package dev.sarvenaz.biosignalbridge.storage

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

data class ChannelTime(val channel: Int, val timeNs: Long)

@Dao
interface SessionDao {
    @Query("SELECT * FROM sessions ORDER BY startedAtEpochMs DESC, id DESC")
    fun observeSessions(): Flow<List<StoredSession>>

    @Query("""
        SELECT sessions.*,
               (SELECT COUNT(*) FROM markers WHERE markers.sessionId = sessions.id) AS markerCount
        FROM sessions
        ORDER BY startedAtEpochMs DESC, id DESC
    """)
    fun observeSummaries(): Flow<List<SessionSummary>>

    @Query("SELECT * FROM sessions WHERE id = :id")
    suspend fun session(id: String): StoredSession?

    @Insert
    suspend fun insert(session: StoredSession)

    @Update
    suspend fun update(session: StoredSession)

    @Query("DELETE FROM sessions WHERE id = :id")
    suspend fun delete(id: String)

    @Query("UPDATE sessions SET status = 'aborted' WHERE status IN ('recording', 'pending')")
    suspend fun abortUnfinished(): Int

    @Insert
    suspend fun insertSamples(samples: List<StoredSample>)

    @Query("SELECT * FROM samples WHERE sessionId = :id ORDER BY `index` LIMIT :limit OFFSET :offset")
    suspend fun samples(id: String, limit: Int, offset: Long): List<StoredSample>

    @Query("SELECT * FROM samples WHERE sessionId = :id ORDER BY `index` DESC LIMIT 1")
    suspend fun lastSample(id: String): StoredSample?

    @Query("SELECT channel, MAX(timeNs) AS timeNs FROM samples WHERE sessionId = :id GROUP BY channel")
    suspend fun channelTimes(id: String): List<ChannelTime>

    @Query("SELECT COUNT(*) FROM samples WHERE sessionId = :id")
    fun observeCount(id: String): Flow<Long>

    @Query("SELECT * FROM markers WHERE sessionId = :id ORDER BY timeNs, id")
    suspend fun markers(id: String): List<StoredMarker>

    @Query("SELECT COUNT(*) FROM markers WHERE sessionId = :id")
    suspend fun markerCount(id: String): Long

    @Insert
    suspend fun insertMarker(marker: StoredMarker)
}

@Database(
    entities = [StoredSession::class, StoredSample::class, StoredMarker::class],
    version = 1,
    exportSchema = true
)
abstract class BridgeDatabase : RoomDatabase() {
    abstract fun sessions(): SessionDao

    companion object {
        fun open(context: Context): BridgeDatabase = Room.databaseBuilder(
            context.applicationContext, BridgeDatabase::class.java, "biosignal-bridge.db"
        ).build()
    }
}
