package io.nearcast.app.capture

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import android.util.Log

/**
 * Inspect local codec declarations, with an optional explicit startup test.
 * A successful start without feeding frames does not prove sustained throughput.
 * Active tests MUST NOT run during an ongoing screen capture.
 */
object CodecProbe {
    data class Item(
        val codec: VideoCodec,
        val encoder: String?,
        val decoder: String?,
        val hardwareEncoder: Boolean,
        val hardwareDecoder: Boolean,
        val surfaceEncoder: Boolean,
        val encoding120: Boolean,
        val decoding120: Boolean,
        val encoding60: Boolean = false,
        val decoding60: Boolean = false,
        val encoderCandidates: String = "",
        val decoderCandidates: String = "",
        val encodeStartup: Boolean? = null,
        val decodeStartup: Boolean? = null,
        val details: String = ""
    ) {
        // Availability != suitability for high-FPS screen streaming.
        val available: Boolean get() = encoder != null || decoder != null
        val lowLatencyCandidate: Boolean get() = encoder != null &&
            decoder != null && surfaceEncoder && hardwareEncoder && hardwareDecoder
    }

    private fun hw(info: MediaCodecInfo): Boolean =
        if (Build.VERSION.SDK_INT >= 29)
            !info.isSoftwareOnly && (info.isHardwareAccelerated || info.isVendor)
        else !info.name.startsWith("OMX.google.", true) &&
            !info.name.startsWith("c2.android.", true)

    private fun candidates(mime: String, encode: Boolean): List<MediaCodecInfo> =
        MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
            .filter { it.isEncoder == encode &&
                it.supportedTypes.any { t -> t.equals(mime, true) } }
            .sortedByDescending { if (hw(it)) 1 else 0 }

    private fun supported(info: MediaCodecInfo, mime: String, rate: Int): Boolean =
        try {
            info.getCapabilitiesForType(mime).videoCapabilities
                .areSizeAndRateSupported(720, 1280, rate.toDouble())
        } catch (_: Exception) { false }

    private fun surface(info: MediaCodecInfo, mime: String): Boolean =
        try {
            MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface in
                info.getCapabilitiesForType(mime).colorFormats
        } catch (_: Exception) { false }

    fun scan(): List<Item> = VideoCodec.entries.map { format ->
        val encoders = candidates(format.mime, true)
        val decoders = candidates(format.mime, false)
        // Do NOT hide a codec just because it is software-only or the vendor
        // omitted the HW flag. Advertise it, mark HW/SW separately.
        val enc = encoders.firstOrNull { surface(it, format.mime) }
            ?: encoders.firstOrNull()
        val dec = decoders.firstOrNull()
        Item(
            format, enc?.name, dec?.name,
            enc?.let(::hw) ?: false, dec?.let(::hw) ?: false,
            enc?.let { surface(it, format.mime) } ?: false,
            enc?.let { supported(it, format.mime, 120) } ?: false,
            dec?.let { supported(it, format.mime, 120) } ?: false,
            enc?.let { supported(it, format.mime, 60) } ?: false,
            dec?.let { supported(it, format.mime, 60) } ?: false,
            encoders.joinToString { it.name },
            decoders.joinToString { it.name }
        )
    }

    fun activeTest(items: List<Item>, fps: Int): List<Item> = items.map { item ->
        val enc = item.encoder?.let { startup(it, item.codec.mime, true, fps) }
        val dec = item.decoder?.let { startup(it, item.codec.mime, false, fps) }
        item.copy(encodeStartup = enc?.first, decodeStartup = dec?.first,
            details = listOfNotNull(enc?.second, dec?.second)
                .filter { it.isNotEmpty() }.joinToString(" · "))
    }

    private fun startup(name: String, mime: String, encode: Boolean, fps: Int): Pair<Boolean, String> {
        var codec: MediaCodec? = null
        var surface: android.view.Surface? = null
        var started = false
        return try {
            val format = MediaFormat.createVideoFormat(mime, 720, 1280)
            if (encode) {
                format.setInteger(MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                format.setInteger(MediaFormat.KEY_FRAME_RATE, fps)
                format.setInteger(MediaFormat.KEY_BIT_RATE, 8_000_000)
                format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                if (Build.VERSION.SDK_INT >= 29)
                    format.setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
            } else if (Build.VERSION.SDK_INT >= 30) {
                format.setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
            }
            codec = MediaCodec.createByCodecName(name)
            codec.configure(format, null, null,
                if (encode) MediaCodec.CONFIGURE_FLAG_ENCODE else 0)
            if (encode) surface = codec.createInputSurface()
            codec.start()
            started = true
            true to ""
        } catch (e: Exception) {
            Log.w("NearCastCodecProbe", "Startup failed: " + name, e)
            false to (name + ": " + e.javaClass.simpleName + " (" +
                (e.message ?: "无详细消息") + ")")
        } finally {
            if (started) try { codec?.stop() } catch (_: Exception) {}
            try { codec?.release() } catch (_: Exception) {}
            try { surface?.release() } catch (_: Exception) {}
        }
    }

    fun decoderMask(items: List<Item>): Int = items.fold(0) { mask, item ->
        // Advertise actual decoding availability, including software AV1,
        // but let sender's encoder policy avoid software encoders.
        if (item.decoder != null) mask or (1 shl item.codec.ordinal) else mask
    }

    fun supports(mask: Int, codec: VideoCodec): Boolean =
        mask and (1 shl codec.ordinal) != 0
}
