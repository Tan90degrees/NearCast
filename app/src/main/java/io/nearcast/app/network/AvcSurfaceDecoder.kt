package io.nearcast.app.network

import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Build
import android.util.Log
import android.view.Surface
import java.util.concurrent.atomic.AtomicBoolean

/** Video decoder with explicitly owned input/output lifecycle and MIME negotiation. */
class AvcSurfaceDecoder(surface: Surface, width: Int, height: Int,
                        mime: String = MediaFormat.MIMETYPE_VIDEO_AVC,
                        private val lowLatencyMode: () -> Boolean = { true },
                        private val onDecodedOutput: () -> Unit = {}) {
    private val codec = MediaCodec.createDecoderByType(mime)
    private val running = AtomicBoolean(true)
    private val outputThread: Thread

    init {
        try {
            val format = MediaFormat.createVideoFormat(mime, width, height)
            if (Build.VERSION.SDK_INT >= 30) {
                try { format.setInteger(MediaFormat.KEY_LOW_LATENCY, 1) }
                catch (_: Exception) {}
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
                    if (index >= 0) {
                        var displayIndex = index
                        var displaySize = info.size
                        if (lowLatencyMode()) {
                            // Moonlight-style "latest ready frame": decoding is
                            // finished, so discarded *output* buffers do not
                            // affect inter-frame prediction, unlike dropping
                            // compressed P frames. Avoid building a long
                            // display backlog when decode is bursty.
                            var skipped = 0
                            while (skipped < 4) {
                                val newer = codec.dequeueOutputBuffer(info, 0)
                                if (newer < 0) break
                                codec.releaseOutputBuffer(displayIndex, false)
                                displayIndex = newer
                                displaySize = info.size
                                skipped++
                            }
                        }
                        codec.releaseOutputBuffer(displayIndex, true)
                        if (displaySize > 0) onDecodedOutput()
                    }
                } catch (e: Exception) {
                    if (running.get()) Log.w("NearCastDecoder", "Decode output failed", e)
                    break
                }
            }
        }, "NearCast-decoder").also { it.start() }
    }

    @Synchronized fun queue(flags: Int, ptsUs: Long, data: ByteArray): Boolean {
        if (!running.get()) return false
        return try {
            val index = codec.dequeueInputBuffer(500)
            if (index < 0) return false
            val buffer = codec.getInputBuffer(index)
            if (buffer == null || data.size > buffer.capacity()) {
                codec.queueInputBuffer(index, 0, 0, ptsUs, 0)
                return false
            }
            buffer.clear()
            buffer.put(data)
            val usefulFlags = flags and
                (MediaCodec.BUFFER_FLAG_CODEC_CONFIG or MediaCodec.BUFFER_FLAG_KEY_FRAME)
            codec.queueInputBuffer(index, 0, data.size, ptsUs, usefulFlags)
            true
        } catch (e: Exception) {
            if (running.get()) Log.w("NearCastDecoder", "Decode input failed", e)
            false
        }
    }

    @Synchronized fun close() {
        if (!running.getAndSet(false)) return
        if (Thread.currentThread() !== outputThread) {
            try { outputThread.join(300) }
            catch (_: InterruptedException) { Thread.currentThread().interrupt() }
        }
        try { codec.stop() } catch (_: Exception) {}
        try { codec.release() } catch (_: Exception) {}
    }
}
