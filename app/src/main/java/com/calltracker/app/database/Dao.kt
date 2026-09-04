package com.calltracker.app.database

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

/**
 * Idempotency lives here, on purpose, rather than being spread through the
 * service. Two layers:
 *
 *  1. SQLite UNIQUE index on calls.deviceCallId + PRIMARY KEY on
 *     processed_call_log_rows.deviceCallId. Even a buggy caller cannot create
 *     a second row for the same CallLog._ID.
 *  2. recordAccepted / recordExcluded run inside a Room @Transaction and
 *     return false when the row was already handled, so counters and diagnostic
 *     events are not double-incremented either.
 */
@Dao
abstract class CallDao {

    // --- raw inserts (used only by the transactional helpers below) ---

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insertCallIgnoringConflicts(call: CallEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insertProcessedMarkerIgnoringConflicts(marker: ProcessedCallEntity): Long

    @Query("SELECT EXISTS(SELECT 1 FROM processed_call_log_rows WHERE deviceCallId = :deviceCallId)")
    abstract suspend fun isProcessed(deviceCallId: String): Boolean

    @Query("SELECT EXISTS(SELECT 1 FROM calls WHERE deviceCallId = :deviceCallId)")
    abstract suspend fun callExists(deviceCallId: String): Boolean

    /**
     * Persists an accepted call and its processed-marker atomically.
     * Returns true if this call was newly stored, false if already handled.
     */
    @Transaction
    open suspend fun recordAccepted(call: CallEntity): Boolean {
        if (isProcessed(call.deviceCallId)) return false
        insertProcessedMarkerIgnoringConflicts(
            ProcessedCallEntity(call.deviceCallId, ProcessingOutcome.STORED)
        )
        // Belt and braces: the UNIQUE index makes this a no-op if a marker was
        // somehow missing but the call row already existed.
        return insertCallIgnoringConflicts(call) != -1L
    }

    /**
     * Records that a call-log row was dropped by the privacy filter. The number
     * itself is deliberately not persisted anywhere.
     * Returns true the first time this row is excluded.
     */
    @Transaction
    open suspend fun recordExcluded(deviceCallId: String): Boolean {
        if (isProcessed(deviceCallId)) return false
        return insertProcessedMarkerIgnoringConflicts(
            ProcessedCallEntity(deviceCallId, ProcessingOutcome.EXCLUDED_PRIVACY)
        ) != -1L
    }

    // --- sync queue ---

    @Query("SELECT * FROM calls WHERE syncStatus = 'PENDING' ORDER BY startedAtEpochMs ASC LIMIT :limit")
    abstract suspend fun getPendingBatch(limit: Int = 50): List<CallEntity>

    @Query(
        "UPDATE calls SET syncStatus = :status, syncAttempts = syncAttempts + 1, " +
            "lastSyncAttemptAtEpochMs = :atEpochMs WHERE id IN (:ids)"
    )
    abstract suspend fun applySyncResult(ids: List<Long>, status: SyncStatus, atEpochMs: Long)

    /** Manual retry-failed action on the diagnostics screen. */
    @Query("UPDATE calls SET syncStatus = 'PENDING', syncAttempts = 0 WHERE syncStatus = 'FAILED'")
    abstract suspend fun requeueFailed(): Int

    // --- counters for the diagnostics screen ---

    @Query("SELECT COUNT(*) FROM calls")
    abstract fun observeCapturedCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM calls WHERE syncStatus = 'PENDING'")
    abstract fun observePendingCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM calls WHERE syncStatus = 'SYNCED'")
    abstract fun observeSyncedCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM calls WHERE syncStatus = 'FAILED'")
    abstract fun observeFailedCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM processed_call_log_rows WHERE outcome = 'EXCLUDED_PRIVACY'")
    abstract fun observeExcludedCount(): Flow<Int>

    @Query("SELECT MAX(capturedAtEpochMs) FROM calls")
    abstract fun observeLastCapturedAt(): Flow<Long?>

    @Query("SELECT * FROM calls ORDER BY startedAtEpochMs DESC LIMIT :limit")
    abstract fun observeRecentCalls(limit: Int = 25): Flow<List<CallEntity>>

    /** Highest call-log id already decided on; used to seed the scan watermark. */
    @Query("SELECT MAX(CAST(deviceCallId AS INTEGER)) FROM processed_call_log_rows")
    abstract suspend fun highestProcessedCallLogId(): Long?

    @Query("DELETE FROM calls")
    abstract suspend fun deleteAllCalls()

    @Query("DELETE FROM processed_call_log_rows")
    abstract suspend fun deleteAllProcessedMarkers()
}

@Dao
interface ExcludedNumberDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun exclude(entry: ExcludedNumberEntity)

    @Delete
    suspend fun remove(entry: ExcludedNumberEntity)

    @Query("DELETE FROM excluded_numbers WHERE normalizedNumber = :normalizedNumber")
    suspend fun removeByNumber(normalizedNumber: String)

    @Query("SELECT normalizedNumber FROM excluded_numbers")
    suspend fun getAllExcluded(): List<String>

    @Query("SELECT * FROM excluded_numbers ORDER BY addedAtEpochMs DESC")
    fun observeAll(): Flow<List<ExcludedNumberEntity>>
}

@Dao
interface DiagnosticEventDao {

    @Insert
    suspend fun insert(event: DiagnosticEventEntity)

    @Query("SELECT * FROM diagnostic_events ORDER BY id DESC LIMIT :limit")
    fun observeRecent(limit: Int = 200): Flow<List<DiagnosticEventEntity>>

    /** Keeps the on-device log bounded; a POC diagnostic, not an audit trail. */
    @Query(
        "DELETE FROM diagnostic_events WHERE id NOT IN " +
            "(SELECT id FROM diagnostic_events ORDER BY id DESC LIMIT :keep)"
    )
    suspend fun trimTo(keep: Int)

    @Query("DELETE FROM diagnostic_events")
    suspend fun clear()
}
