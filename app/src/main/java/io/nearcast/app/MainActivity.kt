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
import io.nearcast.app.capture.VideoCodec
import io.nearcast.app.capture.VideoResolution
import io.nearcast.app.capture.VideoTransport
import io.nearcast.app.network.NearbyPeer
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
            MaterialTheme(colorScheme = darkColorScheme(
                primary = Aqua, background = Dark, surface = Panel,
                onSurface = Color(0xFFF0F8F7), onBackground = Color(0xFFF0F8F7)
            )) {
                NearCastApp(
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
private fun NearCastApp(
    requestShare: () -> Unit,
    openAccessibility: () -> Unit,
    openWriteSettings: () -> Unit,
    stopShare: () -> Unit
) {
    val ctx = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val server = NearCastRuntime.host ?: return
    val discoverer = NearCastRuntime.discovery ?: return
    val client = NearCastRuntime.client
    val peers by discoverer.peers.collectAsState()
    val discoveryError by discoverer.error.collectAsState()
    val hostConnected by server.connected.collectAsState()
    val hostStatus by server.status.collectAsState()
    val clientConnected by client.connected.collectAsState()
    val clientStatus by client.status.collectAsState()
    val captureActive by NearCastRuntime.captureActive.collectAsState()
    val dimActive by NearCastRuntime.dimActive.collectAsState()
    val fps by NearCastRuntime.requestedFps.collectAsState()
    val resolution by NearCastRuntime.resolution.collectAsState()
    val codec by NearCastRuntime.codec.collectAsState()
    val audioEnabled by NearCastRuntime.audioEnabled.collectAsState()
    val transport by NearCastRuntime.transport.collectAsState()
    val audioActive by NearCastRuntime.audioActive.collectAsState()
    val dropped by server.droppedFrames.collectAsState()
    val encoderFps by server.encodedFps.collectAsState()
    val encoderMbps by server.encodedMbps.collectAsState()
    val wireMbps by server.wireMbps.collectAsState()
    val writerDepth by server.writerQueueDepth.collectAsState()
    val sourceHz by server.physicalDisplayHz.collectAsState()
    val virtualHz by server.virtualDisplayHz.collectAsState()
    val message by NearCastRuntime.message.collectAsState()
    val diagnostic by NearCastRuntime.captureDiagnostic.collectAsState()
    var selectedPeer by remember { mutableStateOf<NearbyPeer?>(null) }
    var pin by remember { mutableStateOf("") }
    var manual by remember { mutableStateOf(false) }
    var address by remember { mutableStateOf("") }
    var port by remember { mutableStateOf("9020") }

    if (clientConnected) {
        RemoteViewer(onClose = { client.close() })
        return
    }

    Column(
        modifier = Modifier.fillMaxSize().background(Dark)
            .statusBarsPadding().navigationBarsPadding()
            .verticalScroll(rememberScrollState()).padding(horizontal = 22.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("◉", color = Aqua, style = MaterialTheme.typography.headlineLarge)
            Spacer(Modifier.width(10.dp))
            Column {
                Text("NearCast", style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold)
                Text("Nearby screens, instantly.", color = Color.LightGray,
                    style = MaterialTheme.typography.bodySmall)
            }
        }
        Card(colors = CardDefaults.cardColors(containerColor = Panel),
            shape = RoundedCornerShape(22.dp), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("共享这台手机", style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.weight(1f))
                    StatusPill(if (hostConnected) "已配对" else "可被发现")
                }
                Text(hostStatus, color = Color.LightGray)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("配对验证码", style = MaterialTheme.typography.labelMedium, color = Color.LightGray)
                        Text(server.pin, style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Bold, color = Aqua)
                    }
                    Text("端口 " + server.port, style = MaterialTheme.typography.labelSmall,
                        color = Color.LightGray)
                }
                Text("让另一台手机选择本机并输入验证码。只有建立连接后才能开始共享。",
                    style = MaterialTheme.typography.bodySmall, color = Color.LightGray)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = requestShare, enabled = hostConnected && !captureActive,
                        modifier = Modifier.weight(1f)
                    ) { Text(if (captureActive) "共享中" else "开始共享") }
                    if (captureActive) OutlinedButton(onClick = stopShare) { Text("停止") }
                }
                if (hostConnected) {
                    HorizontalDivider(color = Color.DarkGray)
                    Text("远程控制权限", style = MaterialTheme.typography.labelLarge)
                    Text(
                        if (io.nearcast.app.input.RemoteInputService.instance != null)
                            "✓ 已启用无障碍控制" else "需手动开启无障碍服务才能远程点击",
                        style = MaterialTheme.typography.bodySmall, color = Color.LightGray
                    )
                    OutlinedButton(onClick = openAccessibility) { Text("设置控制权限") }
                }
            }
        }

        if (captureActive) {
            Card(colors = CardDefaults.cardColors(containerColor = Panel),
                shape = RoundedCornerShape(18.dp)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("省电暗屏", fontWeight = FontWeight.SemiBold)
                            Text("最小亮度 + 保持显示活动，并非物理关屏",
                                color = Color.LightGray, style = MaterialTheme.typography.bodySmall)
                        }
                        Switch(checked = dimActive, onCheckedChange = { enabled ->
                            val dim = NearCastRuntime.dimScreen
                            if (enabled && dim?.permitted() != true) {
                                openWriteSettings()
                            } else if (enabled) {
                                NearCastRuntime.dimActive.value = dim?.enable() == true
                            } else {
                                dim?.restore()
                                NearCastRuntime.dimActive.value = false
                            }
                        })
                    }
                    Text("直接按电源键锁屏会终止部分 Android 系统的屏幕采集。",
                        color = Color.LightGray, style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("视频设置 · 共享前可调整", style = MaterialTheme.typography.titleMedium)
            Text("网络传输", style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                VideoTransport.entries.forEach { value ->
                    FilterChip(
                        selected = transport == value, enabled = !captureActive,
                        onClick = { NearCastRuntime.transport.value = value },
                        label = { Text(value.label) }
                    )
                }
            }
            Text("UDP 低延迟为实验模式，遇到丢包会请求关键帧；" +
                "两种网络传输都尚未加密。",
                style = MaterialTheme.typography.bodySmall, color = Color.LightGray)
            Text("视频编码协议", style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                VideoCodec.entries.forEach { value ->
                    FilterChip(
                        selected = codec == value, enabled = !captureActive,
                        onClick = { NearCastRuntime.codec.value = value },
                        label = { Text(value.label) }
                    )
                }
            }
            Text("传输分辨率", style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                VideoResolution.entries.forEach { value ->
                    FilterChip(
                        selected = resolution == value, enabled = !captureActive,
                        onClick = { NearCastRuntime.resolution.value = value },
                        label = { Text(value.label) }
                    )
                }
            }
            Text("目标刷新率", style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(60, 90, 120).forEach { value ->
                    FilterChip(
                        selected = fps == value,
                        enabled = !captureActive,
                        onClick = { NearCastRuntime.requestedFps.value = value },
                        label = { Text(value.toString() + " FPS") }
                    )
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("共享系统声音", style = MaterialTheme.typography.labelLarge)
                    Text("仅 Android 10+，支持允许采集的媒体/游戏音频",
                        style = MaterialTheme.typography.bodySmall, color = Color.LightGray)
                }
                Switch(
                    checked = audioEnabled,
                    enabled = !captureActive && Build.VERSION.SDK_INT >= 29,
                    onCheckedChange = { NearCastRuntime.audioEnabled.value = it }
                )
            }
            if (captureActive) Text(
                "当前音频：" + if (audioActive) "传输中" else "未开启或不可捕获",
                color = Aqua, style = MaterialTheme.typography.bodySmall
            )
            Text("屏幕 " + sourceHz.toInt() + "Hz / 虚拟屏 " + virtualHz.toInt() +
                "Hz · 实际编码 " + encoderFps + " FPS / " +
                "%.1f".format(encoderMbps) + " Mbps",
                style = MaterialTheme.typography.bodySmall, color = Aqua)
            Text("网络写出 " + "%.1f".format(wireMbps) + " Mbps · 发送排队 " +
                writerDepth + " 帧 · GOP 重同步 " + dropped,
                style = MaterialTheme.typography.bodySmall, color = Color.LightGray)
            Text("实际媒体模式：" +
                transport.label + "。H.265/AV1 不可用时自动回退 H.264。",
                style = MaterialTheme.typography.bodySmall, color = Color.LightGray)
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("附近设备", style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold)
                Text("自动扫描同一局域网", style = MaterialTheme.typography.bodySmall,
                    color = Color.LightGray)
            }
            TextButton(onClick = { manual = true }) { Text("手动连接") }
        }
        if (discoveryError != null) Text(discoveryError ?: "", color = MaterialTheme.colorScheme.error)
        if (message != null) {
            Text(message ?: "", color = MaterialTheme.colorScheme.error)
            if (diagnostic != null) {
                OutlinedButton(onClick = {
                    clipboard.setText(AnnotatedString(diagnostic ?: ""))
                }) { Text("复制故障诊断信息") }
            }
        }
        if (clientStatus != "未连接") Text(clientStatus, color = Aqua,
            style = MaterialTheme.typography.bodySmall)

        if (peers.isEmpty()) {
            Box(Modifier.fillMaxWidth().height(152.dp), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("⌁", style = MaterialTheme.typography.displayLarge, color = Aqua)
                    Text("正在寻找附近设备…", color = Color.LightGray)
                    Text("两台手机需要处于可互访的同一 Wi-Fi",
                        color = Color.Gray, style = MaterialTheme.typography.bodySmall)
                }
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                peers.forEach { peer ->
                    Card(onClick = { selectedPeer = peer; pin = "" },
                        colors = CardDefaults.cardColors(containerColor = Panel),
                        shape = RoundedCornerShape(16.dp),
                        border = BorderStroke(1.dp, Color(0xFF304343))) {
                        Row(Modifier.fillMaxWidth().padding(18.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Text("▣", color = Aqua, style = MaterialTheme.typography.headlineMedium)
                            Spacer(Modifier.width(14.dp))
                            Column(Modifier.weight(1f)) {
                                Text(peer.name, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    fontWeight = FontWeight.SemiBold)
                                Text(peer.address, color = Color.LightGray,
                                    style = MaterialTheme.typography.bodySmall)
                            }
                            Text("连接", color = Aqua)
                        }
                    }
                }
            }
        }
        Text("局域网测试版 · 无云端服务 · 暂未实现加密传输",
            modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
            style = MaterialTheme.typography.labelSmall, color = Color.Gray)
    }

    if (manual) {
        AlertDialog(
            onDismissRequest = { manual = false },
            title = { Text("手动连接") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(value = address, onValueChange = { address = it },
                        label = { Text("设备 IP") }, singleLine = true)
                    OutlinedTextField(value = port, onValueChange = { port = it.filter(Char::isDigit) },
                        label = { Text("连接端口") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val n = port.toIntOrNull()
                    if (address.isNotBlank() && n != null && n in 1..65535) {
                        selectedPeer = NearbyPeer(address, address, n)
                        pin = ""
                        manual = false
                    }
                }) { Text("继续") }
            },
            dismissButton = { TextButton(onClick = { manual = false }) { Text("取消") } }
        )
    }
    selectedPeer?.let { peer ->
        AlertDialog(
            onDismissRequest = { selectedPeer = null },
            title = { Text("连接 " + peer.name) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("请输入对方设备显示的六位验证码。")
                    OutlinedTextField(
                        value = pin, onValueChange = { pin = it.filter(Char::isDigit).take(6) },
                        singleLine = true, label = { Text("配对验证码") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                    )
                }
            },
            confirmButton = {
                TextButton(enabled = pin.length == 6, onClick = {
                    client.connect(peer, pin)
                    selectedPeer = null
                }) { Text("连接") }
            },
            dismissButton = { TextButton(onClick = { selectedPeer = null }) { Text("取消") } }
        )
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
private fun RemoteViewer(onClose: () -> Unit) {
    val client = NearCastRuntime.client
    val size by client.videoSize.collectAsState()
    val status by client.status.collectAsState()
    val codec by client.codecName.collectAsState()
    val received by client.decodedFrames.collectAsState()
    val fps by client.receivedFps.collectAsState()
    val resync by client.recoveryRequests.collectAsState()
    val context = LocalContext.current
    val activity = context as? Activity
    var immersive by remember { mutableStateOf(false) }

    DisposableEffect(immersive) {
        val window = activity?.window
        if (window != null) {
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
                        Text("$status · $fps FPS(提交) · 同步请求 $resync · 总帧 $received",
                            style = MaterialTheme.typography.bodySmall, color = Color.LightGray)
                    }
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
            FilledTonalButton(
                modifier = Modifier.align(Alignment.TopEnd).padding(8.dp),
                onClick = { immersive = false }
            ) { Text("退出全屏") }
        }
    }
}
