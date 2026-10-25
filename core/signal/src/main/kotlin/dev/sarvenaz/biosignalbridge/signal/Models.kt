package dev.sarvenaz.biosignalbridge.signal

/** Acquisition provenance remains visible in recordings and exports. */
enum class Source { PREVIEW, LIVE, IMPORTED }

data class Channel(
    val id: Int,
    val name: String,
    val unit: String,
    val scalePerCount: Double = 1.0,
) {
    init {
        require(id in 0..255)
        require(name.isNotBlank() && name.length <= 120)
        require(unit.isNotBlank() && unit.length <= 32)
        require(scalePerCount.isFinite() && scalePerCount != 0.0 && (scalePerCount * 32768).isFinite())
    }
}

/** timeNs uses a monotonic host clock. Exports subtract the session's originNs. */
data class Sample(
    val timeNs: Long,
    val deviceTickUs: Long,
    val channel: Int,
    val sequence: Int,
    val raw: Int,
    val gapBefore: Boolean,
)

data class Marker(val id: String, val timeNs: Long, val label: String) {
    init {
        require(id.isNotBlank() && id.length <= 128)
        require(label.isNotBlank() && label.length <= 240)
    }
}

data class SignalStats(
    val receivedPackets: Long = 0,
    val acceptedPackets: Long = 0,
    val malformedPackets: Long = 0,
    val lostPackets: Long = 0,
    val duplicatePackets: Long = 0,
    val outOfOrderPackets: Long = 0,
    val clockResets: Long = 0,
)

data class Session(
    val id: String,
    val startedAtEpochMs: Long,
    val originNs: Long,
    val source: Source,
    val channels: List<Channel>,
    val samples: List<Sample>,
    val markers: List<Marker>,
    val stats: SignalStats = SignalStats(),
)

data class SensorPacket(
    val sequence: Int,
    val tickUs: Long,
    val periodUs: Int,
    val channel: Int,
    val samples: List<Int>,
) {
    init {
        require(sequence in 0..65535)
        require(tickUs in 0..0xFFFFFFFFL)
        require(periodUs in 1..65535)
        require(channel in 0..255)
        require(samples.size in 1..4)
        require(samples.all { it in -32768..32767 })
    }
}

enum class PacketStatus { ACCEPTED, MALFORMED, DUPLICATE, OUT_OF_ORDER, CLOCK_RESET_PENDING }

data class ProcessedPacket(
    val samples: List<Sample>,
    val status: PacketStatus,
    val stats: SignalStats,
)
