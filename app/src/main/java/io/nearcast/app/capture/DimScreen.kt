package io.nearcast.app.capture

import android.content.Context
import android.os.PowerManager
import android.provider.Settings

/**
 * IMPORTANT: this DOES NOT physically turn off the display.
 * Android 15 QPR1+ terminates MediaProjection when the device locks.
 * Instead, dim the real panel to its minimum and prevent auto-sleep during sharing.
 * The original global brightness/mode are persisted for crash recovery.
 */
class DimScreen(context: Context) {
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences("dim-recovery", Context.MODE_PRIVATE)
    private var wakeLock: PowerManager.WakeLock? = null
    var active: Boolean = false
        private set

    fun permitted(): Boolean = Settings.System.canWrite(app)

    fun enable(): Boolean {
        if (active) return true
        if (!permitted()) return false
        recoverIfNeeded()
        val resolver = app.contentResolver
        val mode = Settings.System.getInt(resolver, Settings.System.SCREEN_BRIGHTNESS_MODE, 0)
        val brightness = Settings.System.getInt(resolver, Settings.System.SCREEN_BRIGHTNESS, 128)
        prefs.edit().putBoolean("pending", true)
            .putInt("mode", mode).putInt("brightness", brightness).commit()
        return try {
            Settings.System.putInt(resolver, Settings.System.SCREEN_BRIGHTNESS_MODE,
                Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
            Settings.System.putInt(resolver, Settings.System.SCREEN_BRIGHTNESS, 1)
            @Suppress("DEPRECATION")
            val pm = app.getSystemService(Context.POWER_SERVICE) as PowerManager
            @Suppress("DEPRECATION")
            val lock = pm.newWakeLock(PowerManager.SCREEN_DIM_WAKE_LOCK, "NearCast:DimScreen")
            lock.acquire()
            wakeLock = lock
            active = true
            true
        } catch (_: Exception) {
            restore()
            false
        }
    }

    fun restore() {
        wakeLock?.let {
            if (it.isHeld) it.release()
        }
        wakeLock = null
        active = false
        recoverIfNeeded()
    }

    fun recoverIfNeeded() {
        if (!prefs.getBoolean("pending", false) || !permitted()) return
        val resolver = app.contentResolver
        Settings.System.putInt(resolver, Settings.System.SCREEN_BRIGHTNESS,
            prefs.getInt("brightness", 128))
        Settings.System.putInt(resolver, Settings.System.SCREEN_BRIGHTNESS_MODE,
            prefs.getInt("mode", Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC))
        prefs.edit().clear().apply()
    }
}
