package io.nearcast.app.network

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import kotlinx.coroutines.flow.MutableStateFlow
import java.net.Inet4Address
import java.util.concurrent.ConcurrentHashMap

data class NearbyPeer(val name: String, val address: String, val port: Int) {
    val key: String get() = address + ":" + port
}

/**
 * Advertise and browse the same DNS-SD service. No Internet or cloud discovery.
 * A multicast lock is needed for some pre-Android-13 Wi-Fi implementations.
 */
class NearbyDiscovery(context: Context) {
    companion object { const val SERVICE_TYPE = "_nearcast._tcp." }

    val peers = MutableStateFlow<List<NearbyPeer>>(emptyList())
    val error = MutableStateFlow<String?>(null)

    private val appContext = context.applicationContext
    private val nsd = appContext.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val wifi = appContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private val multicastLock = wifi.createMulticastLock("nearcast-mdns").apply { setReferenceCounted(false) }
    private val discovered = ConcurrentHashMap<String, NearbyPeer>()
    private var registration: NsdManager.RegistrationListener? = null
    private var discovery: NsdManager.DiscoveryListener? = null
    private var localName = ""

    fun start(port: Int) {
        if (registration != null || discovery != null) return
        try {
            multicastLock.acquire()
            localName = "NearCast-" + Build.MODEL.take(24)
            val info = NsdServiceInfo().apply {
                serviceName = localName
                serviceType = SERVICE_TYPE
                setPort(port)
            }
            val reg = object : NsdManager.RegistrationListener {
                override fun onServiceRegistered(info: NsdServiceInfo) { localName = info.serviceName }
                override fun onRegistrationFailed(info: NsdServiceInfo, code: Int) {
                    error.value = "发布设备失败：" + code
                }
                override fun onServiceUnregistered(info: NsdServiceInfo) = Unit
                override fun onUnregistrationFailed(info: NsdServiceInfo, code: Int) = Unit
            }
            registration = reg
            nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, reg)
            val listener = object : NsdManager.DiscoveryListener {
                override fun onDiscoveryStarted(type: String) = Unit
                override fun onStartDiscoveryFailed(type: String, code: Int) {
                    error.value = "搜索设备失败：" + code
                }
                override fun onStopDiscoveryFailed(type: String, code: Int) = Unit
                override fun onDiscoveryStopped(type: String) = Unit
                override fun onServiceLost(info: NsdServiceInfo) {
                    discovered.remove(info.serviceName)
                    publish()
                }
                override fun onServiceFound(info: NsdServiceInfo) {
                    if (info.serviceName == localName || !info.serviceType.startsWith("_nearcast._tcp")) return
                    @Suppress("DEPRECATION")
                    nsd.resolveService(info, object : NsdManager.ResolveListener {
                        override fun onResolveFailed(info: NsdServiceInfo, code: Int) = Unit
                        override fun onServiceResolved(resolved: NsdServiceInfo) {
                            val address = resolved.host
                            if (address !is Inet4Address || resolved.port <= 0) return
                            discovered[resolved.serviceName] = NearbyPeer(
                                resolved.serviceName.removePrefix("NearCast-"),
                                address.hostAddress ?: return,
                                resolved.port
                            )
                            publish()
                        }
                    })
                }
            }
            discovery = listener
            nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (e: Exception) {
            error.value = "局域网发现不可用：" + (e.message ?: "未知错误")
            stop()
        }
    }

    private fun publish() {
        peers.value = discovered.values.sortedBy { it.name.lowercase() }
    }

    fun stop() {
        discovery?.let {
            try { nsd.stopServiceDiscovery(it) } catch (_: Exception) {}
        }
        registration?.let {
            try { nsd.unregisterService(it) } catch (_: Exception) {}
        }
        discovery = null
        registration = null
        if (multicastLock.isHeld) multicastLock.release()
        discovered.clear()
        peers.value = emptyList()
    }
}
