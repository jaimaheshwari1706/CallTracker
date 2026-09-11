package com.calltracker.app.call

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The privacy filter is the one piece of this POC where a bug has a real-world
 * cost: a false ALLOW means a personal call gets persisted and queued for
 * upload. These tests pin the behaviour that matters.
 */
class PrivacyRulesTest {

    private val personal = normalizePhoneNumber("+919876543210")
    private val business = normalizePhoneNumber("+919111222333")

    @Test
    fun `a number not on the list is allowed`() {
        assertEquals(
            PrivacyDecision.ALLOW,
            PrivacyRules.evaluate(business, setOf(personal))
        )
    }

    @Test
    fun `an excluded number is rejected`() {
        assertEquals(
            PrivacyDecision.EXCLUDED,
            PrivacyRules.evaluate(personal, setOf(personal))
        )
    }

    @Test
    fun `an empty exclusion list allows everything`() {
        assertEquals(PrivacyDecision.ALLOW, PrivacyRules.evaluate(business, emptySet()))
    }

    @Test
    fun `exclusion matches across number formats`() {
        // The personal number was saved as a bare national number but the call
        // log wrote it in international form. It must still be excluded.
        val savedAsNational = setOf(normalizePhoneNumber("9876543210"))
        assertEquals(
            PrivacyDecision.EXCLUDED,
            PrivacyRules.evaluate(normalizePhoneNumber("+919876543210"), savedAsNational)
        )
        assertEquals(
            PrivacyDecision.EXCLUDED,
            PrivacyRules.evaluate(normalizePhoneNumber("09876543210"), savedAsNational)
        )
    }

    @Test
    fun `a different country code with the same trailing digits is not confused`() {
        // Different subscriber numbers must not collide just because they share
        // a suffix shorter than the comparison window.
        val excluded = setOf(normalizePhoneNumber("+919876543210"))
        assertEquals(
            PrivacyDecision.ALLOW,
            PrivacyRules.evaluate(normalizePhoneNumber("+919876543211"), excluded)
        )
    }

    @Test
    fun `a withheld caller id is flagged rather than silently allowed`() {
        val decision = PrivacyRules.evaluate(UNKNOWN_NUMBER, setOf(personal))
        assertEquals(PrivacyDecision.ALLOW_UNRESOLVED_NUMBER, decision)
        // It is still captured - the POC is testing capture - but the caller can
        // see it was never actually checked against the exclusion list.
        assertTrue(decision.isSyncable)
    }

    @Test
    fun `only EXCLUDED is non-syncable`() {
        assertTrue(PrivacyDecision.ALLOW.isSyncable)
        assertTrue(PrivacyDecision.ALLOW_UNRESOLVED_NUMBER.isSyncable)
        assertFalse(PrivacyDecision.EXCLUDED.isSyncable)
    }

    @Test
    fun `short codes are matched exactly and not by suffix`() {
        val excluded = setOf(normalizePhoneNumber("121"))
        assertEquals(PrivacyDecision.EXCLUDED, PrivacyRules.evaluate("121", excluded))
        assertEquals(PrivacyDecision.ALLOW, PrivacyRules.evaluate("1210", excluded))
    }

    // ------------------------------------------------------------------
    // Input formats a real dialer/carrier writes into CallLog.Calls.NUMBER.
    // The invariant: an excluded personal number never passes, whatever the
    // rendering. Each raw string goes through the same normalizePhoneNumber()
    // the engine uses, then the same PrivacyRules.evaluate().
    // ------------------------------------------------------------------

    private val excludedSavedAsTyped = setOf(normalizePhoneNumber("98765 43210"))

    private fun decisionFor(rawFromCallLog: String) =
        PrivacyRules.evaluate(normalizePhoneNumber(rawFromCallLog), excludedSavedAsTyped)

    @Test
    fun `format - exact excluded number`() {
        assertEquals(PrivacyDecision.EXCLUDED, decisionFor("9876543210"))
    }

    @Test
    fun `format - international +91 rendering`() {
        assertEquals(PrivacyDecision.EXCLUDED, decisionFor("+919876543210"))
    }

    @Test
    fun `format - local Indian trunk-prefixed rendering`() {
        assertEquals(PrivacyDecision.EXCLUDED, decisionFor("09876543210"))
    }

    @Test
    fun `format - country code without plus`() {
        assertEquals(PrivacyDecision.EXCLUDED, decisionFor("919876543210"))
    }

    @Test
    fun `format - spaces`() {
        assertEquals(PrivacyDecision.EXCLUDED, decisionFor("+91 98765 43210"))
    }

    @Test
    fun `format - hyphens`() {
        assertEquals(PrivacyDecision.EXCLUDED, decisionFor("+91-98765-43210"))
    }

    @Test
    fun `format - parentheses`() {
        assertEquals(PrivacyDecision.EXCLUDED, decisionFor("(+91) 98765-43210"))
        assertEquals(PrivacyDecision.EXCLUDED, decisionFor("(0) 98765 43210"))
    }

    @Test
    fun `format - leading and trailing whitespace`() {
        assertEquals(PrivacyDecision.EXCLUDED, decisionFor("   +919876543210   "))
        assertEquals(PrivacyDecision.EXCLUDED, decisionFor("\t9876543210\n"))
    }

    @Test
    fun `format - a different number in the same formats is still allowed`() {
        assertEquals(PrivacyDecision.ALLOW, decisionFor("+91 98765 43211"))
        assertEquals(PrivacyDecision.ALLOW, decisionFor("(0) 98765-43211"))
    }

    @Test
    fun `format - withheld and unknown renderings are flagged, never matched`() {
        for (raw in listOf("", "   ", "-1", "-2", "-3", "Unknown", "PRIVATE")) {
            assertEquals(
                "raw='" + raw + "'",
                PrivacyDecision.ALLOW_UNRESOLVED_NUMBER,
                decisionFor(raw)
            )
        }
    }

    @Test
    fun `the exclusion list itself is normalized on read so any saved format matches`() {
        // What PrivacyFilter does before calling PrivacyRules: normalize every
        // saved entry. A list saved in three formats collapses to one value.
        val saved = listOf("+91 98765 43210", "09876543210", "9876543210")
        val normalizedList = saved.map { normalizePhoneNumber(it) }.toSet()
        assertEquals(1, normalizedList.size)
        assertEquals(PrivacyDecision.EXCLUDED, PrivacyRules.evaluate(normalizePhoneNumber("919876543210"), normalizedList))
    }
}
