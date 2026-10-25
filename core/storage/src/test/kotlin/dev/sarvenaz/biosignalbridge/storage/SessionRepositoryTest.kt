package dev.sarvenaz.biosignalbridge.storage

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@LooperMode(LooperMode.Mode.PAUSED)
class SessionRepositoryTest {
    private lateinit var database: BridgeDatabase
    private lateinit var repository: SessionRepository

    @Before fun openDatabase() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(), BridgeDatabase::class.java
        ).allowMainThreadQueries().build()
        repository = SessionRepository(database)
    }

    @After fun closeDatabase() = database.close()

    private fun session(id: String = "session") = StoredSession(
        id, "Desk sensor", 1_700_000_000_000, "live", 500.0
    )

    private fun samples(start: Long, count: Int) = List(count) { offset ->
        val index = start + offset
        StoredSample("session", index, index * 2_000_000, index * 2_000, 0,
            (index % 65536).toInt(), ((index % 65536) - 32768).toInt(), if (index == 2L) 1 else 0)
    }

    @Test fun commitsSamplesCountDurationAndGapTogether() = runBlocking {
        repository.createSession(session())
        repository.appendSamples("session", samples(0, 2))
        val quality = SessionMetrics(receivedPackets = 2, acceptedPackets = 2)
        repository.appendSamples("session", samples(2, 2), quality)
        val stored = repository.getSession("session")!!
        assertEquals(quality, stored.metrics)
        assertEquals(4L, stored.sampleCount)
        assertEquals(6_000_000L, stored.durationNs)
        assertEquals(4L, repository.observeSampleCount("session").first())
        assertEquals(1, repository.readSamples("session")[2].gapBefore)
    }

    @Test fun rejectsWholeInvalidBatchWithoutChangingSavedCount() = runBlocking {
        repository.createSession(session())
        repository.appendSamples("session", samples(0, 2))
        expectFailure<IllegalArgumentException> {
            repository.appendSamples("session", samples(2, 2).mapIndexed { i, sample ->
                if (i == 1) sample.copy(index = 9) else sample
            }, SessionMetrics(receivedPackets = 99))
        }
        assertEquals(2L, repository.getSession("session")!!.sampleCount)
        assertEquals(SessionMetrics(), repository.getMetrics("session"))
        assertEquals(listOf(0L, 1L), repository.readSamples("session").map { it.index })
    }

    @Test fun sampleLimitRejectsNewBatchAndRetainsEverySavedMeasurement() = runBlocking {
        repository.createSession(session())
        var next = 0L
        while (next < SessionRepository.MAX_SAMPLES) {
            val count = minOf(2_000L, SessionRepository.MAX_SAMPLES - next).toInt()
            repository.appendSamples("session", samples(next, count))
            next += count
        }
        expectFailure<SessionLimitException> { repository.appendSamples("session", samples(next, 1)) }
        assertEquals(100_000L, repository.getSession("session")!!.sampleCount)
        assertEquals(0L, repository.readSamples("session", 1).single().index)
        assertEquals(99_999L, repository.readSamples("session", 1, 99_999).single().index)
    }

    @Test fun completedSessionRejectsFurtherSamplesAndMarkers() = runBlocking {
        repository.createSession(session())
        repository.appendSamples("session", samples(0, 1))
        repository.finish("session")
        repository.finish("session")
        expectFailure<IllegalStateException> { repository.appendSamples("session", samples(1, 1)) }
        expectFailure<IllegalStateException> { repository.addMarker(StoredMarker("mark", "session", 0, "Rest")) }
        assertEquals("complete", repository.getSession("session")!!.status)
    }

    @Test fun recoveryMarksOnlyUnfinishedSessionsAborted() = runBlocking {
        repository.createSession(session())
        repository.createSession(session("finished"))
        repository.finish("finished")
        repository.createSession(session("pending").copy(status = "pending"))
        assertEquals(2, repository.abortUnfinished())
        assertEquals("aborted", repository.getSession("session")!!.status)
        assertEquals("aborted", repository.getSession("pending")!!.status)
        assertEquals("complete", repository.getSession("finished")!!.status)
    }

    @Test fun storesQualityMetricsAndMarkersInTimeOrder() = runBlocking {
        repository.createSession(session())
        assertEquals(0, repository.observeSessionSummaries().first().single().markerCount)
        val metrics = SessionMetrics(10, 7, 1, 2, 1, 0, 0, 1)
        repository.updateMetrics("session", metrics)
        repository.addMarker(StoredMarker("later", "session", 10, "Movement"))
        repository.addMarker(StoredMarker("earlier", "session", 0, "Rest"))
        assertEquals(metrics, repository.getMetrics("session"))
        val summary = repository.observeSessionSummaries().first().single()
        assertEquals(2, summary.markerCount)
        assertEquals(metrics, summary.session.metrics)
        assertEquals(listOf("Rest", "Movement"), repository.readMarkers("session").map { it.label })
    }

    @Test fun persistedSessionSurvivesDatabaseCloseAndReopen() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val filename = "bridge-persistence-test.db"
        context.deleteDatabase(filename)
        try {
            val disk = Room.databaseBuilder(context, BridgeDatabase::class.java, filename).build()
            try {
                val writer = SessionRepository(disk)
                writer.createSession(session())
                writer.appendSamples("session", samples(0, 3))
                writer.updateMetrics("session", SessionMetrics(receivedPackets = 3, acceptedPackets = 3))
                writer.finish("session")
            } finally { disk.close() }
            val reopened = Room.databaseBuilder(context, BridgeDatabase::class.java, filename).build()
            try {
                val reader = SessionRepository(reopened)
                assertEquals(3L, reader.getSession("session")!!.sampleCount)
                assertEquals("complete", reader.getSession("session")!!.status)
                assertEquals(3, reader.readSamples("session").size)
                assertEquals(3L, reader.getMetrics("session")!!.acceptedPackets)
            } finally { reopened.close() }
        } finally { context.deleteDatabase(filename) }
    }

    @Test fun interleavedChannelsRetainTheirOwnTimesAndMaximumDuration() = runBlocking {
        repository.createSession(session())
        repository.appendSamples("session", samples(0, 4))
        repository.appendSamples("session", samples(4, 4).mapIndexed { i, value ->
            value.copy(channel = 1, timeNs = i * 2_000_000L)
        })
        assertEquals(6_000_000L, repository.getSession("session")!!.durationNs)
        assertEquals(0L, repository.readSamples("session")[4].timeNs)
        expectFailure<IllegalArgumentException> {
            repository.appendSamples("session", samples(8, 1).map { it.copy(timeNs = 1_000_000) })
        }
        assertEquals(8L, repository.getSession("session")!!.sampleCount)
    }

    @Test fun renameAndDeleteRequireEndedSessionAndDeleteCascades() = runBlocking {
        repository.createSession(session())
        repository.appendSamples("session", samples(0, 2))
        repository.addMarker(StoredMarker("marker", "session", 0, "Rest"))
        expectFailure<IllegalStateException> { repository.renameSession("session", "Renamed") }
        expectFailure<IllegalStateException> { repository.deleteSession("session") }
        val metrics = SessionMetrics(receivedPackets = 1, acceptedPackets = 1)
        repository.finish("session", "complete", metrics)
        repository.renameSession("session", "Renamed")
        assertEquals("Renamed", repository.getSession("session")!!.name)
        assertEquals(metrics, repository.getMetrics("session"))
        repository.deleteSession("session")
        assertEquals(null, repository.getSession("session"))
        assertTrue(repository.readSamples("session").isEmpty())
        assertTrue(repository.readMarkers("session").isEmpty())
    }

    private suspend inline fun <reified T : Throwable> expectFailure(noinline block: suspend () -> Unit) {
        try {
            block()
            throw AssertionError("Expected ${T::class.java.simpleName}")
        } catch (error: Throwable) {
            assertTrue("Expected ${T::class.java.simpleName}, got ${error::class.java.simpleName}", error is T)
        }
    }
}
