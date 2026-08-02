package com.ronin.phoneshm.core.device

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings

object BatteryOptimizationHelper {

    /**
     * List of OEM manufacturers known for aggressive background battery throttling / sensor sampling duty-cycles.
     * BACKLOG ITEM: Keep updated as new OEMs/models introduce custom power keepers. Can be complemented by dynamic runtime gap detection.
     */
    private val AGGRESSIVE_OEMS = listOf(
        "xiaomi", "redmi", "poco",
        "huawei", "honor",
        "oppo", "vivo", "realme", "oneplus"
    )

    /**
     * Returns true if the device manufacturer matches a known aggressive power-management OEM (e.g. Xiaomi, Huawei, Oppo, Vivo).
     */
    fun isAggressiveOemDevice(): Boolean {
        val manufacturer = (Build.MANUFACTURER ?: "").lowercase()
        val brand = (Build.BRAND ?: "").lowercase()
        return AGGRESSIVE_OEMS.any { oem ->
            manufacturer.contains(oem) || brand.contains(oem)
        }
    }

    /**
     * Checks if the app is currently ignoring standard Android battery optimizations.
     */
    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return true
        return powerManager.isIgnoringBatteryOptimizations(context.packageName)
    }

    /**
     * Determines whether a battery warning prompt should be shown before starting long (600s) ambient baseline recordings.
     */
    fun shouldShowBatteryWarning(context: Context): Boolean {
        return isAggressiveOemDevice() || !isIgnoringBatteryOptimizations(context)
    }

    /**
     * Creates an intent targeting the app's settings page to allow the user to disable battery saver restrictions and enable autostart.
     */
    fun createAppSettingsIntent(context: Context): Intent {
        return Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", context.packageName, null)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }
}
