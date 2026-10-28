package io.nearcast.app.network

import android.media.MediaCodec
import android.media.MediaFormat
import android.view.Surface
import java.util.concurrent.atomic.AtomicBoolean

/** Decode straight to a Surface; no Bitmap conversion or deliberate playback buffer. */
class AvcSurfaceDecoder(surface: Surface, width: Int, height: Int) {
    private val codec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
    private val running = AtomicBoolean(true)
    private val outputThread: Thread

    init {
        try {
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height)
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                format.setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
            }
            codec.configure(format, surface, null, 0)
            codec.start()
        } catch (e: Exception) {
            codec.release()
            throw e
        }
        outputThread = Thread({
            val info = MediaCodec.BufferInfo()
            while (running.get()) {
                try {
                    val index = codec.dequeueOutputBuffer(info, 5000)
                    if (index >= 0) codec.releaseOutputBuffer(index, true)
                } catch (_: Exception) { break }
            }
        }, "NearCast-decoder").also { it.start() }
    }

    fun queue(flags: Int, ptsUs: Long, data: ByteArray) {
        if (!running.get()) return
        try {
            val index = codec.dequeueInputBuffer(1000)
            if (index >= 0) {
                val buffer = codec.getInputBuffer(index) ?: return
                if (data.size > buffer.capacity()) {
                    codec.queueInputBuffer(index, 0, 0, ptsUs, 0)
                    return
                }
                buffer.clear()
                buffer.put(data)
                codec.queueInputBuffer(index, 0, data.size, ptsUs,
                    flags and (MediaCodec.BUFFER_FLAG_CODEC_CONFIG or MediaCodec.BUFFER_FLAG_KEY_FRAME))
            }
        } catch (_: Exception) {}
    }

    fun close() {
        if (!running.getAndSet(false)) return
        // Stop the hardware codec only after the dequeue thread exits.
        if (Thread.currentThread() !== outputThread) {
            try { outputThread.join(300) } catch (_: InterruptedException) {}
        }
        try { codec.stop() } catch (_: Exception) {}
        try { codec.release() } catch (_: Exception) {}
    }
}
