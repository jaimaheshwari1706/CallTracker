package com.calltracker.app.call

import com.calltracker.app.database.ExcludedNumberDao

/**
 * The outcome of a privacy check. Modelled as an enum rather than a Boolean so
 * the diagnostic log can say WHY a call was dropped without logging the number.
 */
enum class PrivacyDecision {
    /** Number is not on the exclusion list. Persist and queue for sync. */
    ALLOW,

    /**
     * The caller ID was withheld/blank, so the number cannot be checked against
     * the exclusion list at all. The POC still stores it, because proving the
     * row was captured is the experiment. Flagged separately so the OEM matrix
     * can count how often this happens. See README "Known limitations".
     */
    ALLOW_UNRESOLVED_NUMBER,

    /** On the exclusion list. Never persisted, never queued, never uploaded. */
    EXCLUDED;

    val isSyncable: Boolean get() = this != EXCLUDED
}

/**
 * Pure decision logic, with no Room and no Android dependency, so it can be
 * unit-tested directly. [PrivacyFilter] is the thin I/O wrapper around it.
 */
object PrivacyRules {

    /**
     * @param normalizedNumber output of [normalizePhoneNumber]
     * @param excludedNormalized the exclusion list, already normalized on write
     */
    fun evaluate(normalizedNumber: String, excludedNormalized: Set<String>): PrivacyDecision {
        if (normalizedNumber == UNKNOWN_NUMBER) return PrivacyDecision.ALLOW_UNRESOLVED_NUMBER
        if (excludedNormalized.isEmpty()) return PrivacyDecision.ALLOW
        if (normalizedNumber in excludedNormalized) return PrivacyDecision.EXCLUDED

        // Loose match on the trailing subscriber digits so that one exclusion
        // entry covers "+919876543210", "09876543210" and "9876543210". Without
        // this, a personal number saved in one format leaks through in another.
        val tail = lastSignificantDigits(normalizedNumber) ?: return PrivacyDecision.ALLOW
        val tailMatches = excludedNormalized.any { lastSignificantDigits(it) == tail }
        return if (tailMatches) PrivacyDecision.EXCLUDED else PrivacyDecision.ALLOW
    }
}

/**
 * Filtering happens here, on-device, BEFORE a call is ever written to the
 * pending-sync queue — not server-side after upload. An excluded number never
 * gets constructed into a sync payload in the first place; the only trace it
 * leaves is a processed-marker row holding the call-log id and the word
 * EXCLUDED_PRIVACY (no number, no name, no timestamp of the call itself).
 */
class PrivacyFilter(private val excludedNumberDao: ExcludedNumberDao) {

    suspend fun evaluate(normalizedNumber: String): PrivacyDecision {
        // Read per-scan rather than caching: the exclusion list is tiny, and a
        // stale cache here means a personal call gets uploaded. Correctness
        // beats the saved query.
        val excluded = excludedNumberDao.getAllExcluded()
            .map { normalizePhoneNumber(it) }
            .toSet()
        return PrivacyRules.evaluate(normalizedNumber, excluded)
    }

    suspend fun isSyncable(normalizedNumber: String): Boolean =
        evaluate(normalizedNumber).isSyncable
}
