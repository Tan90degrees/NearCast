package io.nearcast.app

import android.content.Context
import io.nearcast.app.capture.DimScreen
import io.nearcast.app.capture.CaptureOptions
import io.nearcast.app.capture.VideoResolution
import io.nearcast.app.capture.VideoCodec
import io.nearcast.app.network.HostServer
import io.nearcast.app.network.NearbyDiscovery
import io.nearcast.app.network.RemoteClient
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * App-process session owner. CaptureService keeps the process alive during a share.
 * Durable session restoration and process-independent discovery are future work.
 */
object NearCastRuntime {
    @Volatile var host: HostServer? = null
        private set
    @Volatile var discovery: NearbyDiscovery? = null
        private set
    @Volatile var dimScreen: DimScreen? = null
        private set
    val client = RemoteClient()
    val captureActive = MutableStateFlow(false)
    val dimActive = MutableStateFlow(false)
    val requestedFps = MutableStateFlow(60)
    val resolution = MutableStateFlow(VideoResolution.P720)
    val codec = MutableStateFlow(VideoCodec.H264)
    val audioEnabled = MutableStateFlow(false)
    val audioActive = MutableStateFlow(false)
    fun options() = CaptureOptions(codec.value, resolution.value, requestedFps.value, audioEnabled.value)
    val message = MutableStateFlow<String?>(null)
    val captureDiagnostic = MutableStateFlow<String?>(null)

    @Synchronized fun initialize(context: Context) {
        if (host != null) return
        val app = context.applicationContext
        dimScreen = DimScreen(app).also { it.recoverIfNeeded() }
        val server = HostServer(app)
        host = server
        discovery = NearbyDiscovery(app).also { it.start(server.port) }
    }
}
