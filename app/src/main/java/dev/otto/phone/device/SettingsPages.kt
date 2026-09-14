package dev.otto.phone.device

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings

/** A Settings page by name (the names are otto's guard_rules.json
 *  `settings_pages`), opened by intent so the agent never navigates there. */
object SettingsPages {
    fun intentFor(page: String, packageName: String): Intent? {
        val action = when (page) {
            "display", "font" -> Settings.ACTION_DISPLAY_SETTINGS
            "wifi" -> Settings.ACTION_WIFI_SETTINGS
            "bluetooth" -> Settings.ACTION_BLUETOOTH_SETTINGS
            "apps" -> Settings.ACTION_APPLICATION_SETTINGS
            "sound" -> Settings.ACTION_SOUND_SETTINGS
            "battery" -> Settings.ACTION_BATTERY_SAVER_SETTINGS
            "notifications" -> Settings.ACTION_NOTIFICATION_SETTINGS
            "accessibility" -> Settings.ACTION_ACCESSIBILITY_SETTINGS
            "location" -> Settings.ACTION_LOCATION_SOURCE_SETTINGS
            "storage" -> Settings.ACTION_INTERNAL_STORAGE_SETTINGS
            "date" -> Settings.ACTION_DATE_SETTINGS
            "language" -> Settings.ACTION_LOCALE_SETTINGS
            "security" -> Settings.ACTION_SECURITY_SETTINGS
            "app_details" -> Settings.ACTION_APPLICATION_DETAILS_SETTINGS
            else -> return null
        }
        val intent = Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (page == "app_details") {
            if (packageName.isBlank()) return null
            intent.data = Uri.parse("package:$packageName")
        }
        return intent
    }

    fun open(context: Context, page: String, packageName: String): Boolean {
        val intent = intentFor(page, packageName) ?: return false
        context.startActivity(intent)
        return true
    }
}
