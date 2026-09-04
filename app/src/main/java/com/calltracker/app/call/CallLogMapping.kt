package com.calltracker.app.call

import android.provider.CallLog
import com.calltracker.app.database.CallDirection
import com.calltracker.app.database.CallStatus

/**
 * Maps the Android call log's single TYPE column onto the two axes the local
 * model actually needs.
 *
 * The CallLog.Calls.*_TYPE values referenced here are compile-time int
 * constants, so this file has no runtime Android dependency and is unit-testable
 * on the JVM.
 *
 *   TYPE                      direction   status
 *   INCOMING_TYPE      (1)    INCOMING    ANSWERED / NOT_CONNECTED
 *   OUTGOING_TYPE      (2)    OUTGOING    ANSWERED / NOT_CONNECTED
 *   MISSED_TYPE        (3)    INCOMING    MISSED
 *   VOICEMAIL_TYPE     (4)    INCOMING    VOICEMAIL
 *   REJECTED_TYPE      (5)    INCOMING    REJECTED
 *   BLOCKED_TYPE       (6)    INCOMING    BLOCKED
 *   ANSWERED_EXTERNALLY(7)    INCOMING    ANSWERED_ELSEWHERE
 *   anything else             UNKNOWN     UNKNOWN
 *
 * Note MISSED/REJECTED/BLOCKED are all *incoming* calls. The previous scaffold
 * modelled them as directions, which made "how many incoming calls did we see"
 * unanswerable.
 */
fun mapDirection(callLogType: Int): CallDirection = when (callLogType) {
    CallLog.Calls.INCOMING_TYPE,
    CallLog.Calls.MISSED_TYPE,
    CallLog.Calls.VOICEMAIL_TYPE,
    CallLog.Calls.REJECTED_TYPE,
    CallLog.Calls.BLOCKED_TYPE,
    CallLog.Calls.ANSWERED_EXTERNALLY_TYPE -> CallDirection.INCOMING
    CallLog.Calls.OUTGOING_TYPE -> CallDirection.OUTGOING
    else -> CallDirection.UNKNOWN
}

fun mapStatus(callLogType: Int, durationSeconds: Int): CallStatus = when (callLogType) {
    CallLog.Calls.INCOMING_TYPE,
    CallLog.Calls.OUTGOING_TYPE ->
        if (sanitizeDuration(durationSeconds) > 0) CallStatus.ANSWERED else CallStatus.NOT_CONNECTED
    CallLog.Calls.MISSED_TYPE -> CallStatus.MISSED
    CallLog.Calls.VOICEMAIL_TYPE -> CallStatus.VOICEMAIL
    CallLog.Calls.REJECTED_TYPE -> CallStatus.REJECTED
    CallLog.Calls.BLOCKED_TYPE -> CallStatus.BLOCKED
    CallLog.Calls.ANSWERED_EXTERNALLY_TYPE -> CallStatus.ANSWERED_ELSEWHERE
    else -> CallStatus.UNKNOWN
}

/**
 * CallLog.Calls.DURATION is documented as seconds, but some OEM builds have
 * been seen writing a negative value for a cancelled outgoing call. Clamp
 * rather than propagate a negative duration into the queue.
 */
fun sanitizeDuration(rawDurationSeconds: Int): Int =
    if (rawDurationSeconds < 0) 0 else rawDurationSeconds

/**
 * CallLog.Calls.DATE is the call START in epoch millis; DURATION is the
 * connected time in seconds. There is no "ended at" column, so it is derived.
 *
 * For MISSED/REJECTED/BLOCKED, duration is 0 and ended == started. That is
 * correct, not a bug: the call never connected.
 */
fun endedAtEpochMs(startedAtEpochMs: Long, durationSeconds: Int): Long =
    startedAtEpochMs + sanitizeDuration(durationSeconds) * 1000L
