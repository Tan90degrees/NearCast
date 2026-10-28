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
import android.os.SystemClock
import kotlin.math.abs
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
    private val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
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
        try {
            val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            @Suppress("DEPRECATION")
            val physical = wm.defaultDisplay.refreshRate
            host.physicalDisplayHz.value = physical
            host.virtualDisplayHz.value = display.display?.refreshRate ?: 0f
            Log.i(TAG, "physicalHz=" + physical + " virtualHz=" + host.virtualDisplayHz.value)
        } catch (e: Exception) {
            Log.w(TAG, "Cannot read display refresh rate", e)
        }
        Log.i(TAG, "Using " + ready.name + " (" + mime + ") " + width + "x" + height + "@" + fps)
        host.onNeedKeyframe = { requestKeyframe() }
        outputThread = Thread({ drain() }, "NearCast-encoder").also { it.start() }
    }

    private fun prepareEncoder(screenW: Int, screenH: Int, options: CaptureOptions): Prepared {
        // The other phone advertises its decoder capabilities at pairing.
        // A phone's AV1 decoding support is not proof it can encode AV1 with
        // low latency. Prefer hardware AV1; otherwise fall back to HEVC.
        val desired = if (options.codec == VideoCodec.AV1)
            listOf(VideoCodec.AV1, VideoCodec.H265, VideoCodec.H264)
        else if (options.codec == VideoCodec.H265)
            listOf(VideoCodec.H265, VideoCodec.H264)
        else listOf(VideoCodec.H264)
        val mimeList = desired
            .filter { io.nearcast.app.capture.CodecProbe.supports(host.peerDecoderMask.value, it) }
            .map { it.mime }
        if (mimeList.isEmpty()) {
            throw IllegalStateException("接收手机未声明可用视频解码器，请两端安装相同新版")
        }
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
                it.isEncoder && it.supportedTypes.any { type -> type.equals(mime, true) } &&
                    (mime != "video/av01" ||
                        (Build.VERSION.SDK_INT >= 29 &&
                            it.isHardwareAccelerated && !it.isSoftwareOnly))
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

                    // Optional encoder latency hints can be ignored/rejected
                    // by OEMs. Retry without them if configuration fails.
                    for (lowLatency in listOf(true, false)) {
                        val format = MediaFormat.createVideoFormat(mime, w, h).apply {
                            setInteger(MediaFormat.KEY_COLOR_FORMAT,
                                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                            setInteger(MediaFormat.KEY_BIT_RATE,
                                if (rate > 60) 14_000_000 else 7_000_000)
                            setInteger(MediaFormat.KEY_FRAME_RATE, rate)
                            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                            if (lowLatency) {
                                setInteger(MediaFormat.KEY_LATENCY, 1)
                                if (Build.VERSION.SDK_INT >= 29)
                                    setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
                                setInteger(MediaFormat.KEY_OPERATING_RATE, rate)
                            }
                        }
                        var candidate: MediaCodec? = null
                        var surface: Surface? = null
                        try {
                            candidate = MediaCodec.createByCodecName(info.name)
                            candidate.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                            surface = candidate.createInputSurface()
                            candidate.start()
                            host.selectedEncoder.value = info.name + " / " + mime +
                                (if (lowLatency) " / 低延迟提示" else " / 标准配置")
                            Log.i(TAG, "Selected " + info.name + " " + w + "x" + h +
                                "@" + rate + " lowLatency=" + lowLatency)
                            return Prepared(candidate, surface, info.name, w, h, rate, mime)
                        } catch (e: Exception) {
                            Log.w(TAG, "Rejected " + info.name + " " + w + "x" + h +
                                "@" + rate + " latencyHint=" + lowLatency, e)
                            lastFailure = e
                            try { surface?.release() } catch (_: Exception) {}
                            try { candidate?.release() } catch (_: Exception) {}
                        }
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
        var statsMs = SystemClock.elapsedRealtime()
        var frameCount = 0
        var bytesCount = 0L
        var prevPts = -1L
        var prevGap = 0L
        var totalGap = 0L
        var totalDeviation = 0L
        var ptsSamples = 0
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
                            if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                                frameCount++
                                bytesCount += info.size.toLong()
                                val pts = info.presentationTimeUs
                                if (prevPts >= 0 && pts > prevPts) {
                                    val gap = pts - prevPts
                                    if (gap in 1L..500_000L) {
                                        totalGap += gap
                                        if (prevGap > 0) totalDeviation += abs(gap - prevGap)
                                        ptsSamples++
                                        prevGap = gap
                                    }
                                }
                                prevPts = pts
                            }
                        }
                    } finally { codec.releaseOutputBuffer(index, false) }
                }
                val now = SystemClock.elapsedRealtime()
                if (now - statsMs >= 1000L) {
                    val ms = (now - statsMs).coerceAtLeast(1L)
                    host.encodedFps.value = (frameCount * 1000L / ms).toInt()
                    host.encodedMbps.value = bytesCount * 8.0 / ms / 1000.0
                    host.frameIntervalMs.value =
                        if (ptsSamples > 0) totalGap.toDouble() / ptsSamples / 1000.0 else 0.0
                    host.frameCadenceJitterMs.value =
                        if (ptsSamples > 1) totalDeviation.toDouble() / (ptsSamples - 1) / 1000.0
                        else 0.0
                    try {
                        @Suppress("DEPRECATION")
                        val physicalHz = wm.defaultDisplay.refreshRate
                        host.physicalDisplayHz.value = physicalHz
                        host.virtualDisplayHz.value = display.display?.refreshRate ?: 0f
                    } catch (_: Exception) {}
                    totalGap = 0L
                    totalDeviation = 0L
                    ptsSamples = 0
                    Log.d(TAG, "encoder actual fps=" + host.encodedFps.value +
                        " bitrate=" + host.encodedMbps.value + " Mbps; target=" + fps)
                    frameCount = 0
                    bytesCount = 0L
                    statsMs = now
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
