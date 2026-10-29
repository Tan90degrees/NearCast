package io.nearcast.app.input

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import io.nearcast.app.NearCastRuntime
import io.nearcast.app.network.Wire

/**
 * Requires the device owner to enable NearCast in Accessibility settings.
 * Only an authenticated, live screen-sharing session is allowed to issue input.
 */
class RemoteInputService : AccessibilityService() {
    companion object {
        @Volatile var instance: RemoteInputService? = null
            private set
        private val main = Handler(Looper.getMainLooper())

        fun dispatch(action: Int, x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Int) {
            if (!NearCastRuntime.captureActive.value || NearCastRuntime.host?.connected?.value != true) return
            main.post {
                if (!NearCastRuntime.captureActive.value || NearCastRuntime.host?.connected?.value != true) return@post
                instance?.perform(action, x1, y1, x2, y2, durationMs)
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() = Unit
    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    private fun perform(action: Int, x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Int) {
        when (action) {
            Wire.BACK -> performGlobalAction(GLOBAL_ACTION_BACK)
            Wire.HOME -> performGlobalAction(GLOBAL_ACTION_HOME)
            Wire.RECENTS -> performGlobalAction(GLOBAL_ACTION_RECENTS)
            Wire.TAP, Wire.SWIPE -> {
                val wm = getSystemService(WINDOW_SERVICE) as WindowManager
                val metrics = DisplayMetrics()
                @Suppress("DEPRECATION")
                wm.defaultDisplay.getRealMetrics(metrics)
                fun px(x: Float) = x.coerceIn(0f, 1f) * (metrics.widthPixels - 1)
                fun py(y: Float) = y.coerceIn(0f, 1f) * (metrics.heightPixels - 1)
                val path = Path().apply {
                    moveTo(px(x1), py(y1))
                    if (action == Wire.SWIPE) lineTo(px(x2), py(y2))
                }
                val time = if (action == Wire.TAP) 75L else durationMs.coerceIn(80, 5000).toLong()
                val gesture = GestureDescription.Builder()
                    .addStroke(GestureDescription.StrokeDescription(path, 0L, time))
                    .build()
                dispatchGesture(gesture, null, null)
            }
        }
    }
}
