package dev.sarvenaz.biosignalbridge

import android.content.Context
import android.os.SystemClock
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.sarvenaz.biosignalbridge.capture.SignalTransport
import dev.sarvenaz.biosignalbridge.capture.CapturePhase
import dev.sarvenaz.biosignalbridge.capture.Peripheral
import dev.sarvenaz.biosignalbridge.signal.Channel
import dev.sarvenaz.biosignalbridge.signal.CsvContract
import dev.sarvenaz.biosignalbridge.signal.Marker
import dev.sarvenaz.biosignalbridge.signal.MinMaxDecimator
import dev.sarvenaz.biosignalbridge.signal.PacketDecoder
import dev.sarvenaz.biosignalbridge.signal.Sample
import dev.sarvenaz.biosignalbridge.signal.SensorPacket
import dev.sarvenaz.biosignalbridge.signal.Session
import dev.sarvenaz.biosignalbridge.signal.SessionJson
import dev.sarvenaz.biosignalbridge.signal.SignalProcessor
import dev.sarvenaz.biosignalbridge.signal.SignalStats
import dev.sarvenaz.biosignalbridge.signal.Source
import dev.sarvenaz.biosignalbridge.signal.WindowInspector
import dev.sarvenaz.biosignalbridge.storage.BridgePreferences
import dev.sarvenaz.biosignalbridge.storage.DisplayPreferences
import dev.sarvenaz.biosignalbridge.storage.SessionMetrics
import dev.sarvenaz.biosignalbridge.storage.SessionRepository
import dev.sarvenaz.biosignalbridge.storage.StoredMarker
import dev.sarvenaz.biosignalbridge.storage.StoredSample
import dev.sarvenaz.biosignalbridge.storage.StoredSession
import dev.sarvenaz.biosignalbridge.ui.BridgeActions
import dev.sarvenaz.biosignalbridge.ui.BridgeMode
import dev.sarvenaz.biosignalbridge.ui.BridgeTab
import dev.sarvenaz.biosignalbridge.ui.BridgeUiState
import dev.sarvenaz.biosignalbridge.ui.UiDevice
import dev.sarvenaz.biosignalbridge.ui.UiMarker
import dev.sarvenaz.biosignalbridge.ui.UiPoint
import dev.sarvenaz.biosignalbridge.ui.UiSession
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Owns acquisition independently of an Activity. All stream, recording and marker mutations
 * pass through one mutex; a database batch is either committed or reported as a failure.
 */
@Singleton
class BridgeEngine @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val capture: SignalTransport,
    private val repository: SessionRepository,
    private val preferences: BridgePreferences,
    @param:ApplicationScope private val scope: CoroutineScope,
) {
    private val gate = Mutex()
    private val initialized = CompletableDeferred<Unit>()
    @Volatile private var initializationError: String? = null
    private val mutableState = MutableStateFlow(BridgeUiState(message = "Opening the signal workspace"))
    val state: StateFlow<BridgeUiState> = mutableState.asStateFlow()

    private var preview = IntArray(0)
    private var previewRate = 250
    private var previewScale = .005
    private var processor = SignalProcessor()
    private var originNs: Long? = null
    private val channels = linkedMapOf<Int, ArrayDeque<Sample>>()
    private val channelRates = linkedMapOf<Int, Double>()
    private var displayScale = .005
    private var displayIsPreview = true
    private val markers = mutableListOf<UiMarker>()
    private val pending = mutableListOf<StoredSample>()
    private var activeRecording: StoredSession? = null
    private var nextSampleIndex = 0L
    private var recordedDurationNs = 0L
    private var overflowCount = 0L
    private var observedOverflow = 0L
    private var terminalMessage: String? = null
    private var playback: Job? = null
    private var previewSampleIndex = 0
    private var previewSequence = 0
    private var displayPreferences = DisplayPreferences()

    val actions = BridgeActions(
        onTab = { tab -> perform { update { it.copy(tab = tab) } } },
        onPlayPause = { perform { togglePlayback() } },
        onRecord = { perform { toggleRecord() } },
        onMarker = { label -> perform { addMarker(label) } },
        onWindow = { seconds -> perform { setWindow(seconds) } },
        onSeek = { seconds -> perform { seek(seconds) } },
        onDeviceScan = { scan() },
        onConnect = { connect(it) },
        onDisconnect = { disconnect() },
        onChannel = { id -> perform { selectChannel(id) } },
        onMode = { mode -> perform { changeMode(mode) } },
        onSessionOpen = { id -> perform { openSession(id) } },
        onExport = { showMessage("Choose a destination to save this session") },
        onRename = { id, name -> perform { renameSession(id, name) } },
        onDelete = { id -> perform { deleteSession(id) } },
    )

    init {
        // Subscribe before a user can connect: a packet without a consumer is an overflow.
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            capture.packets.collect { packet ->
                if (!awaitReady()) return@collect
                gate.withLock {
                    if (mutableState.value.mode == BridgeMode.LIVE) {
                        try {
                            accept(packet.bytes, packet.hostTimeNs)
                            handleOverflow()
                        } catch (error: Exception) {
                            if (error is CancellationException) throw error
                            recordingFailed(error)
                        }
                    }
                }
            }
        }
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val json = JSONObject(context.assets.open("preview.json").bufferedReader().use { it.readText() })
                    previewRate = json.getInt("sampleRateHz")
                    previewScale = json.getDouble("scalePerCount")
                    val values = json.getJSONArray("samples")
                    preview = IntArray(values.length()) { values.getInt(it) }
                    require(previewRate == 250 && preview.size == 3000 && preview.all { it in -32768..32767 }) {
                        "The bundled ECG excerpt could not be read"
                    }
                    repository.abortUnfinished()
                }
                gate.withLock { resetPreview() }
                initialized.complete(Unit)
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                update { it.copy(phase = "Unavailable", message = error.message ?: "The workspace could not open") }
                initializationError = error.message ?: "The workspace could not open"
                initialized.complete(Unit)
            }
        }
        scope.launch {
            if (!awaitReady()) return@launch
            repository.observeSessionSummaries().collect { rows ->
                gate.withLock {
                    val sessions = rows.map { summary ->
                        val row = summary.session
                        UiSession(row.id, row.name, row.source, formatTime(row.startedAtEpochMs), row.durationNs / 1e9f, row.sampleCount,
                            summary.markerCount)
                    }
                    update { it.copy(sessions = sessions) }
                }
            }
        }
        scope.launch {
            if (!awaitReady()) return@launch
            preferences.values.collect { values ->
                gate.withLock {
                    displayPreferences = values
                    update { it.copy(showWindowSec = values.windowSeconds.toFloat()) }
                    publishPoints()
                }
            }
        }
        scope.launch {
            if (!awaitReady()) return@launch
            capture.devices.collect { devices ->
                gate.withLock {
                    update { current ->
                        current.copy(devices = devices.map {
                            UiDevice(it.address, it.name, "Bridge protocol · BLE", it.rssi,
                                it.address == current.selectedDevice && capture.state.value.phase == CapturePhase.READY)
                        })
                    }
                }
            }
        }
        scope.launch {
            if (!awaitReady()) return@launch
            capture.state.collect { connection ->
                gate.withLock {
                    if (mutableState.value.mode == BridgeMode.LIVE) {
                        if (connection.phase in setOf(CapturePhase.ERROR, CapturePhase.DISCONNECTED) && activeRecording != null) {
                            try {
                                finishRecording(if (connection.phase == CapturePhase.ERROR) "error" else "aborted")
                            } catch (error: Exception) {
                                if (error is CancellationException) throw error
                                recordingFailed(error)
                                return@withLock
                            }
                        }
                        update {
                            val terminal = connection.phase in setOf(CapturePhase.ERROR, CapturePhase.DISCONNECTED)
                            it.copy(
                                phase = if (terminal && terminalMessage != null) "Stopped" else phaseLabel(connection.phase),
                                message = if (terminal) terminalMessage ?: connection.message else connection.message,
                                running = connection.phase == CapturePhase.READY,
                                scanning = connection.phase == CapturePhase.SCANNING,
                                selectedDevice = connection.peripheral?.address,
                                devices = it.devices.map { device -> device.copy(connected = device.address == connection.peripheral?.address && connection.phase == CapturePhase.READY) },
                            )
                        }
                    }
                }
            }
        }
        scope.launch {
            if (!awaitReady()) return@launch
            capture.overflow.collect {
                gate.withLock {
                    if (mutableState.value.mode == BridgeMode.LIVE) {
                        try {
                            handleOverflow()
                        } catch (error: Exception) {
                            if (error is CancellationException) throw error
                            recordingFailed(error)
                        }
                    }
                }
            }
        }
        scope.launch {
            if (!awaitReady()) return@launch
            while (true) {
                delay(250)
                gate.withLock {
                    if (activeRecording != null && pending.isNotEmpty()) {
                        try {
                            flush()
                        } catch (error: Exception) {
                            if (error is CancellationException) throw error
                            recordingFailed(error)
                        }
                    }
                }
            }
        }
    }

    fun showMessage(message: String) { update { it.copy(message = message) } }

    fun scan() = perform {
        if (activeRecording != null) error("Finish the recording before scanning")
        if (capture.state.value.phase == CapturePhase.READY) error("Disconnect the sensor before scanning")
        changeMode(BridgeMode.LIVE)
        capture.scan()
    }

    fun connect(address: String) = perform {
        if (activeRecording != null) error("Finish the recording before changing sensors")
        changeMode(BridgeMode.LIVE)
        clearStream()
        val device = capture.devices.value.firstOrNull { it.address == address } ?: Peripheral(address, "Bridge sensor", 0)
        update { it.copy(selectedDevice = address, phase = "Connecting", message = "Connecting to ${device.name}") }
        withContext(Dispatchers.Main.immediate) { capture.connect(device) }
    }

    fun disconnect() = perform {
        finishRecording("complete")
        stopPlayback()
        capture.disconnect()
        update { it.copy(running = false, scanning = false,
            phase = if (terminalMessage != null) "Stopped" else "Disconnected", message = terminalMessage ?: "Sensor disconnected") }
    }

    fun onBackground() = perform {
        capture.stopScan()
        if (mutableState.value.mode != BridgeMode.LIVE) {
            stopPlayback()
            if (activeRecording != null) {
                finishRecording("complete")
                update { it.copy(message = "Recording saved before leaving the workspace") }
            }
        }
    }

    /** A finished immutable snapshot is exported off the UI thread. */
    suspend fun exportArchive(id: String): ByteArray = withContext(Dispatchers.IO) {
        check(awaitReady()) { initializationError ?: "The workspace could not open" }
        gate.withLock {
            if (activeRecording?.id == id) {
                stopPlayback()
                finishRecording("complete")
            }
            val row = repository.getSession(id) ?: error("This session no longer exists")
            check(row.status !in SessionRepository.OPEN_STATES) { "Finish the recording before exporting it" }
            val values = readAll(id)
            val storedMarkers = repository.readMarkers(id)
            val channelIds = values.map { it.channel }.distinct().sorted().ifEmpty { listOf(0) }
            val session = Session(
                id = id,
                startedAtEpochMs = row.startedAtEpochMs,
                originNs = 0,
                source = Source.valueOf(row.source),
                channels = channelIds.map { Channel(it, channelName(it, row.source == Source.PREVIEW.name), row.unit, row.scalePerCount) },
                samples = values.map { Sample(it.timeNs, it.deviceTickUs, it.channel, it.sequence, it.raw, it.gapBefore != 0) },
                markers = storedMarkers.map { Marker(it.id, it.timeNs, it.label) },
                stats = row.metrics.toStats(),
            )
            val metadata = JSONObject(SessionJson.encode(session)).apply {
                put("name", row.name)
                put("status", row.status)
                put("recordingStatus", row.status)
                put("transportOverflowPackets", row.metrics.overflowPackets)
                put("sampleRateHz", row.sampleRateHz)
                put("sampleRateDescription", "Nominal first channel rate; sample timestamps define the recorded timebase")
                val channelMetadata = getJSONArray("channels")
                repeat(channelMetadata.length()) { index ->
                    val channel = channelMetadata.getJSONObject(index)
                    val rate = inferRate(values.filter { it.channel == channel.getInt("id") }, row.sampleRateHz)
                    channel.put("sampleRateHz", rate)
                }
                getJSONObject("stats").put("overflowPackets", row.metrics.overflowPackets)
                val inspections = org.json.JSONArray()
                session.channels.forEach { channel ->
                    val summary = WindowInspector.inspect(session.samples.asSequence(), channel)
                    inspections.put(JSONObject().apply {
                        put("channel", channel.id)
                        put("sampleCount", summary.sampleCount)
                        put("minimum", summary.minimum ?: JSONObject.NULL)
                        put("maximum", summary.maximum ?: JSONObject.NULL)
                        put("mean", summary.mean ?: JSONObject.NULL)
                        put("rms", summary.rms ?: JSONObject.NULL)
                        put("standardDeviation", summary.standardDeviation ?: JSONObject.NULL)
                        put("observedRateHz", summary.observedRateHz ?: JSONObject.NULL)
                        put("intervalJitterUs", summary.intervalJitterUs ?: JSONObject.NULL)
                        put("gapMarkers", summary.gapMarkers)
                        put("segments", summary.segments)
                        put("duplicateTimestamps", summary.duplicateTimestamps)
                        put("railSamples", summary.railSamples)
                    })
                }
                put("inspection", JSONObject().put("schema", "biosignal-bridge.inspection/v1").put("scope", "whole-recording").put("channels", inspections))
                if (row.source == Source.PREVIEW.name) put("provenanceFile", "provenance.json")
            }.toString(2) + "\n"
            ByteArrayOutputStream().use { output ->
                ZipOutputStream(output).use { zip ->
                    fun entry(name: String, bytes: ByteArray) {
                        zip.putNextEntry(ZipEntry(name).apply { time = 0L })
                        zip.write(bytes)
                        zip.closeEntry()
                    }
                    entry("session.csv", CsvContract.encode(session).toByteArray(Charsets.UTF_8))
                    entry("metadata.json", metadata.toByteArray(Charsets.UTF_8))
                    if (row.source == Source.PREVIEW.name) entry("provenance.json", context.assets.open("provenance.json").use { it.readBytes() })
                }
                update { it.copy(message = "${row.name} is ready to export") }
                output.toByteArray()
            }
        }
    }

    private fun perform(block: suspend () -> Unit): Job = scope.launch {
            try {
                check(awaitReady()) { initializationError ?: "The workspace could not open" }
                gate.withLock { block() }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                showMessage(error.message ?: "That action could not be completed")
            }
    }

    private suspend fun awaitReady(): Boolean {
        initialized.await()
        return initializationError == null
    }

    private fun update(block: (BridgeUiState) -> BridgeUiState) {
        mutableState.update(block)
    }

    private suspend fun changeMode(mode: BridgeMode) {
        if (mode == mutableState.value.mode) return
        require(mode != BridgeMode.REVIEW) { "Open a saved session to review it" }
        stopPlayback()
        finishRecording("complete")
        if (mode == BridgeMode.PREVIEW) {
            capture.disconnect()
            resetPreview()
        } else {
            clearStream()
            displayScale = 1.0
            displayIsPreview = false
            update {
                it.copy(mode = mode, phase = phaseLabel(capture.state.value.phase), message = "Connect a sensor that supports the Bridge protocol",
                    activeSessionId = null, activeSessionName = "Sensor stream", rateHz = 0, unit = "ADC counts", channelName = "Channel 0", markers = emptyList())
            }
        }
    }

    private fun resetPreview() {
        clearStream()
        displayScale = previewScale
        displayIsPreview = true
        channelRates[0] = previewRate.toDouble()
        channels[0] = ArrayDeque(preview.mapIndexed { index, raw ->
            Sample(index * 1_000_000_000L / previewRate, index * 1_000_000L / previewRate, 0, index / 4, raw, false)
        })
        update {
            it.copy(mode = BridgeMode.PREVIEW, phase = "Ready", message = "Explore a 12-second public ECG excerpt",
                activeSessionId = null, activeSessionName = "MIT-BIH · Record 100", running = false, recording = false,
                durationSec = previewDuration(), windowEndSec = minOf(8f, previewDuration()), rateHz = previewRate,
                totalSamples = preview.size.toLong(), unit = "mV", channelName = "ECG · MLII", selectedChannel = 0,
                channelOptions = listOf(0 to "ECG · MLII"), markers = emptyList(), selectedDevice = null, scanning = false)
        }
        publishPoints()
    }

    private fun clearStream() {
        processor = SignalProcessor()
        originNs = null
        channels.clear()
        channelRates.clear()
        markers.clear()
        overflowCount = 0
        observedOverflow = 0
        terminalMessage = null
        previewSampleIndex = 0
        previewSequence = 0
        update {
            it.copy(points = emptyList(), markers = emptyList(), durationSec = 0f, windowEndSec = 0f, totalSamples = 0,
                lostPackets = 0, duplicates = 0, outOfOrder = 0, clockResets = 0, malformed = 0, overflow = 0,
                channelOptions = emptyList(), selectedChannel = 0, windowInspection = null)
        }
    }

    private suspend fun togglePlayback() {
        if (mutableState.value.mode == BridgeMode.LIVE) {
            error("Live monitoring follows the sensor. Disconnect to stop it.")
        }
        if (mutableState.value.running) {
            stopPlayback()
            update { it.copy(phase = if (it.recording) "Recording paused" else "Paused") }
            return
        }
        val sourceMode = mutableState.value.mode
        if (activeRecording == null && mutableState.value.windowEndSec >= mutableState.value.durationSec) {
            update { it.copy(windowEndSec = 0f) }
        }
        val startCursor = if (activeRecording != null) previewSampleIndex / previewRate.toFloat() else mutableState.value.windowEndSec
        val startedNs = SystemClock.elapsedRealtimeNanos()
        update { it.copy(running = true, phase = if (it.recording) "Recording" else "Playing") }
        playback = scope.launch {
            try {
                while (true) {
                    delay(16)
                    val keepPlaying = gate.withLock {
                        if (!mutableState.value.running || mutableState.value.mode != sourceMode) return@withLock false
                        val cursor = startCursor + (SystemClock.elapsedRealtimeNanos() - startedNs) / 1e9f
                        if (activeRecording != null) {
                            val target = minOf(preview.size, (cursor * previewRate).toInt())
                            while (previewSampleIndex < target) {
                                val end = minOf(preview.size, previewSampleIndex + 4)
                                val packet = SensorPacket(previewSequence++ and 65535,
                                    previewSampleIndex * 1_000_000L / previewRate, 1_000_000 / previewRate, 0,
                                    preview.slice(previewSampleIndex until end))
                                val receiveNs = PREVIEW_ORIGIN_NS + (end - 1L) * 1_000_000_000L / previewRate
                                accept(PacketDecoder.encode(packet), receiveNs)
                                previewSampleIndex = end
                            }
                            if (previewSampleIndex >= preview.size) {
                                finishRecording("complete")
                                update { it.copy(running = false, phase = "Saved", message = "ECG preview session saved") }
                                false
                            } else true
                        } else {
                            update { it.copy(windowEndSec = minOf(cursor, it.durationSec)) }
                            publishPoints()
                            if (cursor >= mutableState.value.durationSec) {
                                update { it.copy(running = false, phase = "Finished") }
                                false
                            } else true
                        }
                    }
                    if (!keepPlaying) break
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                gate.withLock { recordingFailed(error) }
            }
        }
    }

    private fun stopPlayback() {
        playback?.cancel()
        playback = null
        update { it.copy(running = it.mode == BridgeMode.LIVE && capture.state.value.phase == CapturePhase.READY) }
    }

    private suspend fun toggleRecord() {
        if (activeRecording != null) {
            stopPlayback()
            finishRecording("complete")
            update { it.copy(phase = "Saved", message = "Session saved on this device") }
            return
        }
        check(mutableState.value.mode != BridgeMode.REVIEW) { "Switch to Preview or Live to record a new session" }
        val isPreview = mutableState.value.mode == BridgeMode.PREVIEW
        check(isPreview || capture.state.value.phase == CapturePhase.READY) { "Connect a sensor and wait for its stream before recording" }
        check(isPreview || channels.isNotEmpty()) { "Wait for the first valid sensor packet before recording" }
        check(isPreview || capture.overflow.value == 0L) { "Reconnect the sensor before recording after a packet queue overflow" }
        val recordingRate = if (isPreview) previewRate.toDouble() else channelRates.values.first()
        check(recordingRate in .1..100_000.0) { "This sample rate is outside the supported recording range" }
        val expectedRates = channelRates.toMap()
        stopPlayback()
        clearStream()
        if (!isPreview) channelRates.putAll(expectedRates)
        val now = System.currentTimeMillis()
        val row = StoredSession(
            id = UUID.randomUUID().toString(), name = if (isPreview) "ECG preview · ${formatTime(now)}" else "Sensor session · ${formatTime(now)}",
            startedAtEpochMs = now, source = if (isPreview) Source.PREVIEW.name else Source.LIVE.name,
            sampleRateHz = recordingRate,
            unit = if (isPreview) "mV" else "ADC counts", scalePerCount = if (isPreview) previewScale else 1.0,
        )
        repository.createSession(row)
        activeRecording = row
        nextSampleIndex = 0
        recordedDurationNs = 0
        pending.clear()
        observedOverflow = if (isPreview) 0 else capture.overflow.value
        update {
            it.copy(recording = true, activeSessionId = row.id, activeSessionName = row.name, phase = "Recording",
                message = if (isPreview) "Recording the public ECG excerpt" else "Recording sensor samples in ADC counts",
                rateHz = recordingRate.toInt(), running = !isPreview, unit = row.unit)
        }
        if (isPreview) togglePlayback()
    }

    private suspend fun accept(bytes: ByteArray, hostTimeNs: Long) {
        val packet = (PacketDecoder.decode(bytes) as? dev.sarvenaz.biosignalbridge.signal.DecodeResult.Success)?.packet
        if (packet != null) {
            val rate = 1_000_000.0 / packet.periodUs
            val priorRate = channelRates[packet.channel]
            check(activeRecording == null || priorRate == null || priorRate == rate) {
                "The sensor changed the sample rate for channel ${packet.channel}. The recording was stopped; start a new session to continue."
            }
            channelRates[packet.channel] = rate
        }
        val result = processor.accept(bytes, hostTimeNs)
        if (result.samples.isNotEmpty()) {
            if (originNs == null) originNs = result.samples.minOf { it.timeNs }
            val origin = originNs!!
            val relative = result.samples.map { it.copy(timeNs = (it.timeNs - origin).coerceAtLeast(0)) }
            relative.forEach { sample ->
                val buffer = channels.getOrPut(sample.channel) { ArrayDeque() }
                buffer.addLast(sample)
                while (buffer.size > MAX_POINTS_PER_CHANNEL) buffer.removeFirst()
                recordedDurationNs = maxOf(recordedDurationNs, sample.timeNs)
            }
            activeRecording?.let { row ->
                val available = SessionRepository.MAX_SAMPLES - nextSampleIndex
                check(relative.size <= available) { "The session reached its 100,000-sample limit. Start a new recording to continue." }
                relative.forEach { sample ->
                    pending += StoredSample(row.id, nextSampleIndex++, sample.timeNs, sample.deviceTickUs,
                        sample.channel, sample.sequence, sample.raw, if (sample.gapBefore) 1 else 0)
                }
                if (pending.size >= 256) flush()
            }
            update {
                it.copy(durationSec = recordedDurationNs / 1e9f, windowEndSec = recordedDurationNs / 1e9f,
                    totalSamples = if (activeRecording != null) nextSampleIndex else it.totalSamples + relative.size,
                    rateHz = packet?.let { value -> (1_000_000.0 / value.periodUs).toInt() } ?: it.rateHz)
            }
            publishPoints()
        }
        publishStats()
    }

    private suspend fun flush() {
        val row = activeRecording ?: return
        if (pending.isNotEmpty()) {
            repository.appendSamples(row.id, pending.toList(), metrics())
            pending.clear()
        } else {
            repository.updateMetrics(row.id, metrics())
        }
    }

    private suspend fun finishRecording(status: String) {
        val row = activeRecording ?: return
        flush()
        repository.finish(row.id, status, metrics())
        activeRecording = null
        update { it.copy(recording = false) }
    }

    private suspend fun recordingFailed(error: Exception) {
        stopPlayback()
        val row = activeRecording
        var unsaved = pending.size
        if (row != null) {
            try { flush(); unsaved = 0 } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { /* Keep the last committed samples available. */ }
            try { repository.finish(row.id, "error", metrics()) } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { /* Recovery marks an unfinished row on next launch. */ }
            activeRecording = null
        }
        pending.clear()
        if (mutableState.value.mode == BridgeMode.LIVE) capture.disconnect()
        val detail = error.message ?: "The stream could not be saved"
        terminalMessage = if (unsaved > 0) "$detail · $unsaved samples in the last batch could not be saved" else detail
        update { it.copy(recording = false, running = false, phase = "Stopped",
            message = terminalMessage!!) }
    }

    private suspend fun handleOverflow() {
        val count = capture.overflow.value
        if (count < observedOverflow) { observedOverflow = count; return }
        if (count == observedOverflow) return
        overflowCount += count - observedOverflow
        observedOverflow = count
        publishStats()
        if (activeRecording != null) {
            finishRecording("error")
            capture.disconnect()
            terminalMessage = "Recording stopped because the incoming packet queue filled. Saved samples remain available."
            update { it.copy(running = false, phase = "Stopped", message = terminalMessage!!) }
        } else update { it.copy(message = "The incoming packet queue filled. Start a new connection before recording.") }
    }

    private suspend fun addMarker(rawLabel: String) {
        val label = rawLabel.trim()
        require(label.isNotBlank() && label.length <= 240) { "Use a marker label between 1 and 240 characters" }
        val row = activeRecording ?: error("Start a recording before adding an event marker")
        val timeNs = recordedDurationNs
        val id = UUID.randomUUID().toString()
        // The marker and preceding measurements retain their order even if a packet arrives now.
        flush()
        repository.addMarker(StoredMarker(id, row.id, timeNs, label))
        markers += UiMarker(id, timeNs / 1e9f, label)
        update { it.copy(markers = markers.toList(), message = "Marker added: $label") }
    }

    private suspend fun setWindow(seconds: Float) {
        require(seconds.isFinite() && seconds in 2f..60f) { "Choose a time window between 2 and 60 seconds" }
        displayPreferences = displayPreferences.copy(windowSeconds = seconds.toInt())
        preferences.update(displayPreferences)
        update { it.copy(showWindowSec = seconds) }
        publishPoints()
    }

    private fun seek(seconds: Float) {
        check(activeRecording == null) { "Pause or finish the recording before moving through its timeline" }
        check(mutableState.value.mode != BridgeMode.LIVE) { "The live timeline follows incoming samples" }
        require(seconds.isFinite()) { "Choose a valid point on the timeline" }
        stopPlayback()
        update { it.copy(windowEndSec = seconds.coerceIn(0f, it.durationSec), phase = "Paused") }
        publishPoints()
    }

    private fun selectChannel(id: Int) {
        require(id in channels) { "This channel has no samples yet" }
        update { it.copy(selectedChannel = id) }
        publishPoints()
    }

    private fun publishPoints() {
        val current = mutableState.value
        val selected = if (current.selectedChannel in channels) current.selectedChannel else channels.keys.firstOrNull() ?: 0
        val isPreview = displayIsPreview
        val scale = displayScale
        val values: List<Sample> = channels[selected] ?: emptyList()
        val startNs = ((current.windowEndSec - current.showWindowSec).coerceAtLeast(0f) * 1e9).toLong()
        val endNs = (current.windowEndSec * 1e9).toLong()
        fun lowerBound(timeNs: Long): Int {
            var left = 0
            var right = values.size
            while (left < right) {
                val mid = (left + right) ushr 1
                if (values[mid].timeNs < timeNs) left = mid + 1 else right = mid
            }
            return left
        }
        val from = (lowerBound(startNs) - 1).coerceAtLeast(0)
        val until = (lowerBound(endNs) + 1).coerceAtMost(values.size)
        val visible = if (from < until) values.subList(from, until) else emptyList()
        val points = if (visible.size > MAX_PLOT_POINTS) MinMaxDecimator.decimate(visible, MAX_PLOT_POINTS) else visible
        val inspection = WindowInspector.inspect(
            visible.asSequence().filter { it.timeNs in startNs..endNs },
            Channel(selected, channelName(selected, isPreview), current.unit, scale),
        )
        update {
            it.copy(points = points.map { value -> UiPoint(value.timeNs / 1e9f, (value.raw * scale).toFloat(), value.gapBefore) },
                windowInspection = inspection,
                selectedChannel = selected, channelName = channelName(selected, isPreview),
                rateHz = channelRates[selected]?.toInt() ?: it.rateHz,
                channelOptions = channels.keys.sorted().map { id -> id to channelName(id, isPreview) })
        }
    }

    private fun publishStats() {
        val stats = processor.stats
        update { it.copy(lostPackets = stats.lostPackets, duplicates = stats.duplicatePackets,
            outOfOrder = stats.outOfOrderPackets, clockResets = stats.clockResets, malformed = stats.malformedPackets, overflow = overflowCount) }
    }

    private fun metrics(): SessionMetrics = with(processor.stats) {
        SessionMetrics(receivedPackets, acceptedPackets, malformedPackets, lostPackets,
            duplicatePackets, outOfOrderPackets, clockResets, overflowCount)
    }

    private suspend fun openSession(id: String) {
        stopPlayback()
        finishRecording("complete")
        capture.disconnect()
        val row = repository.getSession(id) ?: error("This session no longer exists")
        check(row.status !in SessionRepository.OPEN_STATES) { "Finish the recording before reviewing it" }
        val samples = readAll(id)
        val savedMarkers = repository.readMarkers(id)
        clearStream()
        displayScale = row.scalePerCount
        displayIsPreview = row.source == Source.PREVIEW.name
        samples.forEach { sample -> channels.getOrPut(sample.channel) { ArrayDeque() }.addLast(
            Sample(sample.timeNs, sample.deviceTickUs, sample.channel, sample.sequence, sample.raw, sample.gapBefore != 0)) }
        channels.keys.forEach { channel -> channelRates[channel] = inferRate(samples.filter { it.channel == channel }, row.sampleRateHz) }
        markers += savedMarkers.map { UiMarker(it.id, it.timeNs / 1e9f, it.label) }
        update {
            it.copy(tab = BridgeTab.MONITOR, mode = BridgeMode.REVIEW, activeSessionId = row.id, activeSessionName = row.name,
                phase = "Review", message = "Saved ${row.source.lowercase()} session · ${row.status}", running = false, recording = false,
                durationSec = row.durationNs / 1e9f, windowEndSec = minOf(it.showWindowSec, row.durationNs / 1e9f),
                totalSamples = row.sampleCount, rateHz = row.sampleRateHz.toInt(), unit = row.unit, markers = markers.toList(),
                lostPackets = row.metrics.lostPackets, duplicates = row.metrics.duplicatePackets, outOfOrder = row.metrics.outOfOrderPackets,
                clockResets = row.metrics.clockResets, malformed = row.metrics.malformedPackets, overflow = row.metrics.overflowPackets)
        }
        publishPoints()
    }

    private suspend fun renameSession(id: String, rawName: String) {
        val name = rawName.trim()
        require(name.isNotBlank() && name.length <= 120) { "Use a session name between 1 and 120 characters" }
        repository.renameSession(id, name)
        if (activeRecording?.id == id) activeRecording = activeRecording?.copy(name = name)
        update { it.copy(activeSessionName = if (it.activeSessionId == id) name else it.activeSessionName, message = "Session renamed") }
    }

    private suspend fun deleteSession(id: String) {
        check(activeRecording?.id != id) { "Finish the recording before deleting it" }
        repository.deleteSession(id)
        if (mutableState.value.activeSessionId == id) resetPreview()
        update { it.copy(message = "Session deleted from this device") }
    }

    private suspend fun readAll(id: String): List<StoredSample> {
        val result = mutableListOf<StoredSample>()
        while (true) {
            val page = repository.readSamples(id, 10_000, result.size.toLong())
            if (page.isEmpty()) break
            result += page
        }
        return result
    }

    private fun previewDuration() = (preview.size - 1).coerceAtLeast(0) / previewRate.toFloat()
    private fun inferRate(samples: List<StoredSample>, fallback: Double): Double {
        val withinPacket = samples.zipWithNext().firstOrNull { (a, b) ->
            a.sequence == b.sequence && b.timeNs > a.timeNs
        }
        if (withinPacket != null) return 1_000_000_000.0 / (withinPacket.second.timeNs - withinPacket.first.timeNs)
        val consecutive = samples.zipWithNext().firstOrNull { (a, b) ->
            b.timeNs > a.timeNs && b.gapBefore == 0
        }
        return if (consecutive != null) 1_000_000_000.0 / (consecutive.second.timeNs - consecutive.first.timeNs) else fallback
    }
    private fun channelName(id: Int, preview: Boolean) = if (preview && id == 0) "ECG · MLII" else "Channel $id"
    private fun formatTime(epochMs: Long) = DateTimeFormatter.ofPattern("dd MMM · HH:mm").withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(epochMs))
    private fun phaseLabel(phase: CapturePhase) = when (phase) {
        CapturePhase.IDLE -> "Ready"
        CapturePhase.SCANNING -> "Scanning"
        CapturePhase.CONNECTING -> "Connecting"
        CapturePhase.DISCOVERING -> "Reading services"
        CapturePhase.SUBSCRIBING -> "Enabling stream"
        CapturePhase.READY -> "Connected"
        CapturePhase.DISCONNECTED -> "Disconnected"
        CapturePhase.ERROR -> "Connection issue"
    }

    private fun SessionMetrics.toStats() = SignalStats(receivedPackets, acceptedPackets, malformedPackets,
        lostPackets, duplicatePackets, outOfOrderPackets, clockResets)

    private companion object {
        const val MAX_POINTS_PER_CHANNEL = 5_000
        const val MAX_PLOT_POINTS = 2_500
        const val PREVIEW_ORIGIN_NS = 1_000_000_000L
    }
}
