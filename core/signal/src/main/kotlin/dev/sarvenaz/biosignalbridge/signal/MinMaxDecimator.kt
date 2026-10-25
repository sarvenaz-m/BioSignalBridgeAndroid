package dev.sarvenaz.biosignalbridge.signal

/** Reduces one channel while retaining each bucket's peaks and any intervening gap. */
object MinMaxDecimator {
    fun decimate(samples: List<Sample>, maxPoints: Int): List<Sample> {
        require(maxPoints >= 4) { "Keep at least four points." }
        require(samples.map { it.channel }.distinct().size <= 1) { "Decimate each channel separately." }
        require(samples.zipWithNext().all { (a, b) -> b.timeNs >= a.timeNs }) { "Samples must be ordered by time." }
        if (samples.size <= maxPoints) return samples.toList()
        val bucketCount = (maxPoints - 2) / 2
        val interiorCount = samples.size - 2
        val indices = mutableSetOf(0, samples.lastIndex)
        repeat(bucketCount) { bucket ->
            val start = 1 + (bucket.toLong() * interiorCount / bucketCount).toInt()
            val end = 1 + ((bucket + 1L) * interiorCount / bucketCount).toInt()
            if (start < end) {
                val range = start until end
                indices += range.minBy { samples[it].raw }
                indices += range.maxBy { samples[it].raw }
            }
        }
        var previousIndex = -1
        return indices.sorted().map { index ->
            val crossedGap = (previousIndex + 1..index).any { samples[it].gapBefore }
            previousIndex = index
            samples[index].copy(gapBefore = crossedGap)
        }
    }
}
