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
import android.util.DisplayMetrics
import android.util.Log
import android.view.Surface
import android.view.WindowManager
import io.nearcast.app.network.HostServer
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToInt

/**
 * Surface-to-Surface hardware pipeline. "Codec supported" alone is insufficient:
 * encoder, Surface input, dimensions AND fps must all be supported.
 * Fall back BEFORE the one-shot MediaProjection#createVirtualDisplay call.
 */
class ScreenEncoder(
    context: Context,
    private val projection: MediaProjection,
    private val host: HostServer,
    private val options: CaptureOptions
) {
    companion object {
        private const val TAG = "NearCastEncoder"
        private const val FALLBACK_MIME = MediaFormat.MIMETYPE_VIDEO_AVC
    }

    class StartupException(stage: String, cause: Throwable) :
        IllegalStateException(stage + ": " + cause.javaClass.simpleName + ": " +
            (cause.message ?: "无异常消息"), cause)

    private data class Prepared(
        val codec: MediaCodec,
        val surface: Surface,
        val name: String,
        val width: Int,
        val height: Int,
        val fps: Int,
        val mime: String
    )

    private val running = AtomicBoolean(true)
    private val codec: MediaCodec
    private val inputSurface: Surface
    private val display: VirtualDisplay
    private val outputThread: Thread
    val fps: Int
    val width: Int
    val height: Int
    val mime: String

    init {
        val screen = try {
            val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            if (Build.VERSION.SDK_INT >= 30) {
                wm.maximumWindowMetrics.bounds.let { it.width() to it.height() }
            } else {
                val metrics = DisplayMetrics()
                @Suppress("DEPRECATION")
                wm.defaultDisplay.getRealMetrics(metrics)
                metrics.widthPixels to metrics.heightPixels
            }
        } catch (e: Exception) {
            throw StartupException("读取屏幕尺寸", e)
        }
        if (screen.first <= 0 || screen.second <= 0) {
            throw StartupException("读取屏幕尺寸", IllegalArgumentException("invalid bounds"))
        }

        // Android 14+: MediaProjection can create only one VirtualDisplay per token.
        val ready = try {
            prepareEncoder(screen.first, screen.second, options)
        } catch (e: Exception) {
            Log.e(TAG, "Encoder initialization failed", e)
            throw StartupException("初始化视频编码器", e)
        }
        codec = ready.codec
        inputSurface = ready.surface
        width = ready.width
        height = ready.height
        fps = ready.fps
        mime = ready.mime
        try {
            display = projection.createVirtualDisplay(
                "NearCast Screen", width, height, context.resources.displayMetrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, inputSurface, null, null
            ) ?: error("createVirtualDisplay returned null")
        } catch (e: Exception) {
            releaseReady(ready)
            Log.e(TAG, "VirtualDisplay initialization failed", e)
            throw StartupException("创建 VirtualDisplay", e)
        }
        // Never perform Socket writes from CaptureService.onStartCommand (main thread).
        // Sending the dimensions on the SAME worker as the AVC stream also
        // guarantees the SIZE packet arrives before the first VIDEO packet.
        Log.i(TAG, "Using " + ready.name + " (" + mime + ") " + width + "x" + height + "@" + fps)
        host.onNeedKeyframe = { requestKeyframe() }
        outputThread = Thread({ drain() }, "NearCast-encoder").also { it.start() }
    }

    private fun prepareEncoder(screenW: Int, screenH: Int, options: CaptureOptions): Prepared {
        // Try selected codec first; H.264 is the compatibility fallback.
        val mimeList = listOf(options.codec.mime, FALLBACK_MIME).distinct()
        val rates = listOf(options.fps.coerceIn(30, 120), 90, 60, 30)
            .filter { it <= options.fps.coerceIn(30, 120) }.distinct().sortedDescending()
        val edges = when (options.resolution) {
            VideoResolution.NATIVE -> listOf(4096, 2560, 1920, 1280, 960, 720)
            VideoResolution.P1080 -> listOf(1920, 1600, 1280, 960, 720)
            VideoResolution.P720 -> listOf(1280, 960, 720)
        }
        var lastFailure: Exception? = null
        var hasSurface = false
        for (mime in mimeList) {
            val codecs = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filter {
                it.isEncoder && it.supportedTypes.any { type -> type.equals(mime, true) }
            }.sortedByDescending {
                if (Build.VERSION.SDK_INT >= 29 && it.isHardwareAccelerated) 1 else 0
            }
            // Prioritize desired FPS, then scale down resolution.
            for (rate in rates) {
                for (maxEdge in edges) {
                    val scale = minOf(1.0, maxEdge.toDouble() / maxOf(screenW, screenH))
                    val idealW = (screenW * scale).roundToInt()
                    val idealH = (screenH * scale).roundToInt()
                    for (info in codecs) {
                    val caps = try { info.getCapabilitiesForType(mime) }
                    catch (e: Exception) { lastFailure = e; continue }
                    if (MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface !in caps.colorFormats) {
                        Log.w(TAG, info.name + " does not support Surface input")
                        continue
                    }
                    hasSurface = true
                    val video = caps.videoCapabilities ?: continue
                    val w = (idealW / video.widthAlignment) * video.widthAlignment
                    val h = (idealH / video.heightAlignment) * video.heightAlignment
                    if (w < 128 || h < 128 ||
                        !video.areSizeAndRateSupported(w, h, rate.toDouble())) continue

                    val format = MediaFormat.createVideoFormat(mime, w, h).apply {
                        setInteger(MediaFormat.KEY_COLOR_FORMAT,
                            MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                        setInteger(MediaFormat.KEY_BIT_RATE,
                            if (rate > 60) 14_000_000 else 7_000_000)
                        setInteger(MediaFormat.KEY_FRAME_RATE, rate)
                        setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                    }
                    var candidate: MediaCodec? = null
                    var surface: Surface? = null
                    try {
                        candidate = MediaCodec.createByCodecName(info.name)
                        candidate.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                        surface = candidate.createInputSurface()
                        candidate.start()
                        Log.i(TAG, "Selected " + info.name + " " + w + "x" + h + "@" + rate)
                        return Prepared(candidate, surface, info.name, w, h, rate, mime)
                    } catch (e: Exception) {
                        Log.w(TAG, "Rejected " + info.name + " " + w + "x" + h + "@" + rate, e)
                        lastFailure = e
                        try { surface?.release() } catch (_: Exception) {}
                        try { candidate?.release() } catch (_: Exception) {}
                    }
                    }
                }
            }
        }
        throw IllegalStateException(
            if (!hasSurface) "所选设备编码器均不支持 Surface 输入"
            else "选定视频编码及 H.264 后备配置全部初始化失败",
            lastFailure
        )
    }

    private fun releaseReady(ready: Prepared) {
        try { ready.codec.stop() } catch (_: Exception) {}
        try { ready.codec.release() } catch (_: Exception) {}
        try { ready.surface.release() } catch (_: Exception) {}
    }

    fun requestKeyframe() {
        try {
            val params = android.os.Bundle().apply {
                putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
            }
            codec.setParameters(params)
        } catch (e: Exception) {
            Log.w(TAG, "Request IDR failed", e)
        }
    }

    private fun drain() {
        // Stream initialization and encoder drain run outside Android UI thread.
        // Do this BEFORE draining the first encoded access unit.
        try {
            host.sendSize(width, height, mime, options.transport)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send stream dimensions", e)
            host.disconnectActivePeer()
            return
        }
        val info = MediaCodec.BufferInfo()
        while (running.get()) {
            try {
                val index = codec.dequeueOutputBuffer(info, 10_000)
                if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    // Some encoders expose parameter sets only in their output format.
                    val format = codec.outputFormat
                    for (i in 0..2) {
                        val csd = format.getByteBuffer("csd-$i") ?: continue
                        val copy = ByteArray(csd.remaining())
                        csd.get(copy)
                        if (copy.isNotEmpty()) host.sendFrame(MediaCodec.BUFFER_FLAG_CODEC_CONFIG, 0, copy)
                    }
                } else if (index >= 0) {
                    try {
                        val buffer = codec.getOutputBuffer(index)
                        if (buffer != null && info.size > 0) {
                            buffer.position(info.offset)
                            buffer.limit(info.offset + info.size)
                            val data = ByteArray(info.size)
                            buffer.get(data)
                            host.sendFrame(info.flags, info.presentationTimeUs, data)
                        }
                    } finally { codec.releaseOutputBuffer(index, false) }
                }
            } catch (e: Exception) {
                if (running.get()) {
                    Log.e(TAG, "Encoder drain/network failure", e)
                    host.disconnectActivePeer()
                }
                break
            }
        }
    }

    fun stop() {
        if (!running.getAndSet(false)) return
        host.onNeedKeyframe = null
        try { display.release() } catch (_: Exception) {}
        try { outputThread.join(300) } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        try { codec.stop() } catch (_: Exception) {}
        try { codec.release() } catch (_: Exception) {}
        try { inputSurface.release() } catch (_: Exception) {}
    }
}
