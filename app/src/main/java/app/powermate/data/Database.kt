package app.powermate.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/**
 * Raw meter pulses over an interval. Storing pulses rather than kWh means correcting the meter's
 * pulses-per-kWh setting later fixes all past readings too.
 */
@Entity(tableName = "readings")
data class Reading(
    /** Unix seconds at the start of the interval. */
    @PrimaryKey val timestamp: Long,
    val pulses: Int,
    /**
     * Interval length: 60 s for the device's records and the cloud's minute readings. Imports by
     * older versions stored longer cloud intervals (an hour, a quarter, or an hour's remainder).
     */
    val durationSec: Int,
    val source: Int,
) {
    companion object {
        const val SOURCE_DEVICE = 0
        const val SOURCE_CLOUD = 1
    }
}

data class PulseBucket(val bucket: Long, val pulses: Long)

@Dao
interface ReadingDao {
    @Upsert
    suspend fun upsert(readings: List<Reading>)

    /** Row IDs of the inserted readings; -1 for each one skipped because its minute was taken. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfAbsent(readings: List<Reading>): List<Long>

    /** Cloud totals longer than a minute (from older imports) in the given hours (Unix seconds / 3600). */
    @Query("DELETE FROM readings WHERE source = 1 AND durationSec > 60 AND timestamp / 3600 IN (:hours)")
    suspend fun deleteCoarseCloudHours(hours: List<Long>)

    @Query("DELETE FROM readings WHERE source = 1 AND timestamp >= :start AND timestamp <= :end")
    suspend fun deleteCloudReadings(start: Long, end: Long)

    /**
     * Stores minute readings from the device. They replace the cloud's readings for the same
     * minutes, and any coarser cloud totals for the same hours.
     */
    @Transaction
    suspend fun storeDeviceReadings(readings: List<Reading>) {
        deleteCoarseCloudHours(readings.map { it.timestamp / 3600 }.distinct())
        upsert(readings)
    }

    /**
     * Replaces every cloud reading from [start] to [end] (inclusive) with [readings], keeping the
     * device's own minutes where both exist. Returns how many cloud readings were stored.
     */
    @Transaction
    suspend fun storeCloudReadings(start: Long, end: Long, readings: List<Reading>): Int {
        deleteCloudReadings(start, end)
        return insertIfAbsent(readings).count { it != -1L }
    }

    /** Emits whenever the table changes; used to refresh derived views. */
    @Query("SELECT MAX(timestamp) FROM readings")
    fun observeLatestTimestamp(): Flow<Long?>

    @Query("SELECT MIN(timestamp) FROM readings")
    suspend fun earliestTimestamp(): Long?

    @Query("SELECT MIN(timestamp) FROM readings WHERE source = :source")
    suspend fun earliestTimestampFrom(source: Int): Long?

    @Query("SELECT COUNT(*) FROM readings")
    fun observeCount(): Flow<Int>

    /**
     * Pulses summed into 15-minute buckets. Quarter hours line up with local time in every zone that
     * matters here, including the half-hour offsets of South Australia and the Northern Territory.
     */
    @Query(
        "SELECT (timestamp / 900) * 900 AS bucket, SUM(pulses) AS pulses FROM readings " +
            "WHERE timestamp >= :start AND timestamp < :end GROUP BY timestamp / 900 ORDER BY bucket",
    )
    suspend fun quarterHours(start: Long, end: Long): List<PulseBucket>

    @Query("SELECT * FROM readings WHERE timestamp >= :start AND timestamp < :end ORDER BY timestamp")
    suspend fun between(start: Long, end: Long): List<Reading>

    @Query("SELECT * FROM readings WHERE timestamp > :after ORDER BY timestamp LIMIT :limit")
    suspend fun page(after: Long, limit: Int): List<Reading>

    @Query("DELETE FROM readings")
    suspend fun deleteAll()
}

@Database(entities = [Reading::class], version = 1, exportSchema = true)
abstract class AppDatabase : RoomDatabase() {
    abstract fun readings(): ReadingDao

    companion object {
        fun build(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "powermate.db").build()
    }
}
