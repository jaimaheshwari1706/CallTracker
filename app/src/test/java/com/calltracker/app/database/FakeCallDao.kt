package com.calltracker.app.database

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * In-memory implementation of the abstract Room DAO methods, so that the REAL
 * `recordAccepted` / `recordExcluded` bodies (the check-then-insert logic Room
 * would run inside a transaction) execute on the JVM.
 *
 * What is real here: the transactional helper logic in [CallDao], the
 * uniqueness semantics of the two tables, and the query helpers the sync path
 * relies on.
 *
 * What is modelled:
 *  - the UNIQUE index / PRIMARY KEY is `ConcurrentHashMap.putIfAbsent`, which
 *    is atomic and therefore the same guarantee SQLite gives;
 *  - `@Transaction` serialization is [txMutex]. Room runs all writes on a single
 *    connection, so two transactions never interleave — the mutex reproduces
 *    that. Tests can also call the helpers WITHOUT the mutex to show that
 *    layer 1 (uniqueness) alone still yields exactly one stored row.
 *
 * This is not a substitute for an instrumented Room test on a device; it
 * verifies the logic we wrote, not Room's implementation of it.
 */
class FakeCallDao : CallDao() {

    val calls = ConcurrentHashMap<String, CallEntity>()          // by deviceCallId
    val ledger = ConcurrentHashMap<String, ProcessedCallEntity>() // by deviceCallId
    private val nextId = AtomicLong(1)
    private val version = MutableStateFlow(0)
    val txMutex = Mutex()

    /** Run [block] the way Room would run a @Transaction method: serialized. */
    suspend fun <T> inTransaction(block: suspend () -> T): T = txMutex.withLock { block() }

    private fun bump() { version.value = version.value + 1 }

    // --- abstract Room methods ---

    override suspend fun insertCallIgnoringConflicts(call: CallEntity): Long {
        val id = if (call.id == 0L) nextId.getAndIncrement() else call.id
        val stored = call.copy(id = id)
        val prior = calls.putIfAbsent(call.deviceCallId, stored)
        bump()
        return if (prior == null) id else -1L
    }

    override suspend fun insertProcessedMarkerIgnoringConflicts(marker: ProcessedCallEntity): Long {
        val prior = ledger.putIfAbsent(marker.deviceCallId, marker)
        bump()
        return if (prior == null) 1L else -1L
    }

    override suspend fun isProcessed(deviceCallId: String): Boolean =
        ledger.containsKey(deviceCallId)

    override suspend fun getPendingBatch(limit: Int): List<CallEntity> =
        calls.values
            .filter { it.syncStatus == SyncStatus.PENDING }
            .sortedBy { it.startedAtEpochMs }
            .take(limit)

    override suspend fun applySyncResult(ids: List<Long>, status: SyncStatus, atEpochMs: Long) {
        for ((key, call) in calls) {
            if (call.id in ids) {
                calls[key] = call.copy(
                    syncStatus = status,
                    syncAttempts = call.syncAttempts + 1,
                    lastSyncAttemptAtEpochMs = atEpochMs
                )
            }
        }
        bump()
    }

    override suspend fun requeueFailed(): Int {
        var n = 0
        for ((key, call) in calls) {
            if (call.syncStatus == SyncStatus.FAILED) {
                calls[key] = call.copy(syncStatus = SyncStatus.PENDING, syncAttempts = 0)
                n++
            }
        }
        bump()
        return n
    }

    override fun observeCapturedCount(): Flow<Int> = version.map { calls.size }
    override fun observePendingCount(): Flow<Int> =
        version.map { calls.values.count { it.syncStatus == SyncStatus.PENDING } }
    override fun observeSyncedCount(): Flow<Int> =
        version.map { calls.values.count { it.syncStatus == SyncStatus.SYNCED } }
    override fun observeFailedCount(): Flow<Int> =
        version.map { calls.values.count { it.syncStatus == SyncStatus.FAILED } }
    override fun observeExcludedCount(): Flow<Int> =
        version.map { ledger.values.count { it.outcome == ProcessingOutcome.EXCLUDED_PRIVACY } }
    override fun observeLastCapturedAt(): Flow<Long?> =
        version.map { calls.values.maxOfOrNull { it.capturedAtEpochMs } }
    override fun observeRecentCalls(limit: Int): Flow<List<CallEntity>> =
        version.map { calls.values.sortedByDescending { it.startedAtEpochMs }.take(limit) }

    // --- test helpers ---

    fun storedCount(): Int = calls.size
    fun excludedCount(): Int = ledger.values.count { it.outcome == ProcessingOutcome.EXCLUDED_PRIVACY }
    fun byDeviceCallId(id: String): CallEntity? = calls[id]
}

/** A representative accepted call, for tests that need an entity. */
fun sampleCall(
    deviceCallId: String,
    direction: CallDirection = CallDirection.OUTGOING,
    status: CallStatus = CallStatus.ANSWERED,
    durationSeconds: Int = 41,
    startedAt: Long = 1_700_000_000_000L
) = CallEntity(
    deviceCallId = deviceCallId,
    phoneNumber = "+919876543210",
    normalizedNumber = "+919876543210",
    direction = direction,
    status = status,
    startedAtEpochMs = startedAt,
    durationSeconds = durationSeconds,
    endedAtEpochMs = startedAt + durationSeconds * 1000L
)
