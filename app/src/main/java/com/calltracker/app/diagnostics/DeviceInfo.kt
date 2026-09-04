package com.calltracker.app.diagnostics

import android.Manifest
import android.app.ActivityManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import androidx.core.content.ContextCompat

/**
 * Everything the OEM matrix needs to identify the row a test result belongs to,
 * plus the two background-policy facts that explain most capture failures.
 */
data class DeviceInfo(
    val manufacturer: String,
    val brand: String,
    val model: String,
    val androidRelease: String,
    val sdkInt: Int,
    val appVersionName: String,
    val appVersionCode: Long,
    val ignoringBatteryOptimizations: Boolean,
    val backgroundRestricted: Boolean,
    val oemBackgroundNote: String?
) {
    val deviceLabel: String get() = "$manufacturer $model"
    val androidLabel: String get() = "Android $androidRelease (API $sdkInt)"
}

object DeviceInfoProvider {

    fun collect(context: Context): DeviceInfo {
        val pm = context.packageManager
        val pkg = runCatching { pm.getPackageInfo(context.packageName, 0) }.getOrNull()
        val versionCode = pkg?.let {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) it.longVersionCode
            else @Suppress("DEPRECATION") it.versionCode.toLong()
        } ?: 0L

        val power = context.getSystemService(PowerManager::class.java)
        val ignoring = runCatching {
            power?.isIgnoringBatteryOptimizations(context.packageName) == true
        }.getOrDefault(false)

        // "Background restricted" is the user-facing App battery usage ->
        // Restricted setting. When true, the OS will not let us hold a
        // foreground service; that is a legitimate OS decision, not something
        // to work around.
        val restricted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            runCatching {
                context.getSystemService(ActivityManager::class.java)?.isBackgroundRestricted == true
            }.getOrDefault(false)
        } else false

        return DeviceInfo(
            manufacturer = Build.MANUFACTURER ?: "unknown",
            brand = Build.BRAND ?: "unknown",
            model = Build.MODEL ?: "unknown",
            androidRelease = Build.VERSION.RELEASE ?: "unknown",
            sdkInt = Build.VERSION.SDK_INT,
            appVersionName = pkg?.versionName ?: "unknown",
            appVersionCode = versionCode,
            ignoringBatteryOptimizations = ignoring,
            backgroundRestricted = restricted,
            oemBackgroundNote = oemBackgroundNote()
        )
    }

    /**
     * Some OEMs require an additional, manufacturer-specific opt-in before a
     * foreground service survives the screen going off. We surface the
     * instruction and let the tester grant it by hand.
     *
     * We deliberately do NOT try to open hidden OEM settings activities or work
     * around these policies — the point of the matrix is to record how each OEM
     * behaves, not to defeat it.
     */
    private fun oemBackgroundNote(): String? {
        val m = (Build.MANUFACTURER ?: "").lowercase()
        return when {
            m.contains("xiaomi") || m.contains("redmi") || m.contains("poco") ->
                "MIUI/HyperOS: enable Autostart and set Battery saver to 'No restrictions' for this app."
            m.contains("samsung") ->
                "One UI: add the app to 'Never sleeping apps' and disable 'Put unused apps to sleep'."
            m.contains("oneplus") || m.contains("oppo") || m.contains("realme") ->
                "ColorOS/OxygenOS: allow Auto-launch and set battery usage to 'Allow background activity'."
            m.contains("vivo") || m.contains("iqoo") ->
                "Funtouch/OriginOS: allow 'High background power consumption' and Auto-start."
            m.contains("huawei") || m.contains("honor") ->
                "EMUI/MagicOS: set app launch to Manage manually with all three toggles on."
            m.contains("google") -> null // Pixel follows AOSP policy; no extra opt-in.
            else ->
                "Check this OEM for an additional autostart / background-activity opt-in."
        }
    }

    fun hasPermission(context: Context, permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    fun hasCallLogPermission(context: Context) =
        hasPermission(context, Manifest.permission.READ_CALL_LOG)

    fun hasPhoneStatePermission(context: Context) =
        hasPermission(context, Manifest.permission.READ_PHONE_STATE)

    fun hasContactsPermission(context: Context) =
        hasPermission(context, Manifest.permission.READ_CONTACTS)
}
