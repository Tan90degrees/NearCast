package io.nearcast.app

import android.app.Activity
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import io.nearcast.app.capture.CaptureService
import io.nearcast.app.capture.CodecProbe
import io.nearcast.app.capture.VideoCodec
import io.nearcast.app.capture.VideoResolution
import io.nearcast.app.capture.VideoTransport
import io.nearcast.app.network.NearbyPeer
import io.nearcast.app.network.HostServer
import io.nearcast.app.network.Wire
import kotlin.math.hypot

private val Aqua = Color(0xFF86F1D5)
private val Dark = Color(0xFF101717)
private val Panel = Color(0xFF1C2929)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        NearCastRuntime.initialize(applicationContext)
        setContent {
            val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
                if (result.resultCode == Activity.RESULT_OK && result.data != null) {
                    val service = Intent(this, CaptureService::class.java)
                        .putExtra(CaptureService.RESULT_CODE, result.resultCode)
                        .putExtra(CaptureService.RESULT_DATA, result.data)
                    ContextCompat.startForegroundService(this, service)
                } else NearCastRuntime.message.value = "未授权屏幕共享"
            }
            val audioPermissionLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.RequestPermission()
            ) { granted ->
                if (granted) {
                    val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                    launcher.launch(manager.createScreenCaptureIntent())
                } else NearCastRuntime.message.value = "未授予系统声音采集权限，关闭音频开关后仍可共享视频"
            }
            val p2pPermissionLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.RequestPermission()
            ) { granted ->
                if (granted) NearCastRuntime.wifiDirect?.start()
                else NearCastRuntime.message.value = "未授予 Wi-Fi Direct 附近设备权限"
            }
            MaterialTheme(colorScheme = darkColorScheme(
                primary = Aqua, background = Dark, surface = Panel,
                onSurface = Color(0xFFF0F8F7), onBackground = Color(0xFFF0F8F7)
            )) {
                NearCastHome(
                    requestShare = {
                        if (NearCastRuntime.host?.connected?.value == true) {
                            if (NearCastRuntime.audioEnabled.value && Build.VERSION.SDK_INT >= 29 &&
                                ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                                    != PackageManager.PERMISSION_GRANTED) {
                                audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                            } else {
                                val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                                launcher.launch(manager.createScreenCaptureIntent())
                            }
                        }
                    },
                    openAccessibility = {
                        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                    },
                    enableDirect = {
                        val permission = if (Build.VERSION.SDK_INT >= 33)
                            Manifest.permission.NEARBY_WIFI_DEVICES
                        else Manifest.permission.ACCESS_FINE_LOCATION
                        if (ContextCompat.checkSelfPermission(this, permission) ==
                            PackageManager.PERMISSION_GRANTED) {
                            NearCastRuntime.wifiDirect?.start()
                        } else p2pPermissionLauncher.launch(permission)
                    },
                    openWriteSettings = {
                        startActivity(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS,
                            Uri.parse("package:" + packageName)))
                    },
                    stopShare = { startService(CaptureService.stopIntent(this)) }
                )
            }
        }
    }
}

@Composable
private fun StatusPill(label: String) {
    Surface(color = Color(0xFF244B42), shape = RoundedCornerShape(50)) {
        Text(label, modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            color = Aqua, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
internal fun RemoteViewer(onClose: () -> Unit) {
    val client = NearCastRuntime.client
    val size by client.videoSize.collectAsState()
    val status by client.status.collectAsState()
    val codec by client.codecName.collectAsState()
    val received by client.decodedFrames.collectAsState()
    val fps by client.receivedFps.collectAsState()
    val outputFps by client.renderedFps.collectAsState()
    val rxMbps by client.receiveMbps.collectAsState()
    val decodeDepth by client.decodeQueueDepth.collectAsState()
    val decodeOverflows by client.videoQueueOverflows.collectAsState()
    val resync by client.recoveryRequests.collectAsState()
    val controlRtt by client.controlRttMs.collectAsState()
    val controlJitter by client.controlJitterMs.collectAsState()
    val context = LocalContext.current
    val activity = context as? Activity
    val receiverHz = activity?.display?.refreshRate ?: 0f
    var immersive by remember { mutableStateOf(false) }
    var showQuality by remember { mutableStateOf(false) }
    var showDiagnostics by remember { mutableStateOf(false) }
    val bitrate by client.bitrateLimitMbps.collectAsState()
    var bitrateSlider by remember(bitrate) { mutableFloatStateOf(bitrate.toFloat()) }

    DisposableEffect(immersive) {
        val window = activity?.window
        val previousModeId = window?.attributes?.preferredDisplayModeId ?: 0
        if (window != null) {
            // Prefer the fastest available same-resolution display mode for the
            // receiving viewer. This is a hint; Android can override it.
            val display = activity.display
            if (display != null) {
                val currentMode = display.mode
                val fastest = display.supportedModes
                    .filter { it.physicalWidth == currentMode.physicalWidth &&
                        it.physicalHeight == currentMode.physicalHeight }
                    .maxByOrNull { it.refreshRate }
                if (fastest != null && fastest.refreshRate > currentMode.refreshRate) {
                    window.attributes = window.attributes.apply {
                        preferredDisplayModeId = fastest.modeId
                    }
                }
            }
            // Hide bars AND allow the SurfaceView to occupy their former space.
            WindowCompat.setDecorFitsSystemWindows(window, !immersive)
            val controller = WindowCompat.getInsetsController(window, window.decorView)
            if (immersive) {
                controller.systemBarsBehavior =
                    WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                controller.hide(WindowInsetsCompat.Type.systemBars())
            } else {
                controller.show(WindowInsetsCompat.Type.systemBars())
            }
        }
        onDispose {
            window?.let {
                it.attributes = it.attributes.apply { preferredDisplayModeId = previousModeId }
                WindowCompat.setDecorFitsSystemWindows(it, true)
                WindowCompat.getInsetsController(it, it.decorView)
                    .show(WindowInsetsCompat.Type.systemBars())
            }
        }
    }

    Box(
        Modifier.fillMaxSize().background(Dark)
            .then(if (immersive) Modifier else Modifier.statusBarsPadding().navigationBarsPadding())
    ) {
        Column(Modifier.fillMaxSize()) {
            if (!immersive) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("远程屏幕 · $codec", fontWeight = FontWeight.Bold)
                        Text(outputFps.toString() + " FPS 输出 · " +
                            "%.1f".format(rxMbps) + " Mbps 接收",
                            style = MaterialTheme.typography.bodySmall, color = Color.LightGray)
                    }
                    TextButton(onClick = { showQuality = true }) { Text("码率") }
                    TextButton(onClick = { showDiagnostics = true }) { Text("统计") }
                    TextButton(onClick = { immersive = true }) { Text("全屏") }
                    TextButton(onClick = onClose) { Text("断开") }
                }
            }
            BoxWithConstraints(
                Modifier.fillMaxWidth().weight(1f),
                contentAlignment = Alignment.Center
            ) {
                val ratio = size.first.toFloat() / size.second.coerceAtLeast(1)
                val available = constraints.maxWidth.toFloat() / constraints.maxHeight.coerceAtLeast(1)
                val videoModifier =
                    if (ratio >= available) Modifier.fillMaxWidth().aspectRatio(ratio)
                    else Modifier.fillMaxHeight().aspectRatio(ratio)
                Box(videoModifier.background(Color.Black)) {
                    AndroidView(
                        modifier = Modifier.fillMaxSize(),
                        factory = { c ->
                            SurfaceView(c).apply {
                                holder.addCallback(object : SurfaceHolder.Callback {
                                    override fun surfaceCreated(holder: SurfaceHolder) {
                                        client.attachSurface(holder.surface)
                                    }
                                    override fun surfaceChanged(holder: SurfaceHolder, format: Int,
                                                                width: Int, height: Int) = Unit
                                    override fun surfaceDestroyed(holder: SurfaceHolder) {
                                        client.attachSurface(null)
                                    }
                                })
                            }
                        }
                    )
                    Box(Modifier.matchParentSize().pointerInput(client) {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            var end = down.position
                            val started = down.uptimeMillis
                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                end = change.position
                                if (!change.pressed) break
                            }
                            val duration = (android.os.SystemClock.uptimeMillis() - started).toInt()
                            fun nx(x: Float) = (x / this.size.width.coerceAtLeast(1)).coerceIn(0f, 1f)
                            fun ny(y: Float) = (y / this.size.height.coerceAtLeast(1)).coerceIn(0f, 1f)
                            if (hypot(end.x - down.position.x, end.y - down.position.y) < 16f) {
                                client.input(Wire.TAP, nx(end.x), ny(end.y))
                            } else {
                                client.input(Wire.SWIPE, nx(down.position.x), ny(down.position.y),
                                    nx(end.x), ny(end.y), duration)
                            }
                        }
                    })
                }
            }
            if (!immersive) {
                Row(Modifier.fillMaxWidth().padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly) {
                    OutlinedButton(onClick = { client.input(Wire.BACK) }) { Text("返回") }
                    OutlinedButton(onClick = { client.input(Wire.HOME) }) { Text("主页") }
                    OutlinedButton(onClick = { client.input(Wire.RECENTS) }) { Text("最近任务") }
                }
            }
        }
        if (immersive) {
            Row(Modifier.align(Alignment.TopEnd).padding(8.dp)) {
                FilledTonalButton(onClick = { showQuality = true }) { Text("码率") }
                Spacer(Modifier.width(8.dp))
                FilledTonalButton(onClick = { immersive = false }) { Text("退出全屏") }
            }
        }
    }
    if (showQuality) {
        AlertDialog(
            onDismissRequest = { showQuality = false },
            title = { Text("视频码率上限") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(bitrateSlider.toInt().toString() + " Mbps",
                        style = MaterialTheme.typography.headlineMedium, color = Aqua)
                    Slider(value = bitrateSlider, onValueChange = { bitrateSlider = it },
                        onValueChangeFinished = {
                            client.setBitrateLimit(bitrateSlider.toInt())
                        }, valueRange = 2f..60f, steps = 57)
                    Text("客户端可实时调整编码器码率目标；实际码率随画面内容变化。" +
                        "降低码率可缓解突发发送、网络及解码积压。",
                        style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    client.setBitrateLimit(bitrateSlider.toInt())
                    showQuality = false
                }) { Text("应用") }
            },
            dismissButton = {
                TextButton(onClick = { showQuality = false }) { Text("取消") }
            }
        )
    }
    if (showDiagnostics) {
        AlertDialog(
            onDismissRequest = { showDiagnostics = false },
            title = { Text("实时传输统计") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("解码提交 " + fps + " FPS / 输出 " + outputFps + " FPS")
                    Text("接收 " + "%.1f".format(rxMbps) + " Mbps")
                    Text("RTT " + controlRtt + " ms / 抖动 " + controlJitter + " ms")
                    Text("解码排队 " + decodeDepth + " / 队列溢出 " + decodeOverflows)
                    Text("IDR 请求 " + resync + " / 已提交帧 " + received)
                    Text("接收屏幕 " + receiverHz.toInt() + " Hz")
                    Text(status, style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {
                TextButton(onClick = { showDiagnostics = false }) { Text("关闭") }
            }
        )
    }
}
