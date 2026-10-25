package dev.sarvenaz.biosignalbridge.storage

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/** Counters describe transport quality, not medical signal quality. */
data class SessionMetrics(
    val receivedPackets: Long = 0,
    val acceptedPackets: Long = 0,
    val malformedPackets: Long = 0,
    val lostPackets: Long = 0,
    val duplicatePackets: Long = 0,
    val outOfOrderPackets: Long = 0,
    val clockResets: Long = 0,
    val overflowPackets: Long = 0
) {
    fun requireValid() {
        require(listOf(receivedPackets, acceptedPackets, malformedPackets, lostPackets,
            duplicatePackets, outOfOrderPackets, clockResets, overflowPackets).all { it >= 0 }) {
            "Session counters cannot be negative"
        }
    }
}

@Entity(tableName = "sessions")
data class StoredSession(
    @PrimaryKey val id: String,
    val name: String,
    val startedAtEpochMs: Long,
    val source: String,
    val sampleRateHz: Double,
    val unit: String = "ADC counts",
    val scalePerCount: Double = 1.0,
    val status: String = "recording",
    val sampleCount: Long = 0,
    val durationNs: Long = 0,
    @Embedded(prefix = "quality_") val metrics: SessionMetrics = SessionMetrics()
)

/** Library rows include note counts without loading note bodies. */
data class SessionSummary(
    @Embedded val session: StoredSession,
    val markerCount: Int
)

@Entity(
    tableName = "samples",
    primaryKeys = ["sessionId", "index"],
    foreignKeys = [ForeignKey(
        entity = StoredSession::class, parentColumns = ["id"], childColumns = ["sessionId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index(value = ["sessionId", "timeNs"])]
)
data class StoredSample(
    val sessionId: String,
    val index: Long,
    val timeNs: Long,
    val deviceTickUs: Long,
    val channel: Int,
    val sequence: Int,
    val raw: Int,
    val gapBefore: Int = 0
)

@Entity(
    tableName = "markers",
    foreignKeys = [ForeignKey(
        entity = StoredSession::class, parentColumns = ["id"], childColumns = ["sessionId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("sessionId")]
)
data class StoredMarker(
    @PrimaryKey val id: String,
    val sessionId: String,
    val timeNs: Long,
    val label: String
)

class SessionLimitException(message: String) : IllegalStateException(message)
