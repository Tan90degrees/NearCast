package io.nearcast.app

import android.os.Build
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.nearcast.app.capture.CodecProbe
import io.nearcast.app.capture.VideoCodec
import io.nearcast.app.capture.VideoResolution
import io.nearcast.app.capture.VideoTransport
import io.nearcast.app.input.RemoteInputService
import io.nearcast.app.network.HostServer
import io.nearcast.app.network.NearbyPeer

private val background = Color(0xFF101717)
private val panel = Color(0xFF1C2929)
private val highlight = Color(0xFF86F1D5)

@Composable
internal fun NearCastHome(
    requestShare: () -> Unit,
    openAccessibility: () -> Unit,
    enableDirect: () -> Unit,
    openWriteSettings: () -> Unit,
    stopShare: () -> Unit
) {
    val host = NearCastRuntime.host ?: return
    val localDiscovery = NearCastRuntime.discovery ?: return
    val wifi = NearCastRuntime.wifiDirect ?: return
    val client = NearCastRuntime.client
    val clipboard = LocalClipboardManager.current

    val connected by client.connected.collectAsState()
    val clientStatus by client.status.collectAsState()
    val reconnecting by client.reconnecting.collectAsState()
    val disconnectReason by client.disconnectReason.collectAsState()
    val hostDisconnectReason by host.lastDisconnectReason.collectAsState()
    val hostConnected by host.connected.collectAsState()
    val hostStatus by host.status.collectAsState()
    val sharing by NearCastRuntime.captureActive.collectAsState()
    val dimmed by NearCastRuntime.dimActive.collectAsState()
    val codec by NearCastRuntime.codec.collectAsState()
    val resolution by NearCastRuntime.resolution.collectAsState()
    val fps by NearCastRuntime.requestedFps.collectAsState()
    val transport by NearCastRuntime.transport.collectAsState()
    val audio by NearCastRuntime.audioEnabled.collectAsState()
    val audioActive by NearCastRuntime.audioActive.collectAsState()
    val codecs by NearCastRuntime.codecResults.collectAsState()
    val probeStatus by NearCastRuntime.codecScanState.collectAsState()
    val peerMask by host.peerDecoderMask.collectAsState()
    val bitrate by client.bitrateLimitMbps.collectAsState()
    val peers by localDiscovery.peers.collectAsState()
    val discoveryError by localDiscovery.error.collectAsState()
    val directPeers by wifi.peers.collectAsState()
    val directState by wifi.status.collectAsState()
    val directActive by wifi.active.collectAsState()
    val directLink by wifi.link.collectAsState()
    val error by NearCastRuntime.message.collectAsState()
    val diagnostic by NearCastRuntime.captureDiagnostic.collectAsState()

    val physicalHz by host.physicalDisplayHz.collectAsState()
    val virtualHz by host.virtualDisplayHz.collectAsState()
    val actualFps by host.encodedFps.collectAsState()
    val encoderMbps by host.encodedMbps.collectAsState()
    val wireMbps by host.wireMbps.collectAsState()
    val encoderName by host.selectedEncoder.collectAsState()
    val frameGap by host.frameIntervalMs.collectAsState()
    val gapJitter by host.frameCadenceJitterMs.collectAsState()
    val backlog by host.writerQueueDepth.collectAsState()
    val dropped by host.droppedFrames.collectAsState()
    val effectiveBitrate by host.bitrateCapMbps.collectAsState()

    var tab by rememberSaveable { mutableIntStateOf(0) }
    var connectionMode by rememberSaveable { mutableIntStateOf(0) } // LAN / P2P
    var directRole by rememberSaveable { mutableIntStateOf(0) } // host / controller
    var selected by remember { mutableStateOf<NearbyPeer?>(null) }
    var pin by remember { mutableStateOf("") }
    var manual by remember { mutableStateOf(false) }
    var address by remember { mutableStateOf("") }
    var port by remember { mutableStateOf(host.port.toString()) }
    var bitrateSlider by remember(bitrate) { mutableFloatStateOf(bitrate.toFloat()) }

    if (connected) {
        RemoteViewer(onClose = { client.close() })
        return
    }

    Column(
        Modifier.fillMaxSize().background(background).statusBarsPadding()
            .navigationBarsPadding()
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("NearCast", style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold)
                Text("连接简单，画面流畅", color = Color.LightGray,
                    style = MaterialTheme.typography.labelMedium)
            }
            Text(if (hostConnected) "● 已配对" else "○ 等待连接",
                color = highlight, style = MaterialTheme.typography.labelMedium)
        }
        TabRow(selectedTabIndex = tab, containerColor = background) {
            listOf("设备", "画质", "诊断").forEachIndexed { index, name ->
                Tab(selected = tab == index, onClick = { tab = index }, text = { Text(name) })
            }
        }
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            if (reconnecting) {
                InfoCard {
                    Text("连接短暂中断 · 正在自动恢复", color = highlight)
                    Text(clientStatus, style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { client.close() }) { Text("取消自动重连") }
                }
            }
            if (error != null) {
                InfoCard {
                    Text(error ?: "", color = MaterialTheme.colorScheme.error)
                    if (diagnostic != null) TextButton(
                        onClick = { clipboard.setText(AnnotatedString(diagnostic ?: "")) }
                    ) { Text("复制错误详情") }
                }
            }

            when (tab) {
                0 -> {
                    InfoCard {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("这台设备", fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.weight(1f))
                            Text(if (sharing) "● 共享中" else if (hostConnected) "● 已连接"
                                else "○ 等待配对", color = highlight)
                        }
                        Text(hostStatus, style = MaterialTheme.typography.bodySmall,
                            color = Color.LightGray)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("连接验证码", color = Color.LightGray,
                                    style = MaterialTheme.typography.labelSmall)
                                Text(host.pin, style = MaterialTheme.typography.headlineMedium,
                                    fontWeight = FontWeight.Bold, color = highlight)
                            }
                            Text("端口 " + host.port, color = Color.LightGray)
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = requestShare,
                                enabled = hostConnected && !sharing,
                                modifier = Modifier.weight(1f)) {
                                Text("开始共享")
                            }
                            if (sharing) OutlinedButton(onClick = stopShare) { Text("停止") }
                            OutlinedButton(onClick = openAccessibility) { Text("触控权限") }
                        }
                        if (sharing) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text("省电暗屏", fontWeight = FontWeight.SemiBold)
                                    Text("最低亮度，不是物理关屏", color = Color.LightGray,
                                        style = MaterialTheme.typography.bodySmall)
                                }
                                Switch(checked = dimmed, onCheckedChange = { enabled ->
                                    val controller = NearCastRuntime.dimScreen
                                    if (enabled && controller?.permitted() != true)
                                        openWriteSettings()
                                    else if (enabled) NearCastRuntime.dimActive.value =
                                        controller?.enable() == true
                                    else {
                                        controller?.restore()
                                        NearCastRuntime.dimActive.value = false
                                    }
                                })
                            }
                        }
                    }

                    InfoCard {
                        Text("连接另一台手机", style = MaterialTheme.typography.titleMedium)
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            FilterChip(selected = connectionMode == 0,
                                onClick = { connectionMode = 0 }, label = { Text("同一 Wi-Fi") })
                            FilterChip(selected = connectionMode == 1,
                                onClick = { connectionMode = 1 }, label = { Text("Wi-Fi 直连") })
                        }
                        if (connectionMode == 0) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("局域网自动发现", modifier = Modifier.weight(1f))
                                TextButton(onClick = { manual = true }) { Text("手动输入 IP") }
                            }
                            if (discoveryError != null) Text(discoveryError ?: "",
                                color = MaterialTheme.colorScheme.error)
                            if (peers.isEmpty()) Text("暂未发现设备。请检查两台手机是否在同一 Wi-Fi。",
                                color = Color.LightGray)
                            peers.forEach { peer ->
                                DeviceRow(peer.name, peer.address) {
                                    selected = peer
                                    pin = ""
                                }
                            }
                        } else {
                            if (!wifi.isSupported()) {
                                Text("本机不支持系统 Wi-Fi Direct。",
                                    color = MaterialTheme.colorScheme.error)
                            } else {
                                Text(directState, color = highlight,
                                    style = MaterialTheme.typography.bodySmall)
                                if (!wifi.hasPermission() || !directActive) {
                                    Button(onClick = enableDirect) { Text("启用直连并授权") }
                                } else {
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        FilterChip(selected = directRole == 0,
                                            onClick = { directRole = 0 },
                                            label = { Text("共享这台手机") })
                                        FilterChip(selected = directRole == 1,
                                            onClick = { directRole = 1 },
                                            label = { Text("控制另一台") })
                                    }
                                    if (directRole == 0) {
                                        Text("① 建立直连组 → ② 对方搜索并加入 → ③ 对方输入验证码",
                                            color = Color.LightGray,
                                            style = MaterialTheme.typography.bodySmall)
                                        Button(
                                            enabled = host.port == HostServer.DIRECT_PORT &&
                                                directLink?.groupOwner != true,
                                            onClick = { wifi.createHostGroup() }
                                        ) { Text("建立直连组") }
                                        if (directLink?.groupOwner == true)
                                            Text("直连组已就绪。请让另一台手机搜索并加入。",
                                                color = highlight)
                                    } else {
                                        Text("① 搜索 → ② 选择共享手机并加入 → ③ 输入验证码",
                                            color = Color.LightGray,
                                            style = MaterialTheme.typography.bodySmall)
                                        OutlinedButton(onClick = { wifi.discover() }) {
                                            Text("扫描直连设备")
                                        }
                                        if (directPeers.isEmpty())
                                            Text("未找到设备。请先在对方手机建立直连组。",
                                                color = Color.LightGray)
                                        directPeers.forEach { peer ->
                                            DeviceRow(peer.name, "Wi-Fi Direct") {
                                                wifi.join(peer)
                                            }
                                        }
                                        if (directLink?.groupOwner == false) {
                                            Button(onClick = {
                                                selected = NearbyPeer("直连共享设备",
                                                    directLink?.address ?: "",
                                                    HostServer.DIRECT_PORT)
                                                pin = ""
                                            }) { Text("连接已加入的共享设备") }
                                        }
                                    }
                                    directLink?.let {
                                        Text("链路状态：" +
                                            if (it.groupOwner) "本机组主机" else "已连接 " + it.address,
                                            color = highlight,
                                            style = MaterialTheme.typography.bodySmall)
                                        TextButton(onClick = { wifi.disconnect() }) {
                                            Text("断开 Wi-Fi Direct")
                                        }
                                    }
                                }
                            }
                        }
                    }
                    Text("仅连接本人授权的设备。直连不保证编码速度或 120 FPS；" +
                        "当前媒体与控制协议仍是开发版明文传输。",
                        color = Color.Gray, style = MaterialTheme.typography.bodySmall)
                }
                1 -> {
                    InfoCard {
                        Text("控制端画质上限", style = MaterialTheme.typography.titleMedium)
                        Text(bitrateSlider.toInt().toString() + " Mbps",
                            color = highlight, style = MaterialTheme.typography.headlineSmall)
                        Slider(value = bitrateSlider, onValueChange = { bitrateSlider = it },
                            onValueChangeFinished = {
                                client.setBitrateLimit(bitrateSlider.toInt())
                            }, valueRange = 2f..60f, steps = 57)
                        Text("连接另一台手机后，会向其编码器实时发送目标码率。" +
                            "可在观看画面时继续调整。实际输出会随内容和编码器波动。",
                            color = Color.LightGray, style = MaterialTheme.typography.bodySmall)
                    }
                    InfoCard {
                        Text("被控端视频输出", style = MaterialTheme.typography.titleMedium)
                        Text("共享启动前设置；共享中更改分辨率、编码需要重新授权。",
                            color = Color.LightGray, style = MaterialTheme.typography.bodySmall)
                        Text("分辨率")
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            VideoResolution.entries.forEach { opt ->
                                FilterChip(selected = resolution == opt,
                                    enabled = !sharing,
                                    onClick = { NearCastRuntime.resolution.value = opt },
                                    label = { Text(opt.label) })
                            }
                        }
                        Text("目标帧率")
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(60, 90, 120).forEach { value ->
                                FilterChip(selected = fps == value, enabled = !sharing,
                                    onClick = { NearCastRuntime.requestedFps.value = value },
                                    label = { Text("$value FPS") })
                            }
                        }
                        Text("视频编解码格式")
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            VideoCodec.entries.forEach { opt ->
                                val found = codecs.firstOrNull { it.codec == opt }
                                val canEncode = found?.surfaceEncoder == true &&
                                    found.encodeStartup != false &&
                                    (opt != VideoCodec.AV1 ||
                                        found.hardwareEncoder ||
                                        (found.vendorEncoder && !found.softwareEncoder))
                                val peerCanDecode = !hostConnected ||
                                    CodecProbe.supports(peerMask, opt)
                                FilterChip(selected = codec == opt,
                                    enabled = !sharing && canEncode && peerCanDecode,
                                    onClick = { NearCastRuntime.codec.value = opt },
                                    label = { Text(opt.label) })
                            }
                        }
                        Text("网络媒体")
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            VideoTransport.entries.forEach { opt ->
                                FilterChip(selected = transport == opt, enabled = !sharing,
                                    onClick = { NearCastRuntime.transport.value = opt },
                                    label = { Text(opt.label) })
                            }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("共享设备内部声音")
                                Text("仅允许捕获的媒体/游戏声音",
                                    color = Color.LightGray,
                                    style = MaterialTheme.typography.bodySmall)
                            }
                            Switch(checked = audio, enabled = !sharing && Build.VERSION.SDK_INT >= 29,
                                onCheckedChange = { NearCastRuntime.audioEnabled.value = it })
                        }
                    }
                }
                else -> {
                    InfoCard {
                        Text("流状态", style = MaterialTheme.typography.titleMedium)
                        Text("本机显示 " + physicalHz.toInt() + "Hz / 虚拟屏 " +
                            virtualHz.toInt() + "Hz")
                        Text("实际编码 " + actualFps + " FPS · " +
                            "%.1f".format(encoderMbps) + " Mbps")
                        Text("网络写出 " + "%.1f".format(wireMbps) + " Mbps · 排队 " +
                            backlog + " · GOP 重同步 " + dropped)
                        Text("PTS 帧间隔 " + "%.2f".format(frameGap) + " ms / 波动 " +
                            "%.2f".format(gapJitter) + " ms")
                        Text("实际编码器：" + encoderName,
                            color = Color.LightGray, style = MaterialTheme.typography.bodySmall)
                        Text("接收端设定码率目标 " + effectiveBitrate + " Mbps")
                        Text("最后一次控制端断开：" + disconnectReason)
                        Text("最后一次被控端断开：" + hostDisconnectReason)
                        Text("客户端状态：" + clientStatus,
                            color = Color.LightGray, style = MaterialTheme.typography.bodySmall)
                        Text("音频：" + if (audioActive) "采集中" else "未采集")
                    }
                    InfoCard {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("编解码器探测", modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.titleMedium)
                            TextButton(onClick = { NearCastRuntime.runCodecStartupTest() },
                                enabled = !sharing) { Text("实测初始化") }
                        }
                        Text(probeStatus, color = Color.LightGray,
                            style = MaterialTheme.typography.bodySmall)
                        codecs.forEach { item ->
                            Text(item.codec.label + "：" +
                                (item.encoder ?: "无编码器") + " / " +
                                (item.decoder ?: "无解码器"), fontWeight = FontWeight.SemiBold)
                            Text((if (item.hardwareEncoder) "硬件编码" else "编码硬件未确认") +
                                " · 720p120 声明 " +
                                (if (item.encoding120) "支持" else "不支持") +
                                " · 初始化 " +
                                when (item.encodeStartup) {
                                    true -> "通过"
                                    false -> "失败"
                                    null -> "未测试"
                                }, color = Color.LightGray,
                                style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
    }

    if (manual) {
        AlertDialog(onDismissRequest = { manual = false },
            title = { Text("通过 IP 连接") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = address, onValueChange = { address = it },
                        label = { Text("IP 地址") }, singleLine = true)
                    OutlinedTextField(value = port,
                        onValueChange = { port = it.filter(Char::isDigit) },
                        label = { Text("端口") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val p = port.toIntOrNull()
                    if (address.isNotBlank() && p != null && p in 1..65535) {
                        selected = NearbyPeer(address.trim(), address.trim(), p)
                        pin = ""
                        manual = false
                    }
                }) { Text("下一步") }
            },
            dismissButton = { TextButton(onClick = { manual = false }) { Text("取消") } })
    }
    selected?.let { peer ->
        AlertDialog(
            onDismissRequest = { selected = null },
            title = { Text("连接 " + peer.name) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(peer.address + ":" + peer.port,
                        color = Color.LightGray, style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(value = pin,
                        onValueChange = { pin = it.filter(Char::isDigit).take(6) },
                        label = { Text("对方的六位验证码") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                }
            },
            confirmButton = {
                TextButton(enabled = pin.length == 6, onClick = {
                    client.connect(peer, pin)
                    selected = null
                }) { Text("连接") }
            },
            dismissButton = { TextButton(onClick = { selected = null }) { Text("取消") } })
    }
}

@Composable
private fun InfoCard(content: @Composable ColumnScope.() -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = panel),
        shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp),
            content = content)
    }
}

@Composable
private fun DeviceRow(name: String, detail: String, onClick: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(), color = Color(0xFF263535),
        shape = RoundedCornerShape(12.dp),
        onClick = onClick
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(name, fontWeight = FontWeight.SemiBold)
                Text(detail, color = Color.LightGray,
                    style = MaterialTheme.typography.bodySmall)
            }
            Text("连接", color = highlight)
        }
    }
}
