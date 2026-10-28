package io.nearcast.app.capture

import android.content.Context
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.projection.MediaProjection
import android.os.Build
import android.view.Surface
import android.view.WindowManager
import io.nearcast.app.network.HostServer
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToInt

/**
 * MediaProjection -> VirtualDisplay -> MediaCodec input Surface.
 * CPU never copies raw pixel buffers.
 *
 * This prototype uses TCP for reliable framing. The planned FastPath should
 * replace that video transport with encrypted RTP/UDP and loss recovery.
 */
class ScreenEncoder(
    context: Context,
    private val projection: MediaProjection,
    private val host: HostServer,
    requestedFps: Int
) {
    private val running = AtomicBoolean(true)
    private val codec: MediaCodec
    private val inputSurface: Surface
    private val display: VirtualDisplay
    private val outputThread: Thread
    val fps: Int
    val width: Int
    val height: Int

    init {
        val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val bounds = if (Build.VERSION.SDK_INT >= 30) {
            windowManager.maximumWindowMetrics.bounds
        } else {
            @Suppress("DEPRECATION")
            val metrics = android.util.DisplayMetrics()
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getRealMetrics(metrics)
            android.graphics.Rect(0, 0, metrics.widthPixels, metrics.heightPixels)
        }
        val scale = minOf(1f, 1600f / maxOf(bounds.width(), bounds.height()))
        width = ((bounds.width() * scale).roundToInt() / 2 * 2).coerceAtLeast(128)
        height = ((bounds.height() * scale).roundToInt() / 2 * 2).coerceAtLeast(128)

        val preferred = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height)
        val entries = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filter {
            it.isEncoder &&
                it.supportedTypes.any { type -> type.equals(MediaFormat.MIMETYPE_VIDEO_AVC, true) }
        }
        val chosen = entries.sortedByDescending { if (Build.VERSION.SDK_INT >= 29 && it.isHardwareAccelerated) 1 else 0 }
            .firstOrNull { info ->
                try {
                    info.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC)
                        .videoCapabilities.areSizeAndRateSupported(width, height, requestedFps.toDouble())
                } catch (_: Exception) { false }
            }
        val rate = if (chosen == null) 30 else requestedFps
        fps = rate
        codec = if (chosen != null) MediaCodec.createByCodecName(chosen.name)
                else MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, if (rate > 60) 16_000_000 else 8_000_000)
            setInteger(MediaFormat.KEY_FRAME_RATE, rate)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            if (Build.VERSION.SDK_INT >= 29) {
                setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
                setInteger(MediaFormat.KEY_OPERATING_RATE, rate)
            }
        }
        try {
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            inputSurface = codec.createInputSurface()
            codec.start()
        } catch (e: Exception) {
            try { codec.release() } catch (_: Exception) {}
            throw e
        }
        try {
            display = projection.createVirtualDisplay(
                "NearCast Screen", width, height, context.resources.displayMetrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                inputSurface, null, null
            )
        } catch (e: Exception) {
            try { codec.stop() } catch (_: Exception) {}
            codec.release()
            inputSurface.release()
            throw e
        }
        host.sendSize(width, height)
        outputThread = Thread({ drain() }, "NearCast-encoder").also { it.start() }
    }

    private fun drain() {
        val info = MediaCodec.BufferInfo()
        while (running.get()) {
            try {
                val index = codec.dequeueOutputBuffer(info, 10000)
                if (index >= 0) {
                    val buffer = codec.getOutputBuffer(index)
                    if (buffer != null && info.size > 0) {
                        buffer.position(info.offset)
                        buffer.limit(info.offset + info.size)
                        val bytes = ByteArray(info.size)
                        buffer.get(bytes)
                        host.sendFrame(info.flags, info.presentationTimeUs, bytes)
                    }
                    codec.releaseOutputBuffer(index, false)
                }
            } catch (_: Exception) {
                break
            }
        }
    }

    fun stop() {
        if (!running.getAndSet(false)) return
        try { display.release() } catch (_: Exception) {}
        try { outputThread.join(300) } catch (_: InterruptedException) {}
        try { codec.stop() } catch (_: Exception) {}
        try { codec.release() } catch (_: Exception) {}
        inputSurface.release()
    }
}
