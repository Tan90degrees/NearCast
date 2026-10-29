package io.nearcast.app.capture

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.MediaCodec
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import io.nearcast.app.MainActivity
import io.nearcast.app.NearCastRuntime

class CaptureService : Service() {
    companion object {
        private const val TAG = "NearCastCapture"
        private const val CHANNEL = "nearcast-capture"
        private const val NOTIFICATION_ID = 1301
        const val RESULT_CODE = "result_code"
        const val RESULT_DATA = "result_data"
        const val ACTION_STOP = "io.nearcast.app.STOP"

        fun stopIntent(context: Context) =
            Intent(context, CaptureService::class.java).setAction(ACTION_STOP)
    }

    private var projection: MediaProjection? = null
    private var encoder: ScreenEncoder? = null
    private var audioCapture: PlaybackAudioCapture? = null
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
        NearCastRuntime.message.value = null
        NearCastRuntime.captureDiagnostic.value = null

        var stage = "启动前台服务"
        try {
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
                startForeground(
                    NOTIFICATION_ID, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                )
            } else startForeground(NOTIFICATION_ID, notification)

            stage = "读取屏幕授权"
            val code = intent.getIntExtra(RESULT_CODE, 0)
            @Suppress("DEPRECATION")
            val data = if (Build.VERSION.SDK_INT >= 33)
                intent.getParcelableExtra(RESULT_DATA, Intent::class.java)
            else intent.getParcelableExtra(RESULT_DATA) as? Intent
            if (code != android.app.Activity.RESULT_OK || data == null) {
                throw IllegalArgumentException("屏幕共享授权数据无效，请重新授权")
            }

            val host = NearCastRuntime.host
            if (host == null || !host.connected.value) {
                throw IllegalStateException("远程控制设备已断开，请重新配对")
            }
            stage = "获取 MediaProjection"
            val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            projection = manager.getMediaProjection(code, data)
                ?: throw IllegalStateException("getMediaProjection 返回 null")
            stage = "注册 MediaProjection 回调"
            projection?.registerCallback(callback, Handler(Looper.getMainLooper()))
            stage = "创建视频编码器和 VirtualDisplay"
            val opts = NearCastRuntime.options()
            encoder = ScreenEncoder(this, projection ?: error("MediaProjection missing"), host, opts)
            NearCastRuntime.captureActive.value = true
            NearCastRuntime.audioActive.value = false
            if (opts.audioEnabled && Build.VERSION.SDK_INT >= 29) {
                // Audio failure must not stop an otherwise healthy video session.
                try {
                    audioCapture = PlaybackAudioCapture(this, projection!!, host).also { it.start() }
                    NearCastRuntime.audioActive.value = true
                } catch (audioError: Exception) {
                    Log.w(TAG, "System playback capture unavailable", audioError)
                    NearCastRuntime.message.value = "视频共享正常；音频不可用：" +
                        audioError.javaClass.simpleName + ": " +
                        (audioError.message ?: "请检查音频权限与目标应用捕获策略")
                }
            }
            Log.i(TAG, "MediaProjection started successfully")
        } catch (e: Exception) {
            // Some Android vendor codecs throw IllegalStateException with NULL message.
            // Always report the failed stage, exception type, and full Logcat stack.
            val message = e.javaClass.simpleName + ": " + (e.message ?: "无异常消息")
            val codecInfo = if (e is MediaCodec.CodecException) {
                " / " + e.diagnosticInfo
            } else ""
            val diagnostic = "阶段: " + stage +
                "\n设备: " + Build.MANUFACTURER + " " + Build.MODEL +
                "\nAndroid: " + Build.VERSION.RELEASE + " (SDK " + Build.VERSION.SDK_INT + ")" +
                "\n目标 FPS: " + NearCastRuntime.requestedFps.value +
                "\n异常: " + message + codecInfo +
                "\n\n" + Log.getStackTraceString(e)
            NearCastRuntime.message.value = "屏幕共享启动失败（" + stage + "）：" + message
            NearCastRuntime.captureDiagnostic.value = diagnostic
            Log.e(TAG, "Capture startup failed at " + stage, e)
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "屏幕共享", NotificationManager.IMPORTANCE_LOW)
        )
    }

    override fun onDestroy() {
        NearCastRuntime.dimScreen?.restore()
        NearCastRuntime.dimActive.value = false
        NearCastRuntime.captureActive.value = false
        NearCastRuntime.audioActive.value = false
        audioCapture?.stop()
        audioCapture = null
        encoder?.stop()
        encoder = null
        NearCastRuntime.host?.clearStream()
        try { projection?.unregisterCallback(callback) } catch (_: Exception) {}
        try { projection?.stop() } catch (_: Exception) {}
        projection = null
        super.onDestroy()
    }
}
