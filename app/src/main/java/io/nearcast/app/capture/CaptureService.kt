package io.nearcast.app.capture

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import io.nearcast.app.MainActivity
import io.nearcast.app.NearCastRuntime

class CaptureService : Service() {
    companion object {
        private const val CHANNEL = "nearcast-capture"
        private const val NOTIFICATION_ID = 1301
        const val RESULT_CODE = "result_code"
        const val RESULT_DATA = "result_data"
        const val ACTION_STOP = "io.nearcast.app.STOP"
        fun stopIntent(context: Context) = Intent(context, CaptureService::class.java).setAction(ACTION_STOP)
    }

    private var projection: MediaProjection? = null
    private var encoder: ScreenEncoder? = null
    private val callback = object : MediaProjection.Callback() {
        override fun onStop() { stopSelf() }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null || intent.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (projection != null) return START_NOT_STICKY
        createChannel()
        val pending = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_share)
            .setContentTitle("NearCast 正在共享屏幕")
            .setContentText("点击查看会话，随时停止共享")
            .setContentIntent(pending)
            .setOngoing(true).build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else startForeground(NOTIFICATION_ID, notification)

        val code = intent.getIntExtra(RESULT_CODE, 0)
        @Suppress("DEPRECATION")
        val data = if (Build.VERSION.SDK_INT >= 33)
            intent.getParcelableExtra(RESULT_DATA, Intent::class.java)
        else intent.getParcelableExtra(RESULT_DATA) as? Intent
        val host = NearCastRuntime.host
        if (data == null || code == 0 || host == null || !host.connected.value) {
            stopSelf()
            return START_NOT_STICKY
        }
        try {
            val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            projection = manager.getMediaProjection(code, data)
            projection?.registerCallback(callback, Handler(Looper.getMainLooper()))
            encoder = ScreenEncoder(this, projection ?: error("No projection"), host,
                NearCastRuntime.requestedFps.value)
            NearCastRuntime.captureActive.value = true
        } catch (e: Exception) {
            NearCastRuntime.message.value = "屏幕共享启动失败：" + (e.message ?: "未知错误")
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "屏幕共享", NotificationManager.IMPORTANCE_LOW))
    }

    override fun onDestroy() {
        NearCastRuntime.dimScreen?.restore()
        NearCastRuntime.dimActive.value = false
        NearCastRuntime.captureActive.value = false
        encoder?.stop()
        encoder = null
        projection?.unregisterCallback(callback)
        projection?.stop()
        projection = null
        super.onDestroy()
    }
}
