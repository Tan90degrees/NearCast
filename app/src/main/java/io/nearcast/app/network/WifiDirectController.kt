package io.nearcast.app.network

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.wifi.WpsInfo
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Direct Android-to-Android wireless link, no third-party router or cloud.
 * Host phone creates group; controller joins and connects to GO IP + TCP port.
 * Wi-Fi Direct does not bypass MediaProjection permission or encryption needs.
 */
class WifiDirectController(context: Context) {
    data class Peer(val name: String, val deviceAddress: String)
    data class Link(val groupOwner: Boolean, val address: String)

    val peers = MutableStateFlow<List<Peer>>(emptyList())
    val status = MutableStateFlow("未启动 Wi-Fi Direct")
    val active = MutableStateFlow(false)
    val link = MutableStateFlow<Link?>(null)

    private val app = context.applicationContext
    private val manager = app.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
    private var channel: WifiP2pManager.Channel? = null
    private var registered = false
    private val mainHandler = Handler(Looper.getMainLooper())

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                    val state = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1)
                    active.value = state == WifiP2pManager.WIFI_P2P_STATE_ENABLED
                    if (!active.value) {
                        link.value = null
                        status.value = "设备的 Wi-Fi Direct 不可用或已关闭"
                    }
                }
                WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> refreshPeers()
                WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> refreshConnection()
                WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION -> Unit
            }
        }
    }

    fun isSupported(): Boolean =
        manager != null && app.packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI_DIRECT)

    fun hasPermission(): Boolean {
        val permission = if (Build.VERSION.SDK_INT >= 33)
            Manifest.permission.NEARBY_WIFI_DEVICES else Manifest.permission.ACCESS_FINE_LOCATION
        return ContextCompat.checkSelfPermission(app, permission) ==
            PackageManager.PERMISSION_GRANTED
    }

    fun start() {
        mainHandler.post {
            if (!isSupported()) {
                status.value = "这台手机未提供 Wi-Fi Direct 能力"
                return@post
            }
            if (!hasPermission()) {
                status.value = "请先授权附近设备权限"
                return@post
            }
            val p2p = manager ?: return@post
            if (channel == null) {
                channel = p2p.initialize(app, Looper.getMainLooper()) {
                    channel = null
                    link.value = null
                    status.value = "Wi-Fi Direct 通道已断开，请重新启用"
                }
            }
            if (!registered) {
                val filter = IntentFilter().apply {
                    addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
                    addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
                    addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
                    addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION)
                }
                ContextCompat.registerReceiver(app, receiver, filter,
                    ContextCompat.RECEIVER_EXPORTED)
                registered = true
            }
            active.value = true
            status.value = "Wi-Fi Direct 已就绪，选择创建直连或搜索"
            refreshConnection()
        }
    }

    @SuppressLint("MissingPermission")
    fun discover() {
        mainHandler.post {
            val p2p = manager ?: return@post
            val c = channel ?: return@post
            if (!hasPermission()) {
                status.value = "需要附近设备权限"
                return@post
            }
            status.value = "正在搜索可直连设备…"
            p2p.discoverPeers(c, action(
                { status.value = "正在发现设备；请在另一台手机选择「建立直连」" },
                { status.value = "直连搜索失败，错误码 " + it + "；请检查 Wi-Fi 和系统位置服务" }
            ))
        }
    }

    @SuppressLint("MissingPermission")
    fun createHostGroup() {
        mainHandler.post {
            val p2p = manager ?: return@post
            val c = channel ?: return@post
            if (!hasPermission()) return@post
            status.value = "正在创建点对点直连组…"
            p2p.createGroup(c, action(
                { status.value = "已请求创建直连组，等待控制手机加入"; refreshConnection() },
                { status.value = "建立直连失败，错误码 " + it }
            ))
        }
    }

    @SuppressLint("MissingPermission")
    fun join(peer: Peer) {
        mainHandler.post {
            val p2p = manager ?: return@post
            val c = channel ?: return@post
            if (!hasPermission()) return@post
            val config = WifiP2pConfig().apply {
                deviceAddress = peer.deviceAddress
                wps.setup = WpsInfo.PBC
                groupOwnerIntent = 0
            }
            status.value = "正在直连 " + peer.name + "，系统可能要求双方确认"
            p2p.connect(c, config, action(
                { status.value = "请求已发送，等待直连组形成" },
                { status.value = "直连失败，错误码 " + it }
            ))
        }
    }

    @SuppressLint("MissingPermission")
    fun disconnect() {
        mainHandler.post {
            val p2p = manager ?: return@post
            val c = channel ?: return@post
            if (!hasPermission()) return@post
            p2p.removeGroup(c, action(
                {
                    link.value = null
                    status.value = "已退出直连组"
                },
                { status.value = "退出直连组失败，错误码 " + it }
            ))
        }
    }

    @SuppressLint("MissingPermission")
    private fun refreshPeers() {
        val p2p = manager ?: return
        val c = channel ?: return
        if (!hasPermission()) return
        p2p.requestPeers(c) { list ->
            peers.value = list.deviceList
                .filter { it.deviceAddress.isNotBlank() }
                .map { Peer(it.deviceName.ifBlank { "Android 设备" }, it.deviceAddress) }
                .sortedBy { it.name }
        }
    }

    @SuppressLint("MissingPermission")
    private fun refreshConnection() {
        val p2p = manager ?: return
        val c = channel ?: return
        if (!hasPermission()) return
        p2p.requestConnectionInfo(c) { info ->
            if (info != null && info.groupFormed) {
                val address = info.groupOwnerAddress?.hostAddress.orEmpty()
                if (info.isGroupOwner || address.isNotBlank()) {
                    link.value = Link(info.isGroupOwner, address)
                    status.value = if (info.isGroupOwner)
                        "直连组已创建 · 本机为被控端，等待控制端连接"
                    else "直连成功 · 组主机 " + address
                }
            } else {
                link.value = null
            }
        }
    }

    private fun action(success: () -> Unit, failure: (Int) -> Unit) =
        object : WifiP2pManager.ActionListener {
            override fun onSuccess() = success()
            override fun onFailure(reason: Int) = failure(reason)
        }

    fun stop() {
        mainHandler.post {
            if (registered) {
                try { app.unregisterReceiver(receiver) } catch (_: Exception) {}
                registered = false
            }
            channel = null
            peers.value = emptyList()
            link.value = null
            active.value = false
        }
    }
}
