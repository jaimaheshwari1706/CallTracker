package com.calltracker.app.call

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.SubscriptionManager
import androidx.core.content.ContextCompat

/**
 * What we know about which SIM a call used. Every field is nullable and every
 * field is either read directly from the platform or left null.
 *
 * [rawPhoneAccountId] is the unmodified CallLog.Calls.PHONE_ACCOUNT_ID string.
 * It is kept even when resolution fails so a tester can look at the diagnostics
 * screen and see what the device actually wrote — that raw value is the data
 * the OEM matrix needs.
 */
data class SimInfo(
    val slotIndex: Int? = null,
    val subscriptionId: Int? = null,
    val carrierName: String? = null,
    val rawPhoneAccountId: String? = null,
    val resolution: SimResolution = SimResolution.UNRESOLVED
)

enum class SimResolution {
    /** Matched PHONE_ACCOUNT_ID to an active subscription. */
    RESOLVED,
    /** Device wrote a PHONE_ACCOUNT_ID we could not match to a subscription. */
    UNRESOLVED,
    /** No PHONE_ACCOUNT_ID in the call-log row at all. */
    NO_PHONE_ACCOUNT,
    /** Only one active SIM, so the slot is unambiguous without matching. */
    SINGLE_SIM,
    /** READ_PHONE_STATE not granted, or the platform threw. */
    UNAVAILABLE
}

/**
 * Dual-SIM resolution is deliberately isolated in this one class.
 *
 * The honest state of the world: CallLog.Calls.PHONE_ACCOUNT_ID is not
 * standardised. On stock Android it is usually the subscription id as a string.
 * On several OEM builds it is the ICCID, a PhoneAccountHandle id, or empty.
 * There is no API that guarantees a mapping.
 *
 * So this class attempts the two mappings that are actually documented/observable
 * and returns null slot information otherwise. It never guesses "slot 0".
 * The OEM test matrix has a "SIM identified?" column precisely because this is
 * an open question — do not build logic on top of it until that column is full.
 */
class SimResolver(private val context: Context) {

    fun resolve(rawPhoneAccountId: String?): SimInfo {
        val hasPermission = ContextCompat.checkSelfPermission(
            context, Manifest.permission.READ_PHONE_STATE
        ) == PackageManager.PERMISSION_GRANTED

        if (!hasPermission) {
            return SimInfo(
                rawPhoneAccountId = rawPhoneAccountId,
                resolution = SimResolution.UNAVAILABLE
            )
        }

        val subscriptions = runCatching {
            val sm = context.getSystemService(SubscriptionManager::class.java)
            @Suppress("MissingPermission")
            sm?.activeSubscriptionInfoList.orEmpty()
        }.getOrElse {
            return SimInfo(
                rawPhoneAccountId = rawPhoneAccountId,
                resolution = SimResolution.UNAVAILABLE
            )
        }

        if (subscriptions.isEmpty()) {
            return SimInfo(
                rawPhoneAccountId = rawPhoneAccountId,
                resolution = SimResolution.UNAVAILABLE
            )
        }

        if (rawPhoneAccountId.isNullOrBlank()) {
            // With exactly one active SIM there is nothing ambiguous to resolve.
            // With two, we genuinely do not know — say so.
            return if (subscriptions.size == 1) {
                val only = subscriptions.first()
                SimInfo(
                    slotIndex = only.simSlotIndex,
                    subscriptionId = only.subscriptionId,
                    carrierName = only.carrierName?.toString(),
                    rawPhoneAccountId = rawPhoneAccountId,
                    resolution = SimResolution.SINGLE_SIM
                )
            } else {
                SimInfo(
                    rawPhoneAccountId = rawPhoneAccountId,
                    resolution = SimResolution.NO_PHONE_ACCOUNT
                )
            }
        }

        // Mapping 1: PHONE_ACCOUNT_ID == subscription id (stock Android behaviour).
        subscriptions.firstOrNull { it.subscriptionId.toString() == rawPhoneAccountId }
            ?.let { return resolved(it, rawPhoneAccountId) }

        // Mapping 2: PHONE_ACCOUNT_ID == ICCID (seen on several OEM builds).
        // getIccId() returns "" without the privileged READ_PRIVILEGED_PHONE_STATE
        // on newer releases, in which case this simply does not match.
        subscriptions.firstOrNull { sub ->
            val iccId = runCatching { sub.iccId }.getOrNull()
            !iccId.isNullOrBlank() && iccId == rawPhoneAccountId
        }?.let { return resolved(it, rawPhoneAccountId) }

        // Deliberately no third "just pick slot 0" fallback.
        return SimInfo(
            rawPhoneAccountId = rawPhoneAccountId,
            resolution = SimResolution.UNRESOLVED
        )
    }

    private fun resolved(
        sub: android.telephony.SubscriptionInfo,
        rawPhoneAccountId: String?
    ) = SimInfo(
        slotIndex = sub.simSlotIndex,
        subscriptionId = sub.subscriptionId,
        carrierName = sub.carrierName?.toString(),
        rawPhoneAccountId = rawPhoneAccountId,
        resolution = SimResolution.RESOLVED
    )

    /** True when the device reports more than one active SIM. Diagnostics only. */
    fun activeSimCount(): Int? = runCatching {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return@runCatching null
        val sm = context.getSystemService(SubscriptionManager::class.java)
        @Suppress("MissingPermission")
        sm?.activeSubscriptionInfoCount
    }.getOrNull()
}
