package com.calltracker.app.sync

import com.calltracker.app.database.CallDirection
import com.calltracker.app.database.CallEntity
import com.calltracker.app.database.CallStatus

/**
 * The batch the app WOULD send. Built explicitly rather than serializing
 * CallEntity so that what leaves the device is a deliberate choice:
 *
 *  - contactName is not here. Contact data stays on the device.
 *  - excluded numbers are not here, because they were never persisted.
 *
 * Field names match the planned POST /api/v1/calls/sync contract in the README.
 */
data class CallSyncPayload(
    val deviceCallId: String,
    val phoneNumber: String,
    val direction: CallDirection,
    val status: CallStatus,
    val durationSeconds: Int,
    val startedAtEpochMs: Long,
    val endedAtEpochMs: Long,
    val simSlot: Int?
)

fun CallEntity.toSyncPayload() = CallSyncPayload(
    deviceCallId = deviceCallId,
    phoneNumber = normalizedNumber,
    direction = direction,
    status = status,
    durationSeconds = durationSeconds,
    startedAtEpochMs = startedAtEpochMs,
    endedAtEpochMs = endedAtEpochMs,
    simSlot = simSlot
)

/** Three-way result, so the state machine can tell "retry" from "give up". */
enum class SyncOutcome {
    SUCCESS,
    /** Network/5xx/timeout. Worth retrying. */
    TRANSIENT_FAILURE,
    /** Rejected batch, 4xx, malformed. Retrying will not help. */
    PERMANENT_FAILURE
}

/**
 * The whole network surface of the app, behind one interface.
 *
 * There is deliberately no live implementation and no server to run. The POC
 * must be fully testable offline, on a plane, with airplane mode on. When a
 * backend exists, add a RetrofitCallSyncClient here and change one construction
 * site in CallSyncWorker - nothing else moves.
 */
interface CallSyncClient {
    suspend fun sync(deviceId: String, batch: List<CallSyncPayload>): SyncOutcome
}

/**
 * The only implementation the POC ships. It performs no network I/O and always
 * accepts the batch, which is what exercises the local
 * PENDING -> SYNCED transition without a server.
 *
 * This is not a mock of a real backend and must not be mistaken for evidence
 * that synchronization works. It only proves the local queue drains.
 */
class LocalLoopbackSyncClient : CallSyncClient {
    override suspend fun sync(deviceId: String, batch: List<CallSyncPayload>): SyncOutcome =
        SyncOutcome.SUCCESS
}
