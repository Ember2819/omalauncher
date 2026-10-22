package dev.omalauncher.widgets.runtime

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.util.Log

/**
 * `settings("...")`: Android's own panels and settings screens, for what a launcher may show but not switch itself
 * (Wi-Fi, Bluetooth, airplane mode, Do Not Disturb). Opening them needs no permission.
 */
class SystemPanels(
    private val context: Context,
) {
    fun open(page: String) {
        // The first the phone has: a panel (a sheet over home) where Android has one, else the settings screen.
        for (action in PAGES[page] ?: return) {
            try {
                context.startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return
            } catch (e: ActivityNotFoundException) {
                continue
            } catch (e: SecurityException) {
                Log.w("widgets", "settings($page): ${e.message}")
            }
        }
    }

    private companion object {

        /** Keyed by the words in owl's SETTINGS_PAGES. The hidden actions are on every phone so far; the screen after each is where it isn't. */
        val PAGES =
            mapOf(
                "internet" to listOf(Settings.Panel.ACTION_INTERNET_CONNECTIVITY, Settings.ACTION_WIRELESS_SETTINGS),
                "wifi" to listOf(Settings.Panel.ACTION_WIFI, Settings.ACTION_WIFI_SETTINGS),
                "bluetooth" to listOf(Settings.ACTION_BLUETOOTH_SETTINGS),
                "airplane" to listOf(Settings.ACTION_AIRPLANE_MODE_SETTINGS),
                "data" to listOf(Settings.ACTION_DATA_USAGE_SETTINGS, Settings.ACTION_WIRELESS_SETTINGS),
                "location" to listOf(Settings.ACTION_LOCATION_SOURCE_SETTINGS),
                "cast" to listOf(Settings.ACTION_CAST_SETTINGS),
                "nfc" to listOf(Settings.Panel.ACTION_NFC, Settings.ACTION_NFC_SETTINGS),
                "volume" to listOf(Settings.Panel.ACTION_VOLUME, Settings.ACTION_SOUND_SETTINGS),
                "sound" to listOf(Settings.ACTION_SOUND_SETTINGS),
                "dnd" to listOf("android.settings.ZEN_MODE_SETTINGS", Settings.ACTION_SOUND_SETTINGS),
                "display" to listOf(Settings.ACTION_DISPLAY_SETTINGS),
                "dark" to listOf("android.settings.DARK_THEME_SETTINGS", Settings.ACTION_DISPLAY_SETTINGS),
                "battery" to listOf(Settings.ACTION_BATTERY_SAVER_SETTINGS),
                "notifications" to listOf("android.settings.NOTIFICATION_SETTINGS", Settings.ACTION_SETTINGS),
            )
    }
}
