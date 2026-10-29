package io.nearcast.app.network

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import android.util.Log

/**
 * Small scoped Android Wi-Fi performance hint for active sessions.
 *
 * Android 10+ LOW_LATENCY only takes effect with the screen on, the app
 * foreground, and an AP connection. Wi-Fi Direct is NOT guaranteed to honor it.
 * This cannot prevent RF interference, codec stalls or OS power policies.
 */
class WifiLatencyGuard(context: Context) {
    private val manager = context.applicationContext
        .getSystemService(Context.WIFI_SERVICE) as? WifiManager
    private val holders = HashSet<String>()
    private val lock: WifiManager.WifiLock? = try {
        val mode = if (Build.VERSION.SDK_INT >= 29)
            WifiManager.WIFI_MODE_FULL_LOW_LATENCY
        else WifiManager.WIFI_MODE_FULL_HIGH_PERF
        manager?.createWifiLock(mode, "NearCast:active-session")?.apply {
            setReferenceCounted(false)
        }
    } catch (e: Exception) {
        Log.w("NearCastWifi", "Low latency lock not supported", e)
        null
    }

    @Synchronized fun acquire(owner: String) {
        holders.add(owner)
        try {
            if (holders.isNotEmpty() && lock?.isHeld == false) lock.acquire()
        } catch (e: Exception) {
            Log.w("NearCastWifi", "Cannot acquire Wi-Fi performance hint", e)
        }
    }

    @Synchronized fun release(owner: String) {
        holders.remove(owner)
        if (holders.isEmpty()) {
            try { if (lock?.isHeld == true) lock.release() }
            catch (e: Exception) {
                Log.w("NearCastWifi", "Cannot release Wi-Fi performance hint", e)
            }
        }
    }
}
