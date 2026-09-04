package com.calltracker.app.sync

import com.calltracker.app.database.SyncStatus

/**
 * The complete set of legal sync-state transitions, as a pure function so it can
 * be unit-tested without Room, WorkManager or a network.
 *
 *   PENDING + SUCCESS                          -> SYNCED
 *   PENDING + TRANSIENT (attempts < MAX)       -> PENDING   (retry later)
 *   PENDING + TRANSIENT (attempts >= MAX)      -> FAILED    (stop retrying)
 *   PENDING + PERMANENT                        -> FAILED
 *   FAILED  + SUCCESS                          -> SYNCED    (after manual requeue)
 *   SYNCED  + anything                         -> SYNCED    (terminal)
 *
 * SYNCED is terminal on purpose: a retry that re-delivers an already-accepted
 * batch must never move a call backwards into the queue. That, plus the
 * deviceCallId uniqueness in Room, is what makes a retry idempotent locally.
 * Server-side idempotency is a separate problem the README records.
 */
object SyncStateMachine {

    const val MAX_SYNC_ATTEMPTS = 5

    /**
     * @param attemptsSoFar attempts BEFORE this one, i.e. the stored counter.
     */
    fun next(current: SyncStatus, outcome: SyncOutcome, attemptsSoFar: Int): SyncStatus {
        if (current == SyncStatus.SYNCED) return SyncStatus.SYNCED

        return when (outcome) {
            SyncOutcome.SUCCESS -> SyncStatus.SYNCED
            SyncOutcome.PERMANENT_FAILURE -> SyncStatus.FAILED
            SyncOutcome.TRANSIENT_FAILURE ->
                if (attemptsSoFar + 1 >= MAX_SYNC_ATTEMPTS) SyncStatus.FAILED else SyncStatus.PENDING
        }
    }

    /** True when the worker should ask WorkManager to run again. */
    fun shouldRetry(resulting: SyncStatus): Boolean = resulting == SyncStatus.PENDING
}
