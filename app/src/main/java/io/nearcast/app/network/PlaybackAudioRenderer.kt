package io.nearcast.app.network

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.util.Log
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean

/** Playback on a dedicated thread so AudioTrack blocking writes never stall video TCP input. */
class PlaybackAudioRenderer {
    private val running = AtomicBoolean(true)
    private val queue = ArrayBlockingQueue<ByteArray>(12)
    private val track: AudioTrack
    private val thread: Thread

    init {
        val rate = 48_000
        val min = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT)
        require(min > 0) { "AudioTrack not supported" }
        track = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            .setAudioFormat(AudioFormat.Builder()
                .setSampleRate(rate)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(maxOf(min, rate * 2 / 10))
            .build()
        check(track.state == AudioTrack.STATE_INITIALIZED) { "AudioTrack init failed" }
        track.play()
        thread = Thread({
            while (running.get()) {
                try {
                    val chunk = queue.poll(100, java.util.concurrent.TimeUnit.MILLISECONDS) ?: continue
                    track.write(chunk, 0, chunk.size, AudioTrack.WRITE_BLOCKING)
                } catch (e: Exception) {
                    if (running.get()) Log.w("NearCastAudio", "AudioTrack write failed", e)
                    break
                }
            }
        }, "NearCast-audio-playback").also { it.start() }
    }

    fun offer(bytes: ByteArray) {
        if (!running.get()) return
        if (!queue.offer(bytes)) {
            queue.poll()
            queue.offer(bytes)
        }
    }

    fun close() {
        if (!running.getAndSet(false)) return
        queue.clear()
        try { track.pause() } catch (_: Exception) {}
        try { track.flush() } catch (_: Exception) {}
        if (thread !== Thread.currentThread()) {
            try { thread.join(300) } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
        }
        try { track.release() } catch (_: Exception) {}
    }
}
