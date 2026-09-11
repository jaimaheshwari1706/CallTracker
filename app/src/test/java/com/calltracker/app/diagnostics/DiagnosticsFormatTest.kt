package com.calltracker.app.diagnostics

import com.calltracker.app.call.UNKNOWN_NUMBER
import com.calltracker.app.call.fingerprintForDiagnostics
import com.calltracker.app.call.maskForDiagnostics
import com.calltracker.app.call.normalizePhoneNumber
import com.calltracker.app.database.DiagnosticEventEntity
import com.calltracker.app.ui.DiagnosticsRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The event log is the thing a tester reads to fill in the OEM matrix, and it
 * is exportable. Two properties matter: lines split back into their fields,
 * and a phone number can never be recovered from one.
 */
class DiagnosticsFormatTest {

    @Test
    fun `fields render as key=value pairs and nulls are dropped`() {
        val line = DiagnosticsStore.formatFields(
            "callLogId" to 1234L,
            "result" to "ALLOW",
            "note" to null,
            "durationS" to 41
        )
        assertEquals("callLogId=1234 result=ALLOW durationS=41", line)
    }

    @Test
    fun `values with whitespace are collapsed so a line always splits on spaces`() {
        val line = DiagnosticsStore.formatFields("message" to "no  network\navailable ")
        assertEquals("message=no_network_available", line)
        assertEquals(1, line.split(" ").size)
    }

    @Test
    fun `mask exposes only the last four digits`() {
        val masked = maskForDiagnostics(normalizePhoneNumber("+91 98765 43210"))
        assertEquals("********3210", masked)
        assertFalse(masked.contains("98765"))
    }

    @Test
    fun `fingerprint is stable, short, and not the number`() {
        val n = normalizePhoneNumber("+919876543210")
        val fp1 = fingerprintForDiagnostics(n)
        val fp2 = fingerprintForDiagnostics(normalizePhoneNumber("09876543210"))
        assertEquals(8, fp1.length)
        assertEquals(fp1, fp2) // same number, two renderings, one fingerprint
        assertTrue(fp1.all { it in "0123456789abcdef" })
        assertFalse(n.contains(fp1))
        assertFalse(fp1.contains("3210"))
    }

    @Test
    fun `different numbers get different fingerprints`() {
        assertNotEquals(
            fingerprintForDiagnostics("+919876543210"),
            fingerprintForDiagnostics("+919876543211")
        )
    }

    @Test
    fun `unknown number is never fingerprinted into something that looks real`() {
        assertEquals(UNKNOWN_NUMBER, fingerprintForDiagnostics(UNKNOWN_NUMBER))
        assertEquals(UNKNOWN_NUMBER, maskForDiagnostics(UNKNOWN_NUMBER))
    }

    // --- last-call trace derivation ---

    private fun ev(id: Long, type: String, detail: String) =
        DiagnosticEventEntity(id = id, atEpochMs = id * 1000, type = type, detail = detail)

    @Test
    fun `last call trace picks the newest evaluated row and returns its events oldest first`() {
        // Stored newest-first, as the DAO returns them.
        val newestFirst = listOf(
            ev(9, DiagnosticEvents.SYNC_STARTED, "batch=1 callLogIds=56"),
            ev(8, DiagnosticEvents.CALL_STORED, "callLogId=56 direction=INCOMING status=MISSED"),
            ev(7, DiagnosticEvents.CALL_ALLOWED, "callLogId=56 result=ALLOW"),
            ev(6, DiagnosticEvents.CALL_ROW_EVALUATED, "callLogId=56 type=3 durationS=0"),
            ev(5, DiagnosticEvents.CALL_LOG_CHANGE_DETECTED, "selfChange=false"),
            ev(4, DiagnosticEvents.CALL_STORED, "callLogId=55 direction=OUTGOING status=ANSWERED"),
            ev(3, DiagnosticEvents.CALL_ROW_EVALUATED, "callLogId=55 type=2 durationS=41")
        )
        val id = DiagnosticsRepository.lastEvaluatedCallLogId(newestFirst)
        assertEquals("56", id)

        val trace = DiagnosticsRepository.traceFor("56", newestFirst)
        assertEquals(listOf(6L, 7L, 8L), trace.map { it.id })
        // "callLogIds=56" (plural, the sync batch) is a different key and must not match.
        assertFalse(trace.any { it.type == DiagnosticEvents.SYNC_STARTED })
    }

    @Test
    fun `no evaluated row means no trace`() {
        assertNull(
            DiagnosticsRepository.lastEvaluatedCallLogId(
                listOf(ev(1, DiagnosticEvents.SERVICE_STARTED, "observer=true"))
            )
        )
    }
}
