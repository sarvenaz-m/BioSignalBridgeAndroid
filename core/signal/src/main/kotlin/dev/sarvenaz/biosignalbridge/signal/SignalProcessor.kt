package dev.sarvenaz.biosignalbridge.signal

/**
 * Tracks a single device stream. Packet sequence numbers are global across channels.
 * Call from one coroutine or thread, and create a new instance for each connection.
 *
 * Alignment anchors the first sample to its receive time minus the packet span.
 * It preserves device intervals and makes each channel monotonic; it cannot infer
 * radio latency or correct an uncalibrated oscillator.
 */
class SignalProcessor {
    var stats: SignalStats = SignalStats()
        private set

    private var lastSequence: Int? = null
    private var lastTickUs = 0L
    private var relativeTickUs = 0L
    private var anchorNs = 0L
    private var gapGeneration = 0L
    private val channelGapGeneration = mutableMapOf<Int, Long>()
    private val channelLastNs = mutableMapOf<Int, Long>()
    private var candidate: Pair<SensorPacket, Long>? = null

    fun reset() {
        stats = SignalStats()
        lastSequence = null
        lastTickUs = 0
        relativeTickUs = 0
        anchorNs = 0
        gapGeneration = 0
        channelGapGeneration.clear()
        channelLastNs.clear()
        candidate = null
    }

    fun accept(bytes: ByteArray, hostTimeNs: Long): ProcessedPacket = when (val decoded = PacketDecoder.decode(bytes)) {
        is DecodeResult.Success -> accept(decoded.packet, hostTimeNs)
        is DecodeResult.Invalid -> {
            stats = stats.copy(receivedPackets = increment(stats.receivedPackets), malformedPackets = increment(stats.malformedPackets))
            result(PacketStatus.MALFORMED)
        }
    }

    fun accept(packet: SensorPacket, hostTimeNs: Long): ProcessedPacket {
        require(hostTimeNs >= 0) { "Host time must use a non-negative monotonic clock." }
        stats = stats.copy(receivedPackets = increment(stats.receivedPackets))
        val prior = lastSequence
        if (prior == null) return acceptFirst(packet, hostTimeNs, false)

        candidate?.let { (first, receivedNs) ->
            candidate = null
            val sequenceStep = sequenceDelta(packet.sequence, first.sequence)
            val clockStep = tickDelta(packet.tickUs, first.tickUs)
            val coherentClock = clockStep in 0..2_000_000L && (clockStep > 0 || packet.channel != first.channel)
            if (sequenceStep in 1..16 && coherentClock && hostTimeNs >= receivedNs) {
                stats = stats.copy(clockResets = increment(stats.clockResets))
                newGap()
                val initial = acceptFirst(first, receivedNs, true).samples
                val following = acceptForward(packet, sequenceStep, clockStep).samples
                return ProcessedPacket(initial + following, PacketStatus.ACCEPTED, stats)
            }
            stats = stats.copy(outOfOrderPackets = increment(stats.outOfOrderPackets))
        }

        val sequenceStep = sequenceDelta(packet.sequence, prior)
        val clockStep = tickDelta(packet.tickUs, lastTickUs)
        if (sequenceStep == 0) {
            stats = stats.copy(duplicatePackets = increment(stats.duplicatePackets))
            return result(PacketStatus.DUPLICATE)
        }
        val backwardClock = clockStep > 0x7FFFFFFFL
        val resetSizedJump = backwardClock && lastTickUs - packet.tickUs >= 2_000_000L
        if (sequenceStep in 1..32767) {
            if (resetSizedJump) {
                stats = stats.copy(clockResets = increment(stats.clockResets))
                newGap()
                return acceptFirst(packet, hostTimeNs, true)
            }
            if (!backwardClock) return acceptForward(packet, sequenceStep, clockStep)
        } else if (resetSizedJump && packet.sequence <= 15 && packet.tickUs <= 5_000_000L) {
            // A reset of both counters needs two coherent packets. The first is
            // held until confirmed so an isolated delayed frame cannot reset time.
            candidate = packet to hostTimeNs
            return result(PacketStatus.CLOCK_RESET_PENDING)
        }
        stats = stats.copy(outOfOrderPackets = increment(stats.outOfOrderPackets))
        return result(PacketStatus.OUT_OF_ORDER)
    }

    private fun acceptFirst(packet: SensorPacket, hostTimeNs: Long, afterReset: Boolean): ProcessedPacket {
        lastSequence = packet.sequence
        lastTickUs = packet.tickUs
        relativeTickUs = 0
        val spanNs = (packet.samples.size - 1L) * packet.periodUs * 1000L
        val firstReceiveNs = (hostTimeNs - spanNs).coerceAtLeast(0)
        anchorNs = if (afterReset) maxOf(firstReceiveNs, safeAdd(channelLastNs.values.maxOrNull() ?: 0, 1)) else firstReceiveNs
        return emit(packet)
    }

    private fun acceptForward(packet: SensorPacket, sequenceStep: Int, clockStep: Long): ProcessedPacket {
        if (sequenceStep > 1) {
            stats = stats.copy(lostPackets = add(stats.lostPackets, sequenceStep - 1L))
            newGap()
        } else if (clockStep > 2_000_000L) {
            newGap()
        }
        relativeTickUs = safeAdd(relativeTickUs, clockStep)
        lastTickUs = packet.tickUs
        lastSequence = packet.sequence
        return emit(packet)
    }

    private fun emit(packet: SensorPacket): ProcessedPacket {
        stats = stats.copy(acceptedPackets = increment(stats.acceptedPackets))
        val periodNs = packet.periodUs * 1000L
        val projected = safeAdd(anchorNs, multiplyMicros(relativeTickUs))
        val priorNs = channelLastNs[packet.channel]
        val firstNs = if (priorNs == null) projected else maxOf(projected, safeAdd(priorNs, 1))
        val gap = gapGeneration > (channelGapGeneration[packet.channel] ?: 0) || (priorNs != null && projected <= priorNs)
        channelGapGeneration[packet.channel] = gapGeneration
        val output = packet.samples.mapIndexed { index, value ->
            Sample(
                timeNs = safeAdd(firstNs, index * periodNs),
                deviceTickUs = (packet.tickUs + index.toLong() * packet.periodUs) and 0xFFFFFFFFL,
                channel = packet.channel,
                sequence = packet.sequence,
                raw = value,
                gapBefore = index == 0 && gap,
            )
        }
        channelLastNs[packet.channel] = output.last().timeNs
        return result(PacketStatus.ACCEPTED, output)
    }

    private fun result(status: PacketStatus, samples: List<Sample> = emptyList()) = ProcessedPacket(samples, status, stats)
    private fun newGap() { gapGeneration = increment(gapGeneration) }
    private fun sequenceDelta(current: Int, previous: Int) = (current - previous) and 65535
    private fun tickDelta(current: Long, previous: Long) = (current - previous) and 0xFFFFFFFFL
    private fun increment(value: Long) = add(value, 1)
    private fun add(value: Long, delta: Long) = if (value > Long.MAX_VALUE - delta) Long.MAX_VALUE else value + delta
    private fun safeAdd(value: Long, delta: Long) = add(value, delta)
    private fun multiplyMicros(value: Long) = if (value > Long.MAX_VALUE / 1000) Long.MAX_VALUE else value * 1000
}
