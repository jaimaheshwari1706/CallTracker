package com.calltracker.app.call

import android.content.Context
import android.net.Uri
import android.provider.ContactsContract
import com.calltracker.app.diagnostics.DeviceInfoProvider

/**
 * Optional contact-name lookup.
 *
 * This is the ONLY thing READ_CONTACTS is used for, and it is genuinely
 * optional: if the permission is denied the app captures calls exactly the same
 * way, the name column is simply null.
 *
 * The resolved name is stored locally so a tester can tell at a glance which
 * captured row corresponds to which test call. It is explicitly excluded from
 * the sync payload (see CallSyncPayload) — this POC does not upload contact
 * data.
 */
class ContactResolver(private val context: Context) {

    fun resolveDisplayName(rawNumber: String?): String? {
        if (rawNumber.isNullOrBlank()) return null
        if (!DeviceInfoProvider.hasContactsPermission(context)) return null

        val uri = Uri.withAppendedPath(
            ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
            Uri.encode(rawNumber)
        )
        return runCatching {
            context.contentResolver.query(
                uri,
                arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME),
                null,
                null,
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        }.getOrNull()
    }
}
