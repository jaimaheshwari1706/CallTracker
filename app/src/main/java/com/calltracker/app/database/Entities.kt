package com.calltracker.app.database

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** Local queue state. PENDING -> SYNCED on success, PENDING -> FAILED when retries are exhausted. */
enum class SyncStatus { PENDING, SYNCED, FAILED }

/**
 * Direction is only ever "who dialled". Whether the call connected is a
 * separate axis — see [CallStatus]. The Android call log conflates the two in
 * a single TYPE column (MISSED_TYPE is an incoming call that was not answered),
 * so the mapper splits it back apart.
 */
enum class CallDirection { INCOMING, OUTGOING, UNKNOWN }

/** What happened to the call, independent of who started it. */
enum class CallStatus {
    ANSWERED,
    NOT_CONNECTED,      // dialled/rang but duration == 0
    MISSED,
    REJECTED,
    BLOCKED,
    VOICEMAIL,
    ANSWERED_ELSEWHERE, // CallLog.Calls.ANSWERED_EXTERNALLY_TYPE
    UNKNOWN
}

/** Outcome recorded for every call-log row the app has looked at. */
enum class ProcessingOutcome {
    STORED,             // passed the privacy filter and is in `calls`
    EXCLUDED_PRIVACY    // discarded before persistence; only the row id is kept
}

/**
 * One accepted (privacy-filtered) call.
 *
 * Deduplication contract:
 *  - [deviceCallId] is the Android `CallLog.Calls._ID` as a string and carries a
 *    UNIQUE index. A second insert for the same row is rejected by SQLite, not
 *    just by an application-level check.
 *  - The application-level check lives in [CallDao.recordAccepted], inside a
 *    Room @Transaction, so a concurrent observer callback cannot interleave a
 *    read-then-write.
 *  - [ProcessedCallEntity] is written in the same transaction, which is what
 *    makes *excluded* rows idempotent too (they are never in `calls`, so `calls`
 *    alone could not dedupe them).
 */
@Entity(
    tableName = "calls",
    indices = [
        Index(value = ["deviceCallId"], unique = true),
        Index(value = ["syncStatus"]),
        Index(value = ["startedAtEpochMs"])
    ]
)
data class CallEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,

    /** Android CallLog._ID — the de-dupe key. UNIQUE. */
    val deviceCallId: String,

    val phoneNumber: String,
    val normalizedNumber: String,

    /**
     * Local-only. Populated from ContactsContract.PhoneLookup when READ_CONTACTS
     * was granted. Never placed in a sync payload — see CallSyncPayload.
     */
    val contactName: String? = null,

    val direction: CallDirection,
    val status: CallStatus,

    val startedAtEpochMs: Long,
    val durationSeconds: Int,
    /** Derived: startedAt + duration. Stored so the value tested on-device is the stored one. */
    val endedAtEpochMs: Long,

    // --- SIM: never invented. null means "this device did not tell us". ---
    val simSlot: Int? = null,
    val simSubscriptionId: Int? = null,
    val simCarrierName: String? = null,
    /** Raw CallLog.Calls.PHONE_ACCOUNT_ID, kept verbatim for the OEM matrix. */
    val phoneAccountId: String? = null,

    val syncStatus: SyncStatus = SyncStatus.PENDING,
    val syncAttempts: Int = 0,
    val lastSyncAttemptAtEpochMs: Long? = null,

    val capturedAtEpochMs: Long = System.currentTimeMillis()
)

/**
 * A marker for every call-log row the capture pipeline has already decided on,
 * whether it was stored or dropped by the privacy filter.
 *
 * This is intentionally separate from `calls`: an excluded number must not be
 * persisted, but it also must not be re-evaluated (and re-counted) every time
 * the ContentObserver fires. Only the call-log row id and the outcome are kept
 * here — no phone number.
 */
@Entity(tableName = "processed_call_log_rows")
data class ProcessedCallEntity(
    @PrimaryKey val deviceCallId: String,
    val outcome: ProcessingOutcome,
    val processedAtEpochMs: Long = System.currentTimeMillis()
)

/**
 * Numbers an employee or admin has marked personal.
 * Rows here never get read by the sync worker — see PrivacyFilter.
 */
@Entity(tableName = "excluded_numbers")
data class ExcludedNumberEntity(
    @PrimaryKey val normalizedNumber: String,
    val label: String? = null,
    val addedAtEpochMs: Long = System.currentTimeMillis()
)

/**
 * Append-only local diagnostic log. This is the thing that makes the OEM matrix
 * fillable: it records what fired, when, in order.
 */
@Entity(tableName = "diagnostic_events", indices = [Index(value = ["atEpochMs"])])
data class DiagnosticEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val atEpochMs: Long,
    /** e.g. TELEPHONY, CALL_LOG_CHANGED, CALL_LOG_READ, PRIVACY_CHECK, CALL_SAVED. */
    val type: String,
    /** Short detail. Must never contain a full phone number. */
    val detail: String
)
