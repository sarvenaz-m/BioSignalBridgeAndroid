package dev.sarvenaz.biosignalbridge.signal

import org.junit.Assert.*
import org.junit.Test

class WindowInspectorTest {
    private val channel = Channel(0, "Test channel", "mV", .5)
    private fun sample(time: Long, raw: Int, gap: Boolean = false, channel: Int = 0) =
        Sample(time, 0, channel, 0, raw, gap)

    @Test fun emptyWindowHasNoInventedMeasurements() {
        val result = WindowInspector.inspect(emptySequence(), channel)
        assertEquals(0L, result.sampleCount)
        assertEquals(0L, result.segments)
        assertNull(result.mean)
        assertNull(result.observedRateHz)
    }

    @Test fun calibratedValuesAndPopulationMomentsMatchKnownSignal() {
        val values = sequenceOf(sample(0, -2), sample(4_000_000, 0), sample(8_000_000, 2))
        val result = WindowInspector.inspect(values, channel)
        assertEquals(-1.0, result.minimum!!, 1e-12)
        assertEquals(1.0, result.maximum!!, 1e-12)
        assertEquals(0.0, result.mean!!, 1e-12)
        assertEquals(kotlin.math.sqrt(2.0 / 3), result.rms!!, 1e-12)
        assertEquals(result.rms!!, result.standardDeviation!!, 1e-12)
        assertEquals(250.0, result.observedRateHz!!, 1e-12)
        assertEquals(0.0, result.intervalJitterUs!!, 1e-12)
    }

    @Test fun gapsDoNotPolluteRateAndChannelsDoNotShareATimebase() {
        val values = sequenceOf(sample(0, 1), sample(1, 12, channel = 1), sample(4_000_000, 2),
            sample(900_000_000, 3, gap = true), sample(904_000_000, 4))
        val result = WindowInspector.inspect(values, channel)
        assertEquals(4L, result.sampleCount)
        assertEquals(1L, result.gapMarkers)
        assertEquals(2L, result.segments)
        assertEquals(250.0, result.observedRateHz!!, 1e-12)
    }

    @Test fun equalTimestampsAreCountedAndExcludedFromRate() {
        val result = WindowInspector.inspect(sequenceOf(sample(4, 1), sample(4, 2)), channel)
        assertEquals(1L, result.duplicateTimestamps)
        assertNull(result.observedRateHz)
    }

    @Test fun negativeScalePreservesAmplitudeOrderingAndAdcRailCounts() {
        val result = WindowInspector.inspect(sequenceOf(sample(0, -32768), sample(4, 32767)), channel.copy(scalePerCount = -2.0))
        assertEquals(-65534.0, result.minimum!!, 0.0)
        assertEquals(65536.0, result.maximum!!, 0.0)
        assertEquals(2L, result.railSamples)
    }

    @Test fun largeScalesDoNotOverflowIntermediateSquares() {
        val result = WindowInspector.inspect(sequenceOf(sample(0, -32768), sample(1, 32767)), channel.copy(scalePerCount = 1e300))
        assertTrue(result.rms!!.isFinite())
        assertTrue(result.standardDeviation!!.isFinite())
    }

    @Test(expected = IllegalArgumentException::class)
    fun backwardsTimestampsAreRejected() {
        WindowInspector.inspect(sequenceOf(sample(20, 1), sample(10, 2)), channel)
    }
}
