package com.calltracker.app.call

/**
 * Phone-number normalization for the POC.
 *
 * Deliberately dependency-free and deliberately naive: it exists so that the
 * SAME number written three different ways by three different OEM dialers
 * de-dupes and privacy-matches consistently. It is NOT a substitute for
 * libphonenumber, which should replace this before any real deployment —
 * see README "Known limitations".
 *
 * Because it has no Android dependencies it is fully unit-testable on the JVM,
 * which is the point.
 */

/** Default country used when the call log gives us a bare national number. */
const val DEFAULT_COUNTRY_CODE = "+91"

/**
 * Sentinel for a call the provider gave us no usable number for: withheld
 * caller ID, payphone, or a blank NUMBER column. Stored as-is so the POC can
 * still prove the row was captured, but it can never match an excluded entry.
 */
const val UNKNOWN_NUMBER = "UNKNOWN"

/**
 * CallLog.Calls.NUMBER uses these sentinels when PRESENTATION is not ALLOWED.
 * They are not phone numbers and must not be normalized into one.
 */
private val PRESENTATION_SENTINELS = setOf("-1", "-2", "-3")

/** Anything shorter than this cannot carry a country code (121, 1800, *123#). */
private const val MIN_SUBSCRIBER_DIGITS = 7

fun normalizePhoneNumber(raw: String?, defaultCountryCode: String = DEFAULT_COUNTRY_CODE): String {
    val trimmed = raw?.trim().orEmpty()
    if (trimmed.isEmpty()) return UNKNOWN_NUMBER
    if (trimmed in PRESENTATION_SENTINELS) return UNKNOWN_NUMBER
    if (trimmed.equals("unknown", ignoreCase = true)) return UNKNOWN_NUMBER
    if (trimmed.equals("private", ignoreCase = true)) return UNKNOWN_NUMBER
    if (trimmed.equals("restricted", ignoreCase = true)) return UNKNOWN_NUMBER

    val hasExplicitPlus = trimmed.startsWith("+")
    val digits = trimmed.filter { it.isDigit() }
    if (digits.isEmpty()) return UNKNOWN_NUMBER

    if (hasExplicitPlus) return "+$digits"

    // 00 is the international access prefix in most of the world.
    if (digits.startsWith("00") && digits.length > MIN_SUBSCRIBER_DIGITS + 2) {
        return "+" + digits.drop(2)
    }

    // Strip a national trunk prefix (a leading 0) before deciding what we have.
    val national = digits.trimStart('0')
    if (national.isEmpty()) return UNKNOWN_NUMBER

    return when {
        // Short codes / service numbers: leave alone, they are not dialable
        // internationally and prefixing a country code would corrupt them.
        national.length < MIN_SUBSCRIBER_DIGITS -> national
        national.length == 10 -> "$defaultCountryCode$national"
        national.length > 10 -> "+$national"
        // 7-9 digits with no prefix: genuinely ambiguous. Leave it verbatim
        // rather than guessing a country code.
        else -> national
    }
}

/**
 * The trailing digits used for loose matching between two renderings of the
 * same number (e.g. "+919876543210" vs "09876543210"). Returns null for
 * anything too short to compare safely.
 */
fun lastSignificantDigits(normalized: String, count: Int = 10): String? {
    if (normalized == UNKNOWN_NUMBER) return null
    val digits = normalized.filter { it.isDigit() }
    return if (digits.length >= count) digits.takeLast(count) else null
}

/**
 * Renders a number for the on-device diagnostic log. Diagnostic events are
 * exportable, so they must never carry a full number.
 */
fun maskForDiagnostics(normalized: String): String {
    if (normalized == UNKNOWN_NUMBER) return UNKNOWN_NUMBER
    val digits = normalized.filter { it.isDigit() }
    if (digits.length <= 4) return "*".repeat(digits.length)
    return "*".repeat(digits.length - 4) + digits.takeLast(4)
}
