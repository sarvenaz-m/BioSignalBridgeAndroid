package dev.sarvenaz.biosignalbridge.signal

import org.junit.Assert.*
import org.junit.Test

class SignalTest {
    private fun packet(sequence: Int, tick: Long = sequence * 4000L, channel: Int = 0) =
        SensorPacket(sequence, tick, 1000, channel, listOf(-32768, -1, 0, 32767))

    @Test fun binaryPacketRoundTripsSignedSamplesAndUnsignedClock() {
        val expected = packet(65535, 0xFFFFFFF0L, 255)
        val bytes = PacketDecoder.encode(expected)
        assertEquals(20, bytes.size)
        assertEquals(0xFF, bytes[2].toInt() and 255)
        assertEquals(DecodeResult.Success(expected), PacketDecoder.decode(bytes))
    }

    @Test fun malformedLengthSignatureVersionCountAndPeriodAreRejected() {
        assertTrue(PacketDecoder.decode(ByteArray(19)) is DecodeResult.Invalid)
        listOf(0 to 0, 1 to 2, 11 to 0, 11 to 5).forEach { (index, value) ->
            val bytes = PacketDecoder.encode(packet(0))
            bytes[index] = value.toByte()
            assertTrue(PacketDecoder.decode(bytes) is DecodeResult.Invalid)
        }
        val noPeriod = PacketDecoder.encode(packet(0))
        noPeriod[8] = 0
        noPeriod[9] = 0
        assertTrue(PacketDecoder.decode(noPeriod) is DecodeResult.Invalid)
    }

    @Test fun unusedSampleBytesMustBeZero() {
        val bytes = PacketDecoder.encode(SensorPacket(1, 10, 1, 0, listOf(42)))
        assertTrue(PacketDecoder.decode(bytes) is DecodeResult.Success)
        bytes[19] = 1
        assertTrue(PacketDecoder.decode(bytes) is DecodeResult.Invalid)
    }

    @Test fun malformedPacketDoesNotAdvanceSequence() {
        val processor = SignalProcessor()
        processor.accept(packet(1), 100_000_000)
        processor.accept(ByteArray(20), 101_000_000)
        val result = processor.accept(packet(2), 104_000_000)
        assertEquals(3, result.stats.receivedPackets)
        assertEquals(2, result.stats.acceptedPackets)
        assertEquals(1, result.stats.malformedPackets)
        assertEquals(0, result.stats.lostPackets)
    }

    @Test fun sequenceWrapCountsOnlyMissingPackets() {
        val processor = SignalProcessor()
        processor.accept(packet(65534, 100), 100_000_000)
        val result = processor.accept(packet(1, 12_100), 112_000_000)
        assertEquals(2, result.stats.lostPackets)
        assertTrue(result.samples.first().gapBefore)
        assertFalse(result.samples.drop(1).any { it.gapBefore })
    }

    @Test fun duplicateAndDelayedPacketsAreNotDelivered() {
        val processor = SignalProcessor()
        processor.accept(packet(20), 100_000_000)
        assertEquals(PacketStatus.DUPLICATE, processor.accept(packet(20), 101_000_000).status)
        val late = processor.accept(packet(19), 102_000_000)
        assertEquals(PacketStatus.OUT_OF_ORDER, late.status)
        assertTrue(late.samples.isEmpty())
        assertEquals(1, late.stats.duplicatePackets)
        assertEquals(1, late.stats.outOfOrderPackets)
        assertEquals(1, late.stats.acceptedPackets)
    }

    @Test fun deviceClockWrapPreservesIntervals() {
        val processor = SignalProcessor()
        val a = processor.accept(packet(1, 0xFFFFFF00L), 100_000_000).samples
        val b = processor.accept(packet(2, 3744L), 104_000_000).samples
        assertEquals(1_000_000, b.first().timeNs - a.last().timeNs)
        assertEquals(0, processor.stats.clockResets)
        assertFalse(b.first().gapBefore)
        assertEquals(744L, a[1].deviceTickUs)
    }

    @Test fun resetWithContinuingSequenceStartsNewMarkedSegment() {
        val processor = SignalProcessor()
        val before = processor.accept(packet(50, 9_000_000), 100_000_000).samples
        val after = processor.accept(packet(51, 0), 110_000_000)
        assertEquals(1, after.stats.clockResets)
        assertTrue(after.samples.first().gapBefore)
        assertTrue(after.samples.first().timeNs > before.last().timeNs)
        assertEquals(0, after.stats.lostPackets)
    }

    @Test fun resetOfBothCountersRequiresTwoCoherentPackets() {
        val processor = SignalProcessor()
        processor.accept(packet(500, 8_000_000), 100_000_000)
        val pending = processor.accept(packet(0, 0), 110_000_000)
        assertEquals(PacketStatus.CLOCK_RESET_PENDING, pending.status)
        assertEquals(1, pending.stats.acceptedPackets)
        val confirmed = processor.accept(packet(1, 4000), 114_000_000)
        assertEquals(PacketStatus.ACCEPTED, confirmed.status)
        assertEquals(8, confirmed.samples.size)
        assertTrue(confirmed.samples.first().gapBefore)
        assertEquals(1, confirmed.stats.clockResets)
        assertEquals(3, confirmed.stats.acceptedPackets)
        assertEquals(0, confirmed.stats.lostPackets)
    }

    @Test fun isolatedOldPacketCannotResetTime() {
        val processor = SignalProcessor()
        processor.accept(packet(500, 8_000_000), 100_000_000)
        processor.accept(packet(0, 0), 110_000_000)
        val current = processor.accept(packet(501, 8_004_000), 114_000_000)
        assertEquals(0, current.stats.clockResets)
        assertEquals(2, current.stats.acceptedPackets)
        assertEquals(1, current.stats.outOfOrderPackets)
        assertFalse(current.samples.first().gapBefore)
    }

    @Test fun resetWithForwardModularSequenceIsAcceptedWithoutInventingLoss() {
        val processor = SignalProcessor()
        processor.accept(packet(50_000, 8_000_000), 100_000_000)
        val restarted = processor.accept(packet(0, 0), 110_000_000)
        assertEquals(PacketStatus.ACCEPTED, restarted.status)
        assertEquals(1, restarted.stats.clockResets)
        assertEquals(0, restarted.stats.lostPackets)
        assertTrue(restarted.samples.first().gapBefore)
    }

    @Test fun resetCandidateCanBeConfirmedByConcurrentOtherChannel() {
        val processor = SignalProcessor()
        processor.accept(packet(500, 8_000_000, 0), 100_000_000)
        assertEquals(PacketStatus.CLOCK_RESET_PENDING, processor.accept(packet(0, 0, 0), 110_000_000).status)
        val confirmed = processor.accept(packet(1, 0, 1), 110_000_001)
        assertEquals(8, confirmed.samples.size)
        assertTrue(confirmed.samples[0].gapBefore)
        assertTrue(confirmed.samples[4].gapBefore)
        assertEquals(confirmed.samples[0].timeNs, confirmed.samples[4].timeNs)
        assertEquals(1, confirmed.stats.clockResets)
    }

    @Test fun sameSequenceAtRestartIsDuplicateUntilNextFrameEstablishesClock() {
        val processor = SignalProcessor()
        processor.accept(packet(0, 8_000_000), 100_000_000)
        assertEquals(PacketStatus.DUPLICATE, processor.accept(packet(0, 0), 110_000_000).status)
        val restarted = processor.accept(packet(1, 4000), 114_000_000)
        assertEquals(1, restarted.stats.clockResets)
        assertEquals(1, restarted.stats.duplicatePackets)
        assertTrue(restarted.samples.first().gapBefore)
    }

    @Test fun alignmentPreservesIntervalsAndConcurrentChannels() {
        val processor = SignalProcessor()
        val a = processor.accept(packet(0, 100, 0), 100_000_000).samples
        val b = processor.accept(packet(1, 100, 1), 101_000_000).samples
        assertEquals(a.map { it.timeNs }, b.map { it.timeNs })
        val next = processor.accept(packet(2, 4100, 0), 108_000_000).samples
        assertEquals(1_000_000, next.first().timeNs - a.last().timeNs)
    }

    @Test fun missingPacketMarksNextSampleInEveryObservedChannel() {
        val processor = SignalProcessor()
        processor.accept(packet(0, 0, 0), 100_000_000)
        processor.accept(packet(1, 0, 1), 100_000_000)
        assertTrue(processor.accept(packet(3, 4000, 0), 104_000_000).samples.first().gapBefore)
        assertTrue(processor.accept(packet(4, 4000, 1), 104_000_000).samples.first().gapBefore)
        assertFalse(processor.accept(packet(5, 8000, 0), 108_000_000).samples.first().gapBefore)
    }

    @Test fun overlappingDeviceSamplesStartMarkedMonotonicSegment() {
        val processor = SignalProcessor()
        val prior = processor.accept(packet(0, 0), 100_000_000).samples
        val overlapping = processor.accept(packet(1, 1000), 101_000_000).samples
        assertTrue(overlapping.first().timeNs > prior.last().timeNs)
        assertTrue(overlapping.first().gapBefore)
        assertEquals(0, processor.stats.clockResets)
    }

    @Test fun resetClearsConnectionStateAndStatistics() {
        val processor = SignalProcessor()
        processor.accept(packet(1), 100_000_000)
        processor.reset()
        assertEquals(SignalStats(), processor.stats)
        assertEquals(PacketStatus.ACCEPTED, processor.accept(packet(1), 200_000_000).status)
    }

    @Test fun decimationRetainsPeaksEndpointsAndGapWithinBudget() {
        val samples = (0..99).map { index -> Sample(index * 1000L, index.toLong(), 0, index, when(index) { 42 -> 30000; 43 -> -30000; else -> index }, index == 50) }
        val output = MinMaxDecimator.decimate(samples, 12)
        assertTrue(output.size <= 12)
        assertEquals(samples.first(), output.first())
        assertEquals(samples.last().timeNs, output.last().timeNs)
        assertTrue(output.any { it.raw == 30000 })
        assertTrue(output.any { it.raw == -30000 })
        assertTrue(output.first { it.timeNs >= 50_000 }.gapBefore)
    }

    @Test(expected = IllegalArgumentException::class) fun mixedChannelsMustBeDecimatedSeparately() {
        MinMaxDecimator.decimate(listOf(Sample(0, 0, 0, 0, 0, false), Sample(1, 0, 1, 0, 0, false)), 10)
    }

    @Test fun exportUsesSessionRelativeTimeAndEscapesMarkerText() {
        val session = session().copy(markers = listOf(Marker("m1", 1007, "Tap \"left\"\nagain")))
        val csv = CsvContract.encode(session)
        assertTrue(csv.startsWith(CsvContract.HEADER + "\n"))
        assertTrue(csv.contains("session-1,0,3,5,5,-20,true\n"))
        val metadata = SessionJson.encode(session)
        assertTrue(metadata.contains("\"originNs\": 0"))
        assertTrue(metadata.contains("\"timeNs\": 7"))
        assertTrue(metadata.contains("Tap \\\"left\\\"\\nagain"))
        assertFalse(metadata.contains("1005"))
    }

    @Test(expected = IllegalArgumentException::class) fun exportRejectsUndeclaredChannel() {
        CsvContract.encode(session().copy(samples = listOf(Sample(1005, 5, 1, 3, -20, true))))
    }

    @Test(expected = IllegalArgumentException::class) fun exportRejectsTimesBeforeSessionOrigin() {
        CsvContract.encode(session().copy(originNs = 2000))
    }

    private fun session() = Session("session-1", 1000, 1000, Source.PREVIEW, listOf(Channel(0, "Signal", "mV", .005)), listOf(Sample(1005, 5, 0, 3, -20, true)), emptyList())
}
