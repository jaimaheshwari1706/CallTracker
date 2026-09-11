package com.calltracker.app.call

import android.content.Context
import com.calltracker.app.database.AppDatabase
import com.calltracker.app.database.CallEntity
import com.calltracker.app.diagnostics.DeviceInfoProvider
import com.calltracker.app.diagnostics.DiagnosticEvents
import com.calltracker.app.diagnostics.DiagnosticsStore
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** What one scan did. Returned so the caller can log and report it. */
data class ScanResult(
    val reason: ScanReason,
    val rowsExamined: Int = 0,
    val stored: Int = 0,
    val excluded: Int = 0,
    val duplicates: Int = 0,
    val skippedNoPermission: Boolean = false,
    val error: String? = null
)

/**
 * CallLog -> normalize -> PrivacyFilter -> Room. The whole pipeline in one
 * place, so the ordering guarantees are readable rather than implied.
 *
 * Concurrency: the ContentObserver fires several times for a single call on
 * most devices, and the telephony IDLE nudge can arrive in the middle of that
 * burst. Every scan therefore runs under a process-wide mutex. Without it, two coroutines
 * could both pass the "already processed" check for the same row before either
 * had written its marker. The database constraints would still stop the
 * duplicate row, but the counters and the diagnostic log would double-count,
 * which would make the OEM matrix lie.
 */
class CallCaptureEngine(
    private val context: Context,
    private val db: AppDatabase = AppDatabase.get(context),
    private val diagnostics: DiagnosticsStore = DiagnosticsStore(context),
    private val privacyFilter: PrivacyFilter = PrivacyFilter(db.excludedNumberDao()),
    private val reader: CallLogReader = CallLogReader(context.contentResolver),
    private val simResolver: SimResolver = SimResolver(context),
    private val contactResolver: ContactResolver = ContactResolver(context)
) {

    suspend fun scan(reason: ScanReason): ScanResult = SCAN_MUTEX.withLock {
        if (!DeviceInfoProvider.hasCallLogPermission(context)) {
            diagnostics.event(
                DiagnosticEvents.SCAN_STARTED,
                "reason" to reason, "result" to "SKIPPED", "cause" to "READ_CALL_LOG denied"
            )
            return@withLock ScanResult(reason, skippedNoPermission = true)
        }

        val useWatermark = reason != ScanReason.CATCH_UP
        val watermark = diagnostics.lastProcessedCallLogId.takeIf { useWatermark && it > 0 }
        val limit = if (useWatermark) {
            CallLogReader.INCREMENTAL_LIMIT
        } else {
            CallLogReader.CATCH_UP_LIMIT
        }

        diagnostics.event(
            DiagnosticEvents.SCAN_STARTED,
            "reason" to reason, "after" to (watermark ?: "-"), "limit" to limit
        )

        val rows = try {
            reader.query(afterId = watermark, limit = limit)
        } catch (e: SecurityException) {
            // Permission revoked between the check above and the query.
            diagnostics.error("CallLogReader.query", e, "reason" to reason)
            return@withLock ScanResult(reason, error = "SecurityException", skippedNoPermission = true)
        } catch (e: Exception) {
            diagnostics.error("CallLogReader.query", e, "reason" to reason)
            return@withLock ScanResult(reason, error = e.javaClass.simpleName)
        }

        diagnostics.event(DiagnosticEvents.CALL_LOG_ROWS_FOUND, "reason" to reason, "rows" to rows.size)

        var stored = 0
        var excluded = 0
        var duplicates = 0
        // Only rows this scan actually finished deciding on advance the
        // watermark. A row that threw would otherwise be skipped forever by the
        // incremental path.
        val examined = ArrayList<Long>(rows.size)

        for (row in rows) {
            val action = try {
                processRow(row)
            } catch (e: Exception) {
                // One bad row must not abort the scan or poison the watermark:
                // it is logged, left un-examined, and picked up by the next
                // catch-up scan.
                diagnostics.error("processRow", e, "callLogId" to row.id)
                continue
            }
            when (action) {
                CaptureAction.STORE -> stored++
                CaptureAction.DISCARD_EXCLUDED -> excluded++
                CaptureAction.SKIP_DUPLICATE -> duplicates++
            }
            examined.add(row.id)
        }

        diagnostics.lastProcessedCallLogId = CapturePipeline.nextWatermark(
            current = diagnostics.lastProcessedCallLogId,
            examinedRowIds = examined
        )

        val summary = "rows=" + rows.size + " stored=" + stored +
            " excluded=" + excluded + " duplicates=" + duplicates
        diagnostics.recordScan(reason.name, summary, duplicates)
        diagnostics.event(
            DiagnosticEvents.SCAN_FINISHED,
            "reason" to reason, "rows" to rows.size, "stored" to stored,
            "excluded" to excluded, "duplicates" to duplicates
        )

        ScanResult(
            reason = reason,
            rowsExamined = rows.size,
            stored = stored,
            excluded = excluded,
            duplicates = duplicates
        )
    }

    /**
     * The documented order, executed literally:
     *   1. read the row
     *   2. is it new?           (processed_call_log_rows)
     *   3. normalize the number
     *   4. privacy filter
     *   5. persist if allowed
     */
    private suspend fun processRow(row: CallLogRow): CaptureAction {
        val deviceCallId = row.id.toString()
        val dao = db.callDao()

        // Step 2. The duplicate check happens BEFORE the privacy check so that a
        // re-notified excluded call is not re-counted as a new exclusion.
        val alreadyProcessed = dao.isProcessed(deviceCallId)
        if (alreadyProcessed) {
            // CapturePipeline.decide() states this rule; it is short-circuited
            // here so a duplicate notification does not cost a privacy-list read.
            diagnostics.event(
                DiagnosticEvents.CALL_DUPLICATE,
                "callLogId" to deviceCallId, "reason" to "already in processed ledger"
            )
            return CaptureAction.SKIP_DUPLICATE
        }

        val duration = sanitizeDuration(row.durationSeconds)

        // Step 3. Normalize.
        val normalized = normalizePhoneNumber(row.number)

        diagnostics.event(
            DiagnosticEvents.CALL_ROW_EVALUATED,
            "callLogId" to deviceCallId,
            "type" to row.type,
            "durationS" to duration,
            "num" to maskForDiagnostics(normalized),
            "fp" to fingerprintForDiagnostics(normalized)
        )

        // Step 4. Privacy filter, before anything is persisted or queued.
        val decision = privacyFilter.evaluate(normalized)
        if (CapturePipeline.decide(alreadyProcessed, decision) == CaptureAction.DISCARD_EXCLUDED) {
            // Record ONLY that this call-log id was excluded. No number, no
            // name, no call timestamp. Nothing that could later be uploaded.
            val firstTime = dao.recordExcluded(deviceCallId)
            if (firstTime) {
                diagnostics.event(
                    DiagnosticEvents.CALL_EXCLUDED_PRIVACY,
                    "callLogId" to deviceCallId, "result" to decision,
                    "fp" to fingerprintForDiagnostics(normalized)
                )
                return CaptureAction.DISCARD_EXCLUDED
            }
            diagnostics.event(
                DiagnosticEvents.CALL_DUPLICATE,
                "callLogId" to deviceCallId, "reason" to "excluded marker raced"
            )
            return CaptureAction.SKIP_DUPLICATE
        }

        diagnostics.event(
            DiagnosticEvents.CALL_ALLOWED,
            "callLogId" to deviceCallId,
            "result" to decision,
            "note" to if (decision == PrivacyDecision.ALLOW_UNRESOLVED_NUMBER) "caller id withheld" else null
        )

        // Step 5. Persist.
        val sim = simResolver.resolve(row.phoneAccountId)
        diagnostics.event(
            DiagnosticEvents.SIM_RESOLUTION,
            "callLogId" to deviceCallId,
            "result" to sim.resolution,
            "slot" to sim.slotIndex,
            "subId" to sim.subscriptionId,
            "phoneAccountId" to (row.phoneAccountId ?: "-")
        )

        val entity = CallEntity(
            deviceCallId = deviceCallId,
            phoneNumber = row.number.orEmpty(),
            normalizedNumber = normalized,
            contactName = row.cachedName ?: contactResolver.resolveDisplayName(row.number),
            direction = mapDirection(row.type),
            status = mapStatus(row.type, duration),
            startedAtEpochMs = row.dateEpochMs,
            durationSeconds = duration,
            endedAtEpochMs = endedAtEpochMs(row.dateEpochMs, duration),
            simSlot = sim.slotIndex,
            simSubscriptionId = sim.subscriptionId,
            simCarrierName = sim.carrierName,
            phoneAccountId = sim.rawPhoneAccountId
        )

        val inserted = dao.recordAccepted(entity)
        return if (inserted) {
            diagnostics.event(
                DiagnosticEvents.CALL_STORED,
                "callLogId" to deviceCallId,
                "direction" to entity.direction,
                "status" to entity.status,
                "durationS" to duration,
                "sim" to sim.resolution,
                "syncStatus" to entity.syncStatus
            )
            CaptureAction.STORE
        } else {
            diagnostics.event(
                DiagnosticEvents.CALL_DUPLICATE,
                "callLogId" to deviceCallId, "reason" to "insert raced"
            )
            CaptureAction.SKIP_DUPLICATE
        }
    }

    private companion object {
        /**
         * Process-wide, not per-instance. The service holds one engine and the
         * periodic catch-up worker builds another; both can be scanning the same
         * provider at the same time. The database constraints would still stop a
         * duplicate row, but two overlapping scans would duplicate work and make
         * the event log harder to read during an OEM test.
         */
        val SCAN_MUTEX = Mutex()
    }
}
