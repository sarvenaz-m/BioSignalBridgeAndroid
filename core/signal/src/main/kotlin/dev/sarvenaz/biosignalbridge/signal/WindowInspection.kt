package dev.sarvenaz.biosignalbridge.signal

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/** Measurements of recorded values and their timing. These are not clinical scores. */
data class WindowInspection(
    val sampleCount: Long,
    val minimum: Double?,
    val maximum: Double?,
    val mean: Double?,
    val rms: Double?,
    val standardDeviation: Double?,
    val observedRateHz: Double?,
    val intervalJitterUs: Double?,
    val gapMarkers: Long,
    val segments: Long,
    val duplicateTimestamps: Long,
    val railSamples: Long,
)

/**
 * One pass, constant memory. Values are normalised to the ADC range before moments are
 * accumulated, so a valid large channel scale cannot overflow an intermediate square.
 * Timing excludes marked gaps and equal timestamps. Channels are inspected independently.
 */
object WindowInspector {
    fun inspect(samples: Sequence<Sample>, channel: Channel): WindowInspection {
        var count = 0L
        var minimum = Double.POSITIVE_INFINITY
        var maximum = Double.NEGATIVE_INFINITY
        var mean = 0.0
        var m2 = 0.0
        var squareMean = 0.0
        var previousNs: Long? = null
        var intervals = 0L
        var intervalMean = 0.0
        var intervalM2 = 0.0
        var gaps = 0L
        var segments = 0L
        var equalTimes = 0L
        var rails = 0L
        val magnitude = abs(channel.scalePerCount) * 32768.0
        val sign = if (channel.scalePerCount < 0) -1.0 else 1.0

        for (sample in samples) {
            if (sample.channel != channel.id) continue
            require(sample.raw in -32768..32767 && sample.timeNs >= 0) { "The window contains an invalid sample" }
            val previous = previousNs
            require(previous == null || sample.timeNs >= previous) { "Channel timestamps must not move backwards" }
            val value = sample.raw / 32768.0 * sign
            count++
            minimum = minOf(minimum, value)
            maximum = maxOf(maximum, value)
            val delta = value - mean
            mean += delta / count
            m2 += delta * (value - mean)
            squareMean += (value * value - squareMean) / count
            if (sample.gapBefore) gaps++
            if (previous == null || sample.gapBefore) segments++
            if (sample.raw == -32768 || sample.raw == 32767) rails++
            if (previous != null) {
                val interval = sample.timeNs - previous
                if (interval == 0L) equalTimes++
                if (!sample.gapBefore && interval > 0) {
                    intervals++
                    val intervalDelta = interval - intervalMean
                    intervalMean += intervalDelta / intervals
                    intervalM2 += intervalDelta * (interval - intervalMean)
                }
            }
            previousNs = sample.timeNs
        }
        return WindowInspection(
            sampleCount = count,
            minimum = if (count > 0) minimum * magnitude else null,
            maximum = if (count > 0) maximum * magnitude else null,
            mean = if (count > 0) mean * magnitude else null,
            rms = if (count > 0) sqrt(max(0.0, squareMean)) * magnitude else null,
            standardDeviation = if (count > 0) sqrt(max(0.0, m2 / count)) * magnitude else null,
            observedRateHz = if (intervals > 0) 1e9 / intervalMean else null,
            intervalJitterUs = if (intervals > 0) sqrt(max(0.0, intervalM2 / intervals)) / 1000.0 else null,
            gapMarkers = gaps,
            segments = segments,
            duplicateTimestamps = equalTimes,
            railSamples = rails,
        )
    }
}
