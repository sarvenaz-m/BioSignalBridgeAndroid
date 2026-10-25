package dev.sarvenaz.biosignalbridge.storage

import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow

/** Writes a complete batch or rejects it; session limits never discard earlier measurements. */
class SessionRepository(private val database: BridgeDatabase) {
    private val dao = database.sessions()

    fun observeSessions(): Flow<List<StoredSession>> = dao.observeSessions()
    fun observeSessionSummaries(): Flow<List<SessionSummary>> = dao.observeSummaries()
    fun observeSampleCount(id: String): Flow<Long> = dao.observeCount(id)
    suspend fun getSession(id: String): StoredSession? = dao.session(id)
    suspend fun getMetrics(id: String): SessionMetrics? = dao.session(id)?.metrics

    suspend fun createSession(session: StoredSession) {
        require(session.id.isNotBlank() && session.id.length <= 128) { "Choose a valid session ID" }
        require(session.name.isNotBlank() && session.name.length <= 120) { "Use a session name between 1 and 120 characters" }
        require(session.startedAtEpochMs > 0) { "The session start time is invalid" }
        require(session.source.isNotBlank() && session.source.length <= 64) { "Record the session source" }
        require(session.sampleRateHz.isFinite() && session.sampleRateHz in 0.1..100_000.0) { "The sample rate is invalid" }
        require(session.unit.isNotBlank() && session.unit.length <= 64) { "Record the measurement unit" }
        require(session.scalePerCount.isFinite() && session.scalePerCount != 0.0) { "The scale must be finite and nonzero" }
        require(session.status in OPEN_STATES) { "A new session must be open" }
        require(session.sampleCount == 0L && session.durationNs == 0L) { "A new session cannot contain saved measurements" }
        session.metrics.requireValid()
        dao.insert(session)
    }

    suspend fun appendSamples(id: String, samples: List<StoredSample>, metrics: SessionMetrics? = null) {
        metrics?.requireValid()
        if (samples.isEmpty() && metrics == null) return
        require(samples.size <= MAX_BATCH_SIZE) { "Write measurements in batches of at most $MAX_BATCH_SIZE" }
        database.withTransaction {
            val session = dao.session(id) ?: error("This session no longer exists")
            check(session.status in OPEN_STATES) { "This session has already ended" }
            if (session.sampleCount + samples.size > MAX_SAMPLES) {
                throw SessionLimitException("The session reached $MAX_SAMPLES measurements. Finish it and start a new session.")
            }
            val last = dao.lastSample(id)
            SampleBatchRules.validate(id, samples, last, dao.channelTimes(id).associate { it.channel to it.timeNs })
            if (samples.isNotEmpty()) dao.insertSamples(samples)
            dao.update(session.copy(
                sampleCount = session.sampleCount + samples.size,
                durationNs = maxOf(session.durationNs, samples.maxOfOrNull { it.timeNs } ?: 0L),
                metrics = metrics ?: session.metrics
            ))
        }
    }

    suspend fun addMarker(marker: StoredMarker) {
        require(marker.id.isNotBlank() && marker.id.length <= 128) { "Choose a valid marker ID" }
        require(marker.timeNs >= 0) { "A marker cannot be before the session start" }
        require(marker.label.isNotBlank() && marker.label.length <= 240) { "Use a marker label between 1 and 240 characters" }
        database.withTransaction {
            val session = dao.session(marker.sessionId) ?: error("This session no longer exists")
            check(session.status in OPEN_STATES) { "This session has already ended" }
            if (dao.markerCount(marker.sessionId) >= MAX_MARKERS) {
                throw SessionLimitException("This session has reached its $MAX_MARKERS marker limit")
            }
            dao.insertMarker(marker)
        }
    }

    suspend fun finish(id: String, status: String = "complete", metrics: SessionMetrics? = null) {
        metrics?.requireValid()
        require(status in CLOSED_STATES) { "Choose a valid session status" }
        database.withTransaction {
            val session = dao.session(id) ?: error("This session no longer exists")
            if (session.status !in OPEN_STATES) {
                check(session.status == status) { "This session has already ended with a different status" }
            }
            dao.update(session.copy(status = status, metrics = metrics ?: session.metrics))
        }
    }

    suspend fun renameSession(id: String, name: String) {
        require(name.isNotBlank() && name.length <= 120) { "Use a session name between 1 and 120 characters" }
        database.withTransaction {
            val session = dao.session(id) ?: error("This session no longer exists")
            check(session.status in CLOSED_STATES) { "Finish the recording before renaming this session" }
            dao.update(session.copy(name = name.trim()))
        }
    }

    suspend fun deleteSession(id: String) {
        database.withTransaction {
            val session = dao.session(id) ?: error("This session no longer exists")
            check(session.status in CLOSED_STATES) { "Finish the recording before deleting this session" }
            dao.delete(id)
        }
    }

    suspend fun rename(id: String, name: String) = renameSession(id, name)
    suspend fun delete(id: String) = deleteSession(id)

    suspend fun updateMetrics(id: String, metrics: SessionMetrics) {
        metrics.requireValid()
        database.withTransaction {
            val session = dao.session(id) ?: error("This session no longer exists")
            dao.update(session.copy(metrics = metrics))
        }
    }

    /** Run before accepting a new recording after process restart. */
    suspend fun abortUnfinished(): Int = dao.abortUnfinished()

    /** The caller pages through the immutable completed session for export. */
    suspend fun readSamples(id: String, limit: Int = 10_000, offset: Long = 0): List<StoredSample> {
        require(limit in 1..10_000) { "Read between 1 and 10000 measurements at a time" }
        require(offset >= 0) { "The read offset cannot be negative" }
        return dao.samples(id, limit, offset)
    }

    suspend fun readMarkers(id: String): List<StoredMarker> = dao.markers(id)

    companion object {
        const val MAX_SAMPLES = 100_000L
        const val MAX_BATCH_SIZE = 2_048
        const val MAX_MARKERS = 5_000L
        val OPEN_STATES = setOf("recording", "pending")
        val CLOSED_STATES = setOf("complete", "aborted", "error")
    }
}

/** Kept independent of Room so continuity and time checks can be exercised on the JVM. */
internal object SampleBatchRules {
    fun validate(
        id: String,
        samples: List<StoredSample>,
        previous: StoredSample?,
        channelTimes: Map<Int, Long> = previous?.let { mapOf(it.channel to it.timeNs) } ?: emptyMap()
    ) {
        var nextIndex = (previous?.index ?: -1L) + 1L
        val lastTimes = channelTimes.toMutableMap()
        for (sample in samples) {
            require(sample.sessionId == id) { "A measurement belongs to another session" }
            require(sample.index == nextIndex) { "Measurement indices must continue without gaps" }
            require(sample.timeNs >= (lastTimes[sample.channel] ?: 0L)) { "Measurement times must not move backwards within a channel" }
            require(sample.deviceTickUs in 0..0xFFFFFFFFL && sample.channel in 0..255 &&
                sample.sequence in 0..65535 && sample.raw in -32768..32767 && sample.gapBefore in 0..1) {
                "A measurement has invalid transport fields"
            }
            lastTimes[sample.channel] = sample.timeNs
            nextIndex++
        }
    }
}
