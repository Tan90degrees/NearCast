package io.nearcast.app

import android.content.Context
import io.nearcast.app.capture.DimScreen
import io.nearcast.app.capture.CaptureOptions
import io.nearcast.app.capture.CodecProbe
import android.util.Log
import io.nearcast.app.capture.VideoResolution
import io.nearcast.app.capture.VideoCodec
import io.nearcast.app.capture.VideoTransport
import io.nearcast.app.network.HostServer
import io.nearcast.app.network.NearbyDiscovery
import io.nearcast.app.network.WifiDirectController
import io.nearcast.app.network.WifiLatencyGuard
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
    @Volatile var wifiDirect: WifiDirectController? = null
        private set
    @Volatile var dimScreen: DimScreen? = null
        private set
    @Volatile var wifiLatencyGuard: WifiLatencyGuard? = null
        private set
    val client = RemoteClient()
    val captureActive = MutableStateFlow(false)
    val dimActive = MutableStateFlow(false)
    val requestedFps = MutableStateFlow(60)
    val resolution = MutableStateFlow(VideoResolution.P720)
    val codec = MutableStateFlow(VideoCodec.H264)
    val audioEnabled = MutableStateFlow(false)
    val transport = MutableStateFlow(VideoTransport.TCP)
    val audioActive = MutableStateFlow(false)
    val codecResults = MutableStateFlow<List<CodecProbe.Item>>(emptyList())
    val codecScanState = MutableStateFlow("扫描中")
    private val probeBusy = java.util.concurrent.atomic.AtomicBoolean(false)
    fun options() = CaptureOptions(codec.value, resolution.value, requestedFps.value, audioEnabled.value, transport.value)
    val message = MutableStateFlow<String?>(null)
    val captureDiagnostic = MutableStateFlow<String?>(null)

    @Synchronized fun initialize(context: Context) {
        if (host != null) return
        val app = context.applicationContext
        dimScreen = DimScreen(app).also { it.recoverIfNeeded() }
        val server = HostServer(app)
        host = server
        discovery = NearbyDiscovery(app).also { it.start(server.port) }
        wifiDirect = WifiDirectController(app)
        wifiLatencyGuard = WifiLatencyGuard(app)
        Thread({
            try {
                val results = CodecProbe.scan()
                codecResults.value = results
                codecScanState.value = "已读取系统声明的编解码能力"
            } catch (e: Exception) {
                Log.e("NearCastProbe", "Codec scan failed", e)
                codecScanState.value = "检测失败：" + (e.message ?: e.javaClass.simpleName)
            }
        }, "NearCast-codec-scan").start()
    }

    fun runCodecStartupTest() {
        if (captureActive.value) {
            codecScanState.value = "共享中不能进行编码器初始化测试，请停止共享后再检测"
            return
        }
        if (!probeBusy.compareAndSet(false, true)) return
        Thread({
            try {
                codecScanState.value = "正在验证编解码器能否初始化…"
                val tested = CodecProbe.activeTest(CodecProbe.scan(), requestedFps.value)
                codecResults.value = tested
                codecScanState.value = "初始化验证完成（不代表真实帧率和时延）"
            } catch (e: Exception) {
                Log.e("NearCastProbe", "Active codec test failed", e)
                codecScanState.value = "验证失败：" + (e.message ?: e.javaClass.simpleName)
            } finally { probeBusy.set(false) }
        }, "NearCast-codec-test").start()
    }
}
