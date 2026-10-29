package io.nearcast.app.capture

import android.media.MediaFormat

/** Negotiated video codec. A selected codec may fail on a given device. */
enum class VideoCodec(val label: String, val mime: String) {
    H264("H.264", MediaFormat.MIMETYPE_VIDEO_AVC),
    H265("H.265", MediaFormat.MIMETYPE_VIDEO_HEVC),
    AV1("AV1", "video/av01");
    companion object {
        fun fromMime(mime: String): VideoCodec? = entries.firstOrNull { it.mime == mime }
    }
}

/** Maximum encoder output edge; preserves source aspect ratio. */
enum class VideoResolution(val label: String, val maxEdge: Int) {
    P720("720p", 1280),
    P1080("1080p", 1920),
    NATIVE("原生", 4096)
}

enum class VideoTransport(val label: String) {
    TCP("TCP 稳定"), UDP("UDP 低延迟")
}

data class CaptureOptions(
    val codec: VideoCodec = VideoCodec.H264,
    val resolution: VideoResolution = VideoResolution.P720,
    val fps: Int = 60,
    val audioEnabled: Boolean = false,
    val transport: VideoTransport = VideoTransport.TCP
)
