package dev.omalauncher.widgets.runtime

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.content.res.Resources
import android.database.ContentObserver
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.location.LocationManager
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import dev.omalauncher.widgets.lang.Bool
import dev.omalauncher.widgets.lang.Null
import dev.omalauncher.widgets.lang.Num
import dev.omalauncher.widgets.lang.Obj
import dev.omalauncher.widgets.lang.Str
import dev.omalauncher.widgets.lang.Value
import dev.omalauncher.widgets.lang.bool
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlin.math.roundToInt

/**
 * The phone's modes, for `source m = android.modes`: Do Not Disturb, battery saver, the ringer, the torch, the radios,
 * location, rotation, dark theme and brightness; and `torch(...)` to switch the torch. None of it needs a permission:
 * the radios, rotation and brightness are read from Android's settings, which any app may read, not from their managers.
 */
class Modes(
    private val context: Context,
) {
    private val notifications = context.getSystemService(NotificationManager::class.java)
    private val power = context.getSystemService(PowerManager::class.java)
    private val audio = context.getSystemService(AudioManager::class.java)
    private val cameras = context.getSystemService(CameraManager::class.java)
    private val location = context.getSystemService(LocationManager::class.java)
    private val resolver = context.contentResolver

    /** The camera with a flash, the back one if there are several; null on phones without one. */
    private val flash: String? by lazy {
        runCatching {
            val ids = cameras.cameraIdList.filter { cameras.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true }
            ids.firstOrNull { cameras.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK } ?: ids.firstOrNull()
        }.getOrNull()
    }

    /** Whether the torch is on, as Android last said; it says so as soon as a callback is registered. */
    @Volatile private var torchOn = false

    /** Listeners waiting for torch changes; the torch callback is registered while there are any, or once torch(...) ran. */
    private val torchListeners = mutableSetOf<() -> Unit>()
    private var torchWatched = false
    private val torchCallback =
        object : CameraManager.TorchCallback() {
            override fun onTorchModeChanged(
                cameraId: String,
                enabled: Boolean,
            ) {
                if (cameraId != flash) return
                torchOn = enabled
                synchronized(torchListeners) { torchListeners.toList() }.forEach { it() }
            }
        }

    private val main = Handler(Looper.getMainLooper())

    private fun watchTorch() {
        if (torchWatched || flash == null) return
        torchWatched = true
        // Kept for the process: unregistering would lose what the torch is doing, and a callback costs nothing.
        cameras.registerTorchCallback(torchCallback, main)
    }

    fun value(): Value {
        val filter = notifications.currentInterruptionFilter
        return Obj(
            mapOf(
                // Off means every notification gets through; anything else is Do Not Disturb in some form.
                "dnd" to bool(filter != NotificationManager.INTERRUPTION_FILTER_ALL && filter != NotificationManager.INTERRUPTION_FILTER_UNKNOWN),
                "saver" to bool(power.isPowerSaveMode),
                "ringer" to
                    Str(
                        when (audio.ringerMode) {
                            AudioManager.RINGER_MODE_SILENT -> "silent"
                            AudioManager.RINGER_MODE_VIBRATE -> "vibrate"
                            else -> "normal"
                        },
                    ),
                "torch" to if (flash == null) Null else bool(torchOn),
                "airplane" to bool(global(Settings.Global.AIRPLANE_MODE_ON) == 1),
                // 2 is Wi-Fi left on in airplane mode.
                "wifi" to bool(global(Settings.Global.WIFI_ON).let { it == 1 || it == 2 }),
                "bluetooth" to bool(global(Settings.Global.BLUETOOTH_ON) == 1),
                "location" to bool(location.isLocationEnabled),
                "rotate" to bool(system(Settings.System.ACCELEROMETER_ROTATION) == 1),
                // The system's, not the launcher's own: Resources.getSystem() isn't themed by the app.
                "dark" to bool(Resources.getSystem().configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES),
                // As Android stores it, in percent of its maximum: the settings slider is curved, so the two can differ.
                "brightness" to (system(Settings.System.SCREEN_BRIGHTNESS)?.let { Num((it.coerceIn(0, maxBrightness) * 100.0 / maxBrightness).roundToInt().toDouble()) } ?: Null),
                "autoBrightness" to bool(system(Settings.System.SCREEN_BRIGHTNESS_MODE) == Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC),
            ),
        )
    }

    /** 255 on most phones; some store more steps, and say so in the framework's own config. */
    private val maxBrightness: Int by lazy {
        val res = Resources.getSystem()
        @Suppress("DiscouragedApi")
        val id = res.getIdentifier("config_screenBrightnessSettingMaximum", "integer", "android")
        (if (id != 0) runCatching { res.getInteger(id) }.getOrNull() else null)?.takeIf { it > 0 } ?: 255
    }

    private fun global(name: String) = runCatching { Settings.Global.getInt(resolver, name) }.getOrNull()

    private fun system(name: String) = runCatching { Settings.System.getInt(resolver, name) }.getOrNull()

    /** Emits whenever one of the modes changes, from anywhere: quick settings, a schedule, another app. */
    fun updates(): Flow<Value> =
        callbackFlow {
            val receiver =
                object : BroadcastReceiver() {
                    override fun onReceive(
                        c: Context,
                        intent: Intent,
                    ) {
                        trySend(Unit)
                    }
                }
            val filter =
                IntentFilter().apply {
                    addAction(NotificationManager.ACTION_INTERRUPTION_FILTER_CHANGED)
                    addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
                    addAction(AudioManager.RINGER_MODE_CHANGED_ACTION)
                    addAction(LocationManager.MODE_CHANGED_ACTION)
                    // Dark theme turning on or off changes the configuration.
                    addAction(Intent.ACTION_CONFIGURATION_CHANGED)
                }
            if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED) else context.registerReceiver(receiver, filter)
            // The radios, rotation and brightness send no broadcast an app without permissions may hear; their settings change.
            val observer =
                object : ContentObserver(main) {
                    override fun onChange(selfChange: Boolean) {
                        trySend(Unit)
                    }
                }
            for (name in listOf(Settings.Global.AIRPLANE_MODE_ON, Settings.Global.WIFI_ON, Settings.Global.BLUETOOTH_ON)) {
                resolver.registerContentObserver(Settings.Global.getUriFor(name), false, observer)
            }
            for (name in listOf(Settings.System.ACCELEROMETER_ROTATION, Settings.System.SCREEN_BRIGHTNESS, Settings.System.SCREEN_BRIGHTNESS_MODE)) {
                resolver.registerContentObserver(Settings.System.getUriFor(name), false, observer)
            }
            val onTorch: () -> Unit = { trySend(Unit) }
            synchronized(torchListeners) { torchListeners += onTorch }
            watchTorch()
            trySend(Unit)
            awaitClose {
                context.unregisterReceiver(receiver)
                resolver.unregisterContentObserver(observer)
                synchronized(torchListeners) { torchListeners -= onTorch }
            }
        }.conflate().map { value() }.distinctUntilChanged()

    /** torch(true), torch(false), or torch("on"), "off", "toggle". Does nothing on a phone without a flash. */
    fun torch(arg: Value) {
        val id = flash ?: return
        watchTorch()
        // After the callback's first call, which registering it queued on the same looper: toggle needs to know the state.
        main.post {
            val on =
                when {
                    arg is Bool -> arg.v
                    arg.toString() == "on" -> true
                    arg.toString() == "off" -> false
                    arg.toString() == "toggle" -> !torchOn
                    else -> return@post
                }
            // The camera app holding the camera refuses the torch; a widget tap mustn't crash the launcher over it.
            runCatching { cameras.setTorchMode(id, on) }.onFailure { Log.w("widgets", "torch: ${it.message}") }
        }
    }
}
