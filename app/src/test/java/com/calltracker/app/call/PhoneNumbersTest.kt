package com.calltracker.app.call

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Normalization is what makes de-duplication and privacy matching work at all:
 * if the same number normalizes two different ways, an excluded personal number
 * leaks through. These cases are the formats real dialers and carriers actually
 * write into CallLog.Calls.NUMBER.
 */
class PhoneNumbersTest {

    @Test
    fun `keeps an already international number`() {
        assertEquals("+919876543210", normalizePhoneNumber("+919876543210"))
    }

    @Test
    fun `strips formatting characters`() {
        assertEquals("+919876543210", normalizePhoneNumber("+91 98765-43210"))
        assertEquals("+919876543210", normalizePhoneNumber("(+91) 98765 43210"))
    }

    @Test
    fun `adds the default country code to a bare national number`() {
        assertEquals("+919876543210", normalizePhoneNumber("9876543210"))
    }

    @Test
    fun `strips a national trunk prefix before adding the country code`() {
        assertEquals("+919876543210", normalizePhoneNumber("09876543210"))
    }

    @Test
    fun `treats a 00 prefix as an international prefix`() {
        assertEquals("+919876543210", normalizePhoneNumber("00919876543210"))
    }

    @Test
    fun `keeps an existing country code without doubling it`() {
        assertEquals("+919876543210", normalizePhoneNumber("919876543210"))
    }

    @Test
    fun `all four common renderings collapse to the same value`() {
        val variants = listOf("+919876543210", "919876543210", "09876543210", "9876543210")
        val normalized = variants.map { normalizePhoneNumber(it) }.toSet()
        assertEquals(setOf("+919876543210"), normalized)
    }

    @Test
    fun `respects a non-default country code`() {
        assertEquals("+15551234567", normalizePhoneNumber("+1 555 123 4567"))
    }

    @Test
    fun `short codes are left alone rather than given a country code`() {
        assertEquals("121", normalizePhoneNumber("121"))
        assertEquals("1800", normalizePhoneNumber("1800"))
    }

    @Test
    fun `withheld and blank caller ids become the unknown sentinel`() {
        assertEquals(UNKNOWN_NUMBER, normalizePhoneNumber(null))
        assertEquals(UNKNOWN_NUMBER, normalizePhoneNumber(""))
        assertEquals(UNKNOWN_NUMBER, normalizePhoneNumber("   "))
        // CallLog presentation sentinels.
        assertEquals(UNKNOWN_NUMBER, normalizePhoneNumber("-1"))
        assertEquals(UNKNOWN_NUMBER, normalizePhoneNumber("-2"))
        assertEquals(UNKNOWN_NUMBER, normalizePhoneNumber("-3"))
        assertEquals(UNKNOWN_NUMBER, normalizePhoneNumber("Unknown"))
        assertEquals(UNKNOWN_NUMBER, normalizePhoneNumber("private"))
    }

    @Test
    fun `sip style addresses with no digits are unknown`() {
        assertEquals(UNKNOWN_NUMBER, normalizePhoneNumber("voicemail"))
    }

    @Test
    fun `last significant digits are comparable across formats`() {
        assertEquals(
            lastSignificantDigits(normalizePhoneNumber("09876543210")),
            lastSignificantDigits(normalizePhoneNumber("+919876543210"))
        )
    }

    @Test
    fun `last significant digits are null for values too short to compare`() {
        assertNull(lastSignificantDigits(normalizePhoneNumber("121")))
        assertNull(lastSignificantDigits(UNKNOWN_NUMBER))
    }

    @Test
    fun `diagnostic masking never exposes the full number`() {
        val masked = maskForDiagnostics("+919876543210")
        assertEquals("********3210", masked)
        assertEquals(UNKNOWN_NUMBER, maskForDiagnostics(UNKNOWN_NUMBER))
    }
}
