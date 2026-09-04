package com.calltracker.app.call

import android.content.ContentResolver
import android.database.Cursor
import android.provider.CallLog

/** One raw row from CallLog.Calls, before any mapping or filtering. */
data class CallLogRow(
    val id: Long,
    val number: String?,
    val type: Int,
    val dateEpochMs: Long,
    val durationSeconds: Int,
    val phoneAccountId: String?,
    val cachedName: String?
)

/**
 * Why the scan ran. Determines whether the incremental watermark is used, and
 * shows up in the diagnostic log so a tester can see which signal did the work.
 */
enum class ScanReason {
    /** ContentObserver.onChange fired — the primary trigger. */
    CALL_LOG_CHANGED,

    /** TelephonyCallback saw CALL_STATE_IDLE — a nudge, not proof of a new row. */
    CALL_STATE_IDLE,

    /**
     * Full bounded rescan ignoring the watermark: service start, boot, the
     * periodic WorkManager catch-up, or the manual button. This is what covers
     * the window where the process was dead and no observer was registered.
     */
    CATCH_UP,

    MANUAL
}

/**
 * Reads CallLog.Calls. Isolated from the persistence pipeline so the query
 * itself can be reasoned about (and so the projection is in one place).
 *
 * Requires READ_CALL_LOG. Callers must check the permission first; a
 * SecurityException here is surfaced, not swallowed silently.
 */
class CallLogReader(private val contentResolver: ContentResolver) {

    /**
     * @param afterId only rows with _ID greater than this, or null to ignore the
     *   watermark entirely (catch-up scan).
     * @param limit bound on rows examined in one pass. A catch-up scan on a
     *   phone with 5000 historical calls must not read all of them.
     */
    fun query(afterId: Long?, limit: Int): List<CallLogRow> {
        val selection = afterId?.let { "${CallLog.Calls._ID} > ?" }
        val selectionArgs = afterId?.let { arrayOf(it.toString()) }

        // Ordered DESC so a catch-up scan reads the most RECENT rows first; the
        // result is reversed at the end so processing happens oldest-first.
        //
        // "LIMIT n" appended to the sort order is the long-standing way to bound
        // a ContentProvider query, but it is not part of the contract and some
        // OEM providers strip it. The read loop below is therefore also bounded,
        // so a provider that ignores the hint cannot make us walk a 5000-row
        // call history.
        val cursor: Cursor = (
            runCatching {
                contentResolver.query(
                    CallLog.Calls.CONTENT_URI,
                    PROJECTION,
                    selection,
                    selectionArgs,
                    "${CallLog.Calls._ID} DESC LIMIT $limit"
                )
            }.getOrNull() ?: contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                PROJECTION,
                selection,
                selectionArgs,
                "${CallLog.Calls._ID} DESC"
            )
            ) ?: return emptyList()

        val rows = ArrayList<CallLogRow>()
        cursor.use { c ->
            val idIdx = c.getColumnIndexOrThrow(CallLog.Calls._ID)
            val numberIdx = c.getColumnIndexOrThrow(CallLog.Calls.NUMBER)
            val typeIdx = c.getColumnIndexOrThrow(CallLog.Calls.TYPE)
            val dateIdx = c.getColumnIndexOrThrow(CallLog.Calls.DATE)
            val durationIdx = c.getColumnIndexOrThrow(CallLog.Calls.DURATION)
            // Not every OEM provider exposes these; getColumnIndex returns -1
            // rather than throwing, which is why they are not ...OrThrow.
            val accountIdx = c.getColumnIndex(CallLog.Calls.PHONE_ACCOUNT_ID)
            val nameIdx = c.getColumnIndex(CallLog.Calls.CACHED_NAME)

            while (c.moveToNext() && rows.size < limit) {
                rows.add(
                    CallLogRow(
                        id = c.getLong(idIdx),
                        number = c.getString(numberIdx),
                        type = c.getInt(typeIdx),
                        dateEpochMs = c.getLong(dateIdx),
                        durationSeconds = c.getInt(durationIdx),
                        phoneAccountId = if (accountIdx >= 0) c.getString(accountIdx) else null,
                        cachedName = if (nameIdx >= 0) c.getString(nameIdx) else null
                    )
                )
            }
        }
        return rows.asReversed()
    }

    companion object {
        private val PROJECTION = arrayOf(
            CallLog.Calls._ID,
            CallLog.Calls.NUMBER,
            CallLog.Calls.TYPE,
            CallLog.Calls.DATE,
            CallLog.Calls.DURATION,
            CallLog.Calls.PHONE_ACCOUNT_ID,
            CallLog.Calls.CACHED_NAME
        )

        /** Rows examined by an incremental scan. */
        const val INCREMENTAL_LIMIT = 50

        /**
         * Rows examined by a catch-up scan. Large enough to cover a long OEM
         * kill window, small enough not to walk a whole call history.
         */
        const val CATCH_UP_LIMIT = 200
    }
}
