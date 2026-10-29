package io.nearcast.app.capture

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.os.Build
import android.util.Log
import io.nearcast.app.network.HostServer
import java.util.concurrent.atomic.AtomicBoolean

/** Captures only permitted MEDIA/GAME playback, not calls, DRM or forbidden apps. */
class PlaybackAudioCapture(
    private val context: Context,
    private val projection: MediaProjection,
    private val host: HostServer
) {
    companion object {
        const val SAMPLE_RATE = 48_000
        const val CHANNELS = 1
        const val BYTES_PER_SAMPLE = 2
        private const val TAG = "NearCastAudioCapture"
    }

    private val running = AtomicBoolean(false)
    private var record: AudioRecord? = null
    private var worker: Thread? = null

    fun start() {
        if (Build.VERSION.SDK_INT < 29) throw IllegalStateException("系统声音需要 Android 10+")
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            throw SecurityException("需要录音权限才能采集允许共享的系统媒体音频")
        }
        val config = AudioPlaybackCaptureConfiguration.Builder(projection)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .build()
        val audioFormat = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(SAMPLE_RATE)
            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
            .build()
        val minBuffer = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuffer <= 0) throw IllegalStateException("音频缓冲区不支持")
        val audioRecord = AudioRecord.Builder()
            .setAudioFormat(audioFormat)
            .setBufferSizeInBytes(maxOf(minBuffer * 2, SAMPLE_RATE / 5 * BYTES_PER_SAMPLE))
            .setAudioPlaybackCaptureConfig(config)
            .build()
        if (audioRecord.state != AudioRecord.STATE_INITIALIZED) {
            audioRecord.release()
            throw IllegalStateException("AudioRecord 初始化失败")
        }
        try {
            audioRecord.startRecording()
        } catch (e: Exception) {
            audioRecord.release()
            throw e
        }
        record = audioRecord
        running.set(true)
        worker = Thread({
            // 20ms mono PCM blocks; audio is best-effort in v0.2.
            val buffer = ByteArray(SAMPLE_RATE / 50 * BYTES_PER_SAMPLE)
            while (running.get()) {
                val n = try { audioRecord.read(buffer, 0, buffer.size) } catch (_: Exception) { break }
                if (n > 0) host.sendAudio(buffer.copyOf(n))
                else if (n < 0) Log.w(TAG, "AudioRecord read error: $n")
            }
        }, "NearCast-audio-capture").apply { start() }
    }

    fun stop() {
        if (!running.getAndSet(false)) return
        try { record?.stop() } catch (_: Exception) {}
        try { worker?.join(300) } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        try { record?.release() } catch (_: Exception) {}
        record = null
        worker = null
    }
}
