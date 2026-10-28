package io.nearcast.app.network

import android.content.Context
import android.media.MediaCodec
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import io.nearcast.app.capture.CaptureService
import io.nearcast.app.capture.VideoTransport
import io.nearcast.app.input.RemoteInputService
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * v2 experimental TCP protocol. PIN does not authenticate or encrypt the media.
 * Use only on a trusted LAN. No old v1 protocol interop.
 */
object Wire {
    const val MAGIC = "NEARCAST/3"
    const val SIZE = 1
    const val VIDEO = 2
    const val INPUT = 3
    const val SYNC = 4
    const val AUDIO = 5
    const val UDP_PORT = 6
    const val TAP = 1
    const val SWIPE = 2
    const val BACK = 3
    const val HOME = 4
    const val RECENTS = 5
    const val MAX_FRAME = 4 * 1024 * 1024
    const val MAX_AUDIO = 16384
}

class HostServer(private val context: Context) {
    val pin: String = (100000 + SecureRandom().nextInt(900000)).toString()
    private val server = ServerSocket(0)
    val port: Int = server.localPort
    val connected = MutableStateFlow(false)
    val status = MutableStateFlow("等待同一 Wi-Fi 下的设备连接")
    val droppedFrames = MutableStateFlow(0L)
    val encodedFps = MutableStateFlow(0)
    val encodedMbps = MutableStateFlow(0.0)
    val physicalDisplayHz = MutableStateFlow(0f)
    val virtualDisplayHz = MutableStateFlow(0f)
    val writerQueueDepth = MutableStateFlow(0)
    val wireMbps = MutableStateFlow(0.0)
    @Volatile var onNeedKeyframe: (() -> Unit)? = null
    @Volatile private var current: HostLink? = null
    private val running = AtomicBoolean(true)

    init {
        Thread({
            while (running.get()) {
                try {
                    val socket = server.accept()
                    Thread({ authenticate(socket) }, "NearCast-handshake").start()
                } catch (_: Exception) {
                    if (running.get()) status.value = "监听连接失败"
                }
            }
        }, "NearCast-host").start()
    }

    private fun authenticate(socket: Socket) {
        try {
            socket.tcpNoDelay = true
            socket.sendBufferSize = 128 * 1024
            socket.soTimeout = 5000
            val input = DataInputStream(socket.getInputStream())
            val output = DataOutputStream(socket.getOutputStream())
            val accepted = input.readUTF() == Wire.MAGIC &&
                MessageDigest.isEqual(input.readUTF().toByteArray(), pin.toByteArray()) &&
                current == null
            output.writeBoolean(accepted)
            output.flush()
            if (!accepted) {
                Thread.sleep(350)
                socket.close()
                return
            }
            val link = HostLink(socket, output, {
                droppedFrames.value += 1
                onNeedKeyframe?.invoke()
            }, { depth, mbps ->
                writerQueueDepth.value = depth
                wireMbps.value = mbps
            })
            current = link
            connected.value = true
            status.value = "已配对 · 请点击共享屏幕"
            socket.soTimeout = 0
            link.startWriter()
            try {
                while (running.get() && !socket.isClosed) {
                    when (input.readUnsignedByte()) {
                        Wire.INPUT -> {
                            val action = input.readInt()
                            val x1 = input.readFloat()
                            val y1 = input.readFloat()
                            val x2 = input.readFloat()
                            val y2 = input.readFloat()
                            val duration = input.readInt()
                            if (link === current)
                                RemoteInputService.dispatch(action, x1, y1, x2, y2, duration)
                        }
                        Wire.SYNC -> if (link === current) onNeedKeyframe?.invoke()
                        Wire.UDP_PORT -> {
                            val port = input.readInt()
                            if (link === current && port in 1..65535) {
                                link.configureUdpDestination(socket.inetAddress, port)
                                onNeedKeyframe?.invoke()
                            }
                        }
                        else -> throw IllegalArgumentException("Unknown control packet")
                    }
                }
            } finally {
                if (current === link) {
                    current = null
                    onNeedKeyframe = null
                    connected.value = false
                    status.value = "控制端已断开"
                    context.startService(CaptureService.stopIntent(context))
                }
                link.close()
            }
        } catch (e: Exception) {
            Log.w("NearCastHost", "Host client disconnect", e)
            try { socket.close() } catch (_: Exception) {}
        }
    }

    fun sendSize(width: Int, height: Int, mime: String, mode: VideoTransport) {
        current?.sendSize(width, height, mime, mode)
    }
    fun sendFrame(flags: Int, ptsUs: Long, data: ByteArray) {
        current?.sendFrame(flags, ptsUs, data)
    }
    fun sendAudio(data: ByteArray) { current?.sendAudio(data) }
    fun disconnectActivePeer() { current?.close() }
    fun close() {
        running.set(false)
        current?.close()
        current = null
        try { server.close() } catch (_: Exception) {}
    }

    private sealed class Packet {
        data class Size(
            val width: Int, val height: Int, val mime: String,
            val udp: Boolean, val udpPort: Int
        ) : Packet()
        data class Video(val flags: Int, val ptsUs: Long, val bytes: ByteArray) : Packet()
        data class Audio(val bytes: ByteArray) : Packet()
    }

    private class HostLink(
        val socket: Socket, private val output: DataOutputStream,
        private val requestSync: () -> Unit,
        private val onNetworkStats: (Int, Double) -> Unit
    ) {
        private val open = AtomicBoolean(true)
        private val pending = ArrayBlockingQueue<Packet>(24)
        private var writer: Thread? = null
        private var header: Packet.Size? = null
        private val cachedConfig = ArrayList<Packet.Video>()
        private var udpSender: UdpVideoSender? = null
        private var useUdp = false
        private var waitingKeyframe = false
        private var statsMs = SystemClock.elapsedRealtime()
        private var windowBytes = 0L

        fun startWriter() {
            writer = Thread({
                try {
                    while (open.get()) {
                        val item = pending.poll(250, java.util.concurrent.TimeUnit.MILLISECONDS)
                            ?: continue
                        when (item) {
                            is Packet.Size -> {
                                output.writeByte(Wire.SIZE)
                                output.writeInt(item.width)
                                output.writeInt(item.height)
                                output.writeUTF(item.mime)
                                output.writeBoolean(item.udp)
                                output.writeInt(item.udpPort)
                            }
                            is Packet.Video -> {
                                // SPS/PPS/VPS/AV1 config must be reliable, even
                                // when video data uses lossy UDP FastPath.
                                val config = item.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                                if (useUdp && !config) {
                                    udpSender?.send(item.flags, item.ptsUs, item.bytes)
                                } else {
                                    output.writeByte(Wire.VIDEO)
                                    output.writeInt(item.flags)
                                    output.writeLong(item.ptsUs)
                                    output.writeInt(item.bytes.size)
                                    output.write(item.bytes)
                                }
                            }
                            is Packet.Audio -> {
                                output.writeByte(Wire.AUDIO)
                                output.writeInt(item.bytes.size)
                                output.write(item.bytes)
                            }
                        }
                        windowBytes += when (item) {
                            is Packet.Video -> item.bytes.size.toLong()
                            is Packet.Audio -> item.bytes.size.toLong()
                            is Packet.Size -> 32L
                        }
                        val now = SystemClock.elapsedRealtime()
                        if (now - statsMs >= 1000L) {
                            onNetworkStats(pending.size,
                                windowBytes * 8.0 / (now - statsMs) / 1000.0)
                            statsMs = now
                            windowBytes = 0L
                        }
                    }
                } catch (e: Exception) {
                    if (open.get()) Log.w("NearCastHost", "Network writer failed", e)
                } finally { close() }
            }, "NearCast-video-writer").apply { start() }
        }

        @Synchronized fun sendSize(width: Int, height: Int, mime: String, mode: VideoTransport) {
            udpSender?.close()
            useUdp = mode == VideoTransport.UDP
            udpSender = if (useUdp) UdpVideoSender() else null
            val next = Packet.Size(width, height, mime, useUdp, udpSender?.localPort ?: 0)
            header = next
            cachedConfig.clear()
            pending.clear()
            waitingKeyframe = true
            pending.offer(next)
        }

        @Synchronized fun configureUdpDestination(address: java.net.InetAddress, port: Int) {
            udpSender?.setDestination(address, port)
            // Initial encoder config/IDR may have been emitted before the
            // controller advertised its UDP port. Start a NEW decodable GOP.
            pending.clear()
            header?.let { pending.offer(it) }
            waitingKeyframe = true
        }

        @Synchronized fun sendFrame(flags: Int, ptsUs: Long, data: ByteArray) {
            if (!open.get() || data.size > Wire.MAX_FRAME) return
            val pkt = Packet.Video(flags, ptsUs, data)
            if (flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                if (cachedConfig.none { it.bytes.contentEquals(data) }) {
                    if (cachedConfig.size >= 4) cachedConfig.removeAt(0)
                    cachedConfig.add(pkt)
                }
                if (!waitingKeyframe) pending.offer(pkt)
                return
            }
            val key = flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
            if (waitingKeyframe) {
                if (!key) return
                cachedConfig.forEach { pending.offer(it) }
                waitingKeyframe = false
            }
            if (pending.offer(pkt)) return

            // TCP can stall under Wi-Fi jitter. Never block MediaCodec drain.
            // Drop the queued GOP and request a fresh IDR. Any P-frame after a
            // missing reference is discarded until that IDR arrives.
            pending.clear()
            header?.let { pending.offer(it) }
            waitingKeyframe = true
            requestSync()
        }

        @Synchronized fun sendAudio(data: ByteArray) {
            if (!open.get() || data.size > Wire.MAX_AUDIO) return
            // Audio is best-effort; never evict video for it.
            pending.offer(Packet.Audio(data))
        }

        fun close() {
            if (!open.getAndSet(false)) return
            pending.clear()
            udpSender?.close()
            udpSender = null
            try { socket.close() } catch (_: Exception) {}
        }
    }
}

class RemoteClient {
    val connected = MutableStateFlow(false)
    val status = MutableStateFlow("未连接")
    val videoSize = MutableStateFlow(9 to 16)
    val codecName = MutableStateFlow("H.264")
    val decodedFrames = MutableStateFlow(0L)
    val receivedFps = MutableStateFlow(0)
    val recoveryRequests = MutableStateFlow(0L)
    private var frameWindowMs = SystemClock.elapsedRealtime()
    private var windowFrames = 0
    private val running = AtomicBoolean(false)
    @Volatile private var socket: Socket? = null
    @Volatile private var output: DataOutputStream? = null
    @Volatile private var decoder: AvcSurfaceDecoder? = null
    @Volatile private var audio: PlaybackAudioRenderer? = null
    @Volatile private var surface: Surface? = null
    @Volatile private var dimensions: Triple<Int, Int, String>? = null
    private val codecConfigs = ArrayList<ByteArray>()
    @Volatile private var configApplied = false
    @Volatile private var needKeyframe = true
    @Volatile private var udpReceiver: UdpVideoReceiver? = null
    @Volatile private var udpPort = 0
    private val sendLock = Any()
    private var lastSyncMs = 0L
    private val inputWriter = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "NearCast-input-writer").apply { isDaemon = true }
    }

    fun connect(peer: NearbyPeer, pin: String) {
        close()
        running.set(true)
        status.value = "正在连接 " + peer.name
        Thread({
            try {
                val s = Socket()
                socket = s
                s.connect(InetSocketAddress(peer.address, peer.port), 4000)
                s.tcpNoDelay = true
                s.soTimeout = 5000
                val writer = DataOutputStream(s.getOutputStream())
                val reader = DataInputStream(s.getInputStream())
                writer.writeUTF(Wire.MAGIC)
                writer.writeUTF(pin)
                writer.flush()
                if (!reader.readBoolean()) throw IllegalStateException("配对码错误或版本不兼容")
                s.soTimeout = 0
                output = writer
                connected.value = true
                status.value = "已连接 · 等待对方开始共享"
                while (running.get()) {
                    when (reader.readUnsignedByte()) {
                        Wire.SIZE -> {
                            val w = reader.readInt()
                            val h = reader.readInt()
                            val mime = reader.readUTF()
                            val udp = reader.readBoolean()
                            val serverPort = reader.readInt()
                            if (w !in 128..4096 || h !in 128..4096 ||
                                mime !in listOf("video/avc", "video/hevc", "video/av01") ||
                                (udp && serverPort !in 1..65535)) break
                            if (udp && (udpReceiver == null || udpPort != serverPort)) {
                                udpReceiver?.close()
                                udpPort = serverPort
                                try {
                                    val receiver = UdpVideoReceiver(s.inetAddress, serverPort,
                                        { f, p, data -> receiveVideo(f, p, data) },
                                        { requestSync() })
                                    udpReceiver = receiver
                                    sendCommand(Wire.UDP_PORT) {
                                        writer.writeInt(receiver.localPort)
                                    }
                                } catch (e: Exception) {
                                    status.value = "UDP 无法启动：" +
                                        (e.message ?: e.javaClass.simpleName)
                                    break
                                }
                            } else if (!udp) {
                                udpReceiver?.close()
                                udpReceiver = null
                                udpPort = 0
                            }
                            // Do not reset the decoder when the same SIZE is
                            // resent after a queue overflow.
                            val newSize = Triple(w, h, mime)
                            if (dimensions != newSize) {
                                dimensions = newSize
                                codecConfigs.clear()
                                configApplied = false
                                needKeyframe = true
                                videoSize.value = w to h
                                codecName.value = when (mime) {
                                    "video/hevc" -> "H.265"
                                    "video/av01" -> "AV1"
                                    else -> "H.264"
                                }
                                createDecoderIfReady()
                            }
                            status.value = "正在共享屏幕"
                        }
                        Wire.VIDEO -> {
                            val flags = reader.readInt()
                            val pts = reader.readLong()
                            val len = reader.readInt()
                            if (len !in 0..Wire.MAX_FRAME) break
                            val data = ByteArray(len)
                            reader.readFully(data)
                            receiveVideo(flags, pts, data)
                        }
                        Wire.AUDIO -> {
                            val len = reader.readInt()
                            if (len !in 0..Wire.MAX_AUDIO) break
                            val samples = ByteArray(len)
                            reader.readFully(samples)
                            if (audio == null) {
                                try { audio = PlaybackAudioRenderer() }
                                catch (e: Exception) { Log.w("NearCastAudio", "Output unavailable", e) }
                            }
                            audio?.offer(samples)
                        }
                        else -> break
                    }
                }
            } catch (e: Exception) {
                if (running.get()) status.value = e.message ?: "连接中断"
            } finally { close() }
        }, "NearCast-client").start()
    }

    /** Shared handler for TCP and UDP video access units. */
    private fun receiveVideo(flags: Int, pts: Long, data: ByteArray) {
                if (flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                    if (codecConfigs.none { it.contentEquals(data) }) {
                        if (codecConfigs.size >= 4) codecConfigs.removeAt(0)
                        codecConfigs.add(data.copyOf())
                    }
                    val current = decoder
                    if (current != null && current.queue(flags, pts, data)) {
                        configApplied = true
                    }
                } else {
                    if (needKeyframe) {
                        if (flags and MediaCodec.BUFFER_FLAG_KEY_FRAME == 0) return
                        // On resume decoder was recreated, replay codec
                        // parameter sets before accepting the IDR.
                        val current = decoder
                        if (current == null) return
                        if (!configApplied) {
                            var applied = true
                            codecConfigs.forEach {
                                if (!current.queue(MediaCodec.BUFFER_FLAG_CODEC_CONFIG, 0, it))
                                    applied = false
                            }
                            configApplied = applied
                        }
                        needKeyframe = false
                    }
                    val target = decoder
                    if (target != null) {
                        if (!target.queue(flags, pts, data)) {
                            needKeyframe = true
                            requestSync()
                        } else {
                            decodedFrames.value += 1
                            windowFrames++
                            val now = SystemClock.elapsedRealtime()
                            if (now - frameWindowMs >= 1000) {
                                receivedFps.value = (windowFrames * 1000L /
                                    (now - frameWindowMs).coerceAtLeast(1L)).toInt()
                                frameWindowMs = now
                                windowFrames = 0
                            }
                        }
                    }
                }
    }

    @Synchronized
    private fun createDecoderIfReady() {
        val dims = dimensions ?: return
        val target = surface ?: return
        decoder?.close()
        decoder = try { AvcSurfaceDecoder(target, dims.first, dims.second, dims.third) }
        catch (e: Exception) {
            status.value = "解码器不可用：" + (e.message ?: e.javaClass.simpleName)
            null
        }
        needKeyframe = true
        configApplied = false
        requestSync()
    }

    fun attachSurface(value: Surface?) {
        surface = value
        if (value == null) {
            // Backgrounding destroys SurfaceView's native window.
            // The next surface MUST get its own codec and fresh IDR.
            decoder?.close()
            decoder = null
            configApplied = false
            needKeyframe = true
        } else createDecoderIfReady()
    }

    private fun requestSync() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastSyncMs < 300) return
        lastSyncMs = now
        recoveryRequests.value += 1
        sendCommand(Wire.SYNC)
    }

    private fun sendCommand(op: Int, payload: (() -> Unit)? = null) {
        val s = socket ?: return
        val writer = output ?: return
        inputWriter.execute {
            if (!connected.value || socket !== s || output !== writer) return@execute
            try {
                synchronized(sendLock) {
                    if (socket !== s) return@synchronized
                    writer.writeByte(op)
                    payload?.invoke()
                    writer.flush()
                }
            } catch (_: Exception) {
                if (socket === s) close()
            }
        }
    }

    fun input(action: Int, x1: Float = 0f, y1: Float = 0f,
              x2: Float = 0f, y2: Float = 0f, duration: Int = 0) {
        sendCommand(Wire.INPUT) {
            val writer = output ?: return@sendCommand
            writer.writeInt(action)
            writer.writeFloat(x1.coerceIn(0f, 1f))
            writer.writeFloat(y1.coerceIn(0f, 1f))
            writer.writeFloat(x2.coerceIn(0f, 1f))
            writer.writeFloat(y2.coerceIn(0f, 1f))
            writer.writeInt(duration.coerceIn(0, 10000))
        }
    }

    fun close() {
        running.set(false)
        try { socket?.close() } catch (_: Exception) {}
        socket = null
        output = null
        udpReceiver?.close()
        udpReceiver = null
        udpPort = 0
        decoder?.close()
        decoder = null
        audio?.close()
        audio = null
        dimensions = null
        codecConfigs.clear()
        configApplied = false
        needKeyframe = true
        receivedFps.value = 0
        connected.value = false
    }
}
