package dev.sarvenaz.biosignalbridge

import android.app.Application
import android.content.Context
import android.os.Looper
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.sarvenaz.biosignalbridge.capture.SignalTransport
import dev.sarvenaz.biosignalbridge.capture.CapturePhase
import dev.sarvenaz.biosignalbridge.capture.CaptureState
import dev.sarvenaz.biosignalbridge.capture.Peripheral
import dev.sarvenaz.biosignalbridge.capture.ReceivedPacket
import dev.sarvenaz.biosignalbridge.signal.PacketDecoder
import dev.sarvenaz.biosignalbridge.signal.SensorPacket
import dev.sarvenaz.biosignalbridge.storage.BridgeDatabase
import dev.sarvenaz.biosignalbridge.storage.BridgePreferences
import dev.sarvenaz.biosignalbridge.storage.SessionRepository
import dev.sarvenaz.biosignalbridge.storage.StoredSample
import dev.sarvenaz.biosignalbridge.storage.StoredSession
import dev.sarvenaz.biosignalbridge.ui.BridgeMode
import java.io.ByteArrayInputStream
import java.io.File
import java.time.Duration
import java.util.zip.ZipInputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowSystemClock

/** Real Room integration; the BLE boundary is supplied with explicit test frames, without a radio. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
class BridgeEngineTest {
    private lateinit var database: BridgeDatabase
    private lateinit var repository: SessionRepository
    private lateinit var capture: FakeTransport
    private lateinit var owner: CoroutineScope
    private var engine: BridgeEngine? = null
    private val context get() = ApplicationProvider.getApplicationContext<Context>()

    @Before fun prepareDatabase() {
        database = Room.inMemoryDatabaseBuilder(context, BridgeDatabase::class.java).allowMainThreadQueries().build()
        repository = SessionRepository(database)
        capture = FakeTransport()
        owner = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }

    @After fun closeWorkspace() {
        engine?.let { instance ->
            val children = owner.coroutineContext[Job]!!.children.toList()
            owner.cancel()
            runBlocking { children.joinAll() }
        }
        capture.close()
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        database.close()
    }

    @Test fun previewRecordingSavesEverySampleAndExportsProvenance() = runBlocking {
        val workspace = startWorkspace()
        workspace.actions.onRecord()
        await { workspace.state.value.recording && workspace.state.value.running }
        val id = workspace.state.value.activeSessionId!!
        ShadowSystemClock.advanceBy(Duration.ofSeconds(13))
        await { !workspace.state.value.recording }
        val saved = repository.getSession(id)!!
        assertEquals("complete", saved.status)
        assertEquals("PREVIEW", saved.source)
        assertEquals(3_000L, saved.sampleCount)
        assertEquals(750L, saved.metrics.acceptedPackets)
        assertEquals(0L, saved.metrics.lostPackets)
        assertEquals(11_996_000_000L, saved.durationNs)
        assertEquals(.005, saved.scalePerCount, 0.0)
        val bytes = workspace.exportArchive(id)
        File("build/verification/native-preview-session.zip").apply { requireNotNull(parentFile).mkdirs(); writeBytes(bytes) }
        val archive = unzip(bytes)
        assertEquals(setOf("session.csv", "metadata.json", "provenance.json"), archive.keys)
        assertEquals(3_001, archive.getValue("session.csv").trim().lines().size)
        val metadata = JSONObject(archive.getValue("metadata.json"))
        assertEquals("PREVIEW", metadata.getString("source"))
        assertEquals(3_000, metadata.getInt("sampleCount"))
        assertEquals("complete", metadata.getString("recordingStatus"))
        assertEquals(0L, metadata.getLong("originNs"))
        val inspection = metadata.getJSONObject("inspection").getJSONArray("channels").getJSONObject(0)
        assertEquals(3_000L, inspection.getLong("sampleCount"))
        assertEquals(250.0, inspection.getDouble("observedRateHz"), 1e-9)
    }

    @Test fun pausingPreviewKeepsRecordingOpenAndPreservesMarkerTime() = runBlocking {
        val workspace = startWorkspace()
        workspace.actions.onRecord()
        await { workspace.state.value.recording && workspace.state.value.running }
        ShadowSystemClock.advanceBy(Duration.ofMillis(400))
        await { workspace.state.value.totalSamples >= 80 }
        workspace.actions.onPlayPause()
        await { !workspace.state.value.running }
        val count = workspace.state.value.totalSamples
        val id = workspace.state.value.activeSessionId!!
        Thread.sleep(80)
        assertTrue(workspace.state.value.recording)
        assertEquals(count, workspace.state.value.totalSamples)
        workspace.actions.onMarker("Posture check")
        await { workspace.state.value.markers.size == 1 }
        workspace.actions.onRecord()
        await { !workspace.state.value.recording }
        val row = repository.getSession(id)!!
        assertEquals("complete", row.status)
        assertEquals(count, row.sampleCount)
        val marker = repository.readMarkers(id).single()
        assertEquals("Posture check", marker.label)
        assertEquals(row.durationNs, marker.timeNs)
    }

    @Test fun liveRecordingUsesDeclaredRateAndRetainsInterleavedChannels() = runBlocking {
        val workspace = startWorkspace()
        readyLive(workspace)
        emitFrame(sequence = 0, tickUs = 0, channel = 42)
        await { workspace.state.value.points.isNotEmpty() }
        assertEquals(100, workspace.state.value.rateHz)
        workspace.actions.onRecord()
        await { workspace.state.value.recording }
        val id = workspace.state.value.activeSessionId!!
        emitFrame(sequence = 1, tickUs = 40_000, channel = 42)
        emitFrame(sequence = 2, tickUs = 40_000, channel = 7)
        await { workspace.state.value.totalSamples == 8L }
        workspace.actions.onRecord()
        await { !workspace.state.value.recording }
        val row = repository.getSession(id)!!
        assertEquals("LIVE", row.source)
        assertEquals(100.0, row.sampleRateHz, 0.0)
        assertEquals("ADC counts", row.unit)
        assertEquals(1.0, row.scalePerCount, 0.0)
        val samples = repository.readSamples(id)
        assertEquals(setOf(42, 7), samples.map { it.channel }.toSet())
        assertEquals(listOf(0L, 10_000_000L, 20_000_000L, 30_000_000L), samples.filter { it.channel == 7 }.map { it.timeNs })
        workspace.actions.onSessionOpen(id)
        await { workspace.state.value.mode == BridgeMode.REVIEW }
        workspace.actions.onChannel(7)
        await { workspace.state.value.selectedChannel == 7 && workspace.state.value.channelName == "Channel 7" }
        assertEquals("Channel 7", workspace.state.value.channelName)
        val metadata = JSONObject(unzip(workspace.exportArchive(id)).getValue("metadata.json"))
        assertEquals(100.0, metadata.getDouble("sampleRateHz"), 0.0)
        assertEquals(2, metadata.getJSONArray("channels").length())
        assertEquals("LIVE", metadata.getString("source"))
    }

    @Test fun overflowClosesRecordingAndKeepsCommittedSamplesVisible() = runBlocking {
        val workspace = startWorkspace()
        readyLive(workspace)
        emitFrame(sequence = 0, tickUs = 0, channel = 0)
        await { workspace.state.value.points.isNotEmpty() }
        workspace.actions.onRecord()
        await { workspace.state.value.recording }
        val id = workspace.state.value.activeSessionId!!
        emitFrame(sequence = 1, tickUs = 40_000, channel = 0)
        await { workspace.state.value.totalSamples == 4L }
        capture.overflow.value = 1
        await { !workspace.state.value.recording }
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        val saved = repository.getSession(id)!!
        assertEquals("error", saved.status)
        assertEquals(4L, saved.sampleCount)
        assertEquals(1L, saved.metrics.overflowPackets)
        await { workspace.state.value.message.contains("queue filled") }
        val metadata = JSONObject(unzip(workspace.exportArchive(id)).getValue("metadata.json"))
        assertEquals("error", metadata.getString("recordingStatus"))
        assertEquals(1L, metadata.getLong("transportOverflowPackets"))
    }

    @Test fun zoomIntoLongRecordingRetainsSamplesInTheVisibleWindow() = runBlocking {
        val id = "long-test-session"
        repository.createSession(StoredSession(id, "Long signal test", 1_700_000_000_000, "LIVE", 250.0))
        repeat(50) { batch ->
            repository.appendSamples(id, List(2_000) { offset ->
                val index = batch * 2_000L + offset
                StoredSample(id, index, index * 4_000_000, index * 4_000, 0, (index / 4 % 65536).toInt(),
                    ((index % 200) - 100).toInt())
            })
        }
        repository.finish(id)
        val workspace = startWorkspace()
        workspace.actions.onSessionOpen(id)
        await { workspace.state.value.mode == BridgeMode.REVIEW }
        workspace.actions.onWindow(2f)
        await { workspace.state.value.showWindowSec == 2f }
        workspace.actions.onSeek(200f)
        await { workspace.state.value.windowEndSec == 200f && workspace.state.value.points.size >= 500 && workspace.state.value.points.all { it.timeSec in 197.99f..200.01f } }
        assertTrue(workspace.state.value.points.size >= 500)
        assertTrue(workspace.state.value.points.all { it.timeSec in 197.99f..200.01f })
        assertEquals(100_000L, workspace.state.value.totalSamples)
    }

    @Test fun processRestartMarksUnfinishedSessionsAsInterrupted() = runBlocking {
        repository.createSession(StoredSession("interrupted", "Interrupted desk session", 1_700_000_000_000, "LIVE", 250.0))
        startWorkspace()
        assertEquals("aborted", repository.getSession("interrupted")!!.status)
    }

    private fun startWorkspace(): BridgeEngine {
        val value = BridgeEngine(context, capture, repository, BridgePreferences(context), owner)
        engine = value
        await { value.state.value.points.isNotEmpty() }
        return value
    }

    private fun readyLive(workspace: BridgeEngine) {
        workspace.actions.onMode(BridgeMode.LIVE)
        await { workspace.state.value.mode == BridgeMode.LIVE }
        capture.state.value = CaptureState(
            CapturePhase.READY, Peripheral("02:00:00:00:00:01", "Protocol test sensor", -50), "Sensor stream ready")
        await { workspace.state.value.phase == "Connected" }
    }

    private fun emitFrame(sequence: Int, tickUs: Long, channel: Int) = runBlocking {
        val packet = SensorPacket(sequence, tickUs, 10_000, channel, listOf(11, 12, 13, 14))
        val flow = capture.packets
        await { flow.subscriptionCount.value > 0 }
        flow.emit(ReceivedPacket(PacketDecoder.encode(packet), 10_000_000_000L + (tickUs + 30_000) * 1_000))
    }

    private fun await(condition: () -> Boolean) {
        val end = System.nanoTime() + 15_000_000_000L
        while (!condition()) {
            if (System.nanoTime() > end) error("Workspace condition did not complete: ${engine?.state?.value}")
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
    }

    private fun unzip(bytes: ByteArray): Map<String, String> = buildMap {
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                put(entry.name, zip.readBytes().toString(Charsets.UTF_8))
            }
        }
    }

    private class FakeTransport : SignalTransport {
        override val state = MutableStateFlow(CaptureState())
        override val devices = MutableStateFlow<List<Peripheral>>(emptyList())
        override val packets = MutableSharedFlow<ReceivedPacket>(extraBufferCapacity = 128)
        override val overflow = MutableStateFlow(0L)
        override fun scan() { state.value = CaptureState(CapturePhase.SCANNING) }
        override fun stopScan() = Unit
        override fun connect(peripheral: Peripheral) { state.value = CaptureState(CapturePhase.READY, peripheral) }
        override fun disconnect() { state.value = CaptureState(CapturePhase.DISCONNECTED) }
        override fun close() = disconnect()
    }
}
