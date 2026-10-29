package io.nearcast.app.network

import android.content.Context
import android.media.MediaCodec
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import io.nearcast.app.capture.CaptureService
import io.nearcast.app.capture.VideoTransport
import io.nearcast.app.capture.CodecProbe
import io.nearcast.app.capture.VideoCodec
import io.nearcast.app.input.RemoteInputService
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import kotlin.math.abs
import java.security.SecureRandom
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicBoolean

/**
 * v2 experimental TCP protocol. PIN does not authenticate or encrypt the media.
 * Use only on a trusted LAN. No old v1 protocol interop.
 */
object Wire {
    const val MAGIC = "NEARCAST/5"
    const val SIZE = 1
    const val VIDEO = 2
    const val INPUT = 3
    const val SYNC = 4
    const val AUDIO = 5
    const val UDP_PORT = 6
    const val PING = 7
    const val PONG = 8
    const val SET_BITRATE = 9
    const val TAP = 1
    const val SWIPE = 2
    const val BACK = 3
    const val HOME = 4
    const val RECENTS = 5
    const val MAX_FRAME = 4 * 1024 * 1024
    const val MAX_AUDIO = 16384
}

class HostServer(private val context: Context) {
    companion object {
        // Fixed port allows Wi-Fi Direct group members to connect without
        // relying on multicast/mDNS (often unavailable on P2P interfaces).
        const val DIRECT_PORT = 39491
    }
    val pin: String = (100000 + SecureRandom().nextInt(900000)).toString()
    private val server = try {
        ServerSocket(DIRECT_PORT)
    } catch (e: java.io.IOException) {
        Log.w("NearCastHost", "Direct port busy; falling back to dynamic LAN port", e)
        ServerSocket(0)
    }
    val port: Int = server.localPort
    val connected = MutableStateFlow(false)
    val status = MutableStateFlow("等待同一 Wi-Fi 下的设备连接")
    val lastDisconnectReason = MutableStateFlow("暂无")
    private data class StreamInfo(val w: Int, val h: Int, val mime: String,
                                  val transport: VideoTransport)
    @Volatile private var streamInfo: StreamInfo? = null
    private val linkEpoch = AtomicLong(0L)
    val droppedFrames = MutableStateFlow(0L)
    val peerDecoderMask = MutableStateFlow(0)
    /** Controller-requested encoder target, bounded independently by host safety cap. */
    val bitrateCapMbps = MutableStateFlow(10)
    val maxBitrateMbps = 60
    @Volatile var onBitrateLimitChanged: ((Int) -> Unit)? = null
    val encodedFps = MutableStateFlow(0)
    val selectedEncoder = MutableStateFlow("尚未开始共享")
    val frameIntervalMs = MutableStateFlow(0.0)
    val frameCadenceJitterMs = MutableStateFlow(0.0)
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
            val matchesVersion = input.readUTF() == Wire.MAGIC
            val matchesPin = MessageDigest.isEqual(
                input.readUTF().toByteArray(), pin.toByteArray()
            )
            val advertisedDecoderMask = input.readInt() and 0x7
            val accepted = matchesVersion && matchesPin && current == null
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
            linkEpoch.incrementAndGet()
            peerDecoderMask.value = advertisedDecoderMask
            connected.value = true
            status.value = "已配对 · 请点击共享屏幕"
            socket.soTimeout = 0
            link.startWriter()
            // If TCP was interrupted briefly, the Android MediaProjection
            // remains active through the reconnection grace period.
            // Attach the NEW transport to the same encoder without seeking a
            // second one-use screen-capture consent token.
            streamInfo?.let {
                link.sendSize(it.w, it.h, it.mime, it.transport)
                if (it.transport == VideoTransport.TCP) onNeedKeyframe?.invoke()
            }
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
                        Wire.SET_BITRATE -> {
                            val requested = input.readInt().coerceIn(2, maxBitrateMbps)
                            if (link === current) {
                                bitrateCapMbps.value = requested
                                onBitrateLimitChanged?.invoke(requested)
                            }
                        }
                        Wire.PING -> {
                            val sentTimeMs = input.readLong()
                            if (link === current) link.pong(sentTimeMs)
                        }
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
                    peerDecoderMask.value = 0
                    connected.value = false
                    val disconnectedEpoch = linkEpoch.incrementAndGet()
                    val reason = if (socket.isClosed) "控制通道已关闭" else "控制通道已中断"
                    lastDisconnectReason.value = reason
                    if (streamInfo != null) {
                        status.value = "连接中断，保留采集 15 秒等待客户端恢复"
                        Thread({
                            try { Thread.sleep(15_000) }
                            catch (_: InterruptedException) { return@Thread }
                            if (linkEpoch.get() == disconnectedEpoch && current == null &&
                                streamInfo != null) {
                                Log.w("NearCastHost", "Reconnect grace expired; stop capture")
                                lastDisconnectReason.value = "15 秒内未能重连"
                                try { context.startService(CaptureService.stopIntent(context)) }
                                catch (e: Exception) {
                                    Log.e("NearCastHost", "Cannot stop capture after grace", e)
                                }
                            }
                        }, "NearCast-reconnect-grace").apply { isDaemon = true; start() }
                    } else status.value = "控制端已断开"
                }
                link.close()
            }
        } catch (e: Exception) {
            Log.w("NearCastHost", "Host client disconnect", e)
            try { socket.close() } catch (_: Exception) {}
        }
    }

    fun sendSize(width: Int, height: Int, mime: String, mode: VideoTransport) {
        streamInfo = StreamInfo(width, height, mime, mode)
        current?.sendSize(width, height, mime, mode)
    }

    fun clearStream() {
        streamInfo = null
        onNeedKeyframe = null
        onBitrateLimitChanged = null
        bitrateCapMbps.value = 10
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
        streamInfo = null
        linkEpoch.incrementAndGet()
        try { server.close() } catch (_: Exception) {}
    }

    private sealed class Packet {
        data class Size(
            val width: Int, val height: Int, val mime: String,
            val udp: Boolean, val udpPort: Int
        ) : Packet()
        data class Video(val flags: Int, val ptsUs: Long, val bytes: ByteArray) : Packet()
        data class Audio(val bytes: ByteArray) : Packet()
        data class Pong(val timestamp: Long) : Packet()
    }

    /**
     * Split video and reliable control. UDP fragmentation must NEVER block
     * PONG/codec configuration/audio and cannot terminate the TCP session.
     */
    private class HostLink(
        val socket: Socket, private val output: DataOutputStream,
        private val requestSync: () -> Unit,
        private val onNetworkStats: (Int, Double) -> Unit
    ) {
        private val open = AtomicBoolean(true)
        private val priority = ArrayBlockingQueue<Packet>(32)
        private val audioQueue = ArrayBlockingQueue<Packet.Audio>(12)
        private val tcpVideo = ArrayBlockingQueue<Packet.Video>(12)
        private val udpVideo = ArrayBlockingQueue<Packet.Video>(12)
        private var udpSender: UdpVideoSender? = null
        private var usingUdp = false
        private var udpReady = false
        private var streamHeader: Packet.Size? = null
        private val cachedConfig = ArrayList<Packet.Video>()
        private var awaitingIdr = true
        private var lastRecoveryMs = 0L
        private var writer: Thread? = null
        private var udpWriter: Thread? = null

        fun startWriter() {
            writer = Thread({
                var lastStatsMs = SystemClock.elapsedRealtime()
                var sentBytes = 0L
                try {
                    while (open.get()) {
                        // An urgent PONG/config cannot sit behind an entire
                        // GOP of encoded packets on the application queue.
                        val packet = priority.poll()
                            ?: audioQueue.poll()
                            ?: tcpVideo.poll(5, java.util.concurrent.TimeUnit.MILLISECONDS)
                            ?: continue
                        when (packet) {
                            is Packet.Size -> {
                                output.writeByte(Wire.SIZE)
                                output.writeInt(packet.width)
                                output.writeInt(packet.height)
                                output.writeUTF(packet.mime)
                                output.writeBoolean(packet.udp)
                                output.writeInt(packet.udpPort)
                                sentBytes += 32L
                            }
                            is Packet.Video -> {
                                output.writeByte(Wire.VIDEO)
                                output.writeInt(packet.flags)
                                output.writeLong(packet.ptsUs)
                                output.writeInt(packet.bytes.size)
                                output.write(packet.bytes)
                                sentBytes += packet.bytes.size.toLong()
                            }
                            is Packet.Audio -> {
                                output.writeByte(Wire.AUDIO)
                                output.writeInt(packet.bytes.size)
                                output.write(packet.bytes)
                                sentBytes += packet.bytes.size.toLong()
                            }
                            is Packet.Pong -> {
                                output.writeByte(Wire.PONG)
                                output.writeLong(packet.timestamp)
                                sentBytes += 9L
                            }
                        }
                        val now = SystemClock.elapsedRealtime()
                        if (now - lastStatsMs >= 1000L) {
                            onNetworkStats(tcpVideo.size + udpVideo.size,
                                sentBytes * 8.0 / (now - lastStatsMs).coerceAtLeast(1L) / 1000.0)
                            sentBytes = 0L
                            lastStatsMs = now
                        }
                    }
                } catch (e: Exception) {
                    if (open.get()) Log.e("NearCastHost", "TCP connection failed", e)
                } finally {
                    close()
                }
            }, "NearCast-reliable-writer").apply { isDaemon = true; start() }

            udpWriter = Thread({
                var failures = 0
                while (open.get()) {
                    try {
                        val packet = udpVideo.poll(100, java.util.concurrent.TimeUnit.MILLISECONDS)
                            ?: continue
                        val sender = synchronized(this) {
                            if (usingUdp && udpReady) udpSender else null
                        } ?: continue
                        sender.send(packet.flags, packet.ptsUs, packet.bytes)
                        failures = 0
                    } catch (e: InterruptedException) {
                        Thread.currentThread().interrupt()
                        return@Thread
                    } catch (e: Exception) {
                        if (!open.get()) break
                        failures++
                        Log.w("NearCastUDP", "UDP video send failure #" + failures +
                            " (TCP control remains connected)", e)
                        synchronized(this) { resetVideoQueue() }
                        if (failures == 1 || failures % 8 == 0) requestSync()
                        try { Thread.sleep(40L.coerceAtMost(failures.toLong() * 10L)) }
                        catch (_: InterruptedException) { return@Thread }
                    }
                }
            }, "NearCast-udp-writer").apply { isDaemon = true; start() }
        }

        @Synchronized fun sendSize(width: Int, height: Int, mime: String, mode: VideoTransport) {
            udpSender?.close()
            usingUdp = mode == VideoTransport.UDP
            udpReady = !usingUdp
            udpSender = if (usingUdp) UdpVideoSender() else null
            val header = Packet.Size(width, height, mime, usingUdp,
                udpSender?.localPort ?: 0)
            streamHeader = header
            cachedConfig.clear()
            priority.clear()
            audioQueue.clear()
            tcpVideo.clear()
            udpVideo.clear()
            awaitingIdr = true
            priority.offer(header)
        }

        @Synchronized fun configureUdpDestination(address: java.net.InetAddress, port: Int) {
            if (!usingUdp) return
            udpSender?.setDestination(address, port)
            udpReady = true
            udpVideo.clear()
            awaitingIdr = true
            // Decoder must receive codec parameters before the new IDR.
            cachedConfig.forEach { priority.offer(it) }
            requestSync()
        }

        @Synchronized fun sendFrame(flags: Int, ptsUs: Long, data: ByteArray) {
            if (!open.get() || streamHeader == null || data.size > Wire.MAX_FRAME) return
            val packet = Packet.Video(flags, ptsUs, data)
            if (flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                if (cachedConfig.none { it.bytes.contentEquals(data) }) {
                    if (cachedConfig.size >= 4) cachedConfig.removeAt(0)
                    cachedConfig.add(packet)
                }
                priority.offer(packet)
                return
            }
            if (usingUdp && !udpReady) return
            val keyFrame = flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
            if (awaitingIdr) {
                if (!keyFrame) return
                cachedConfig.forEach { priority.offer(it) }
                awaitingIdr = false
            }
            val queue = if (usingUdp) udpVideo else tcpVideo
            if (queue.offer(packet)) return
            // Drop only the obsolete queued GOP; do not block MediaCodec or
            // close the whole connection on transient pressure.
            resetVideoQueue()
            requestSync()
        }

        @Synchronized private fun resetVideoQueue() {
            tcpVideo.clear()
            udpVideo.clear()
            awaitingIdr = true
            val now = SystemClock.elapsedRealtime()
            if (now - lastRecoveryMs < 500L) return
            lastRecoveryMs = now
        }

        fun pong(timestampMs: Long) {
            if (open.get()) priority.offer(Packet.Pong(timestampMs))
        }

        fun sendAudio(data: ByteArray) {
            if (open.get() && data.size <= Wire.MAX_AUDIO) {
                // Best effort PCM; never forces the loss of the video GOP.
                audioQueue.offer(Packet.Audio(data))
            }
        }

        fun close() {
            if (!open.getAndSet(false)) return
            priority.clear()
            audioQueue.clear()
            tcpVideo.clear()
            udpVideo.clear()
            synchronized(this) {
                udpSender?.close()
                udpSender = null
            }
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
    val renderedFps = MutableStateFlow(0)
    val lowestLatencyRender = MutableStateFlow(true)
    val receiveMbps = MutableStateFlow(0.0)
    val decodeQueueDepth = MutableStateFlow(0)
    val videoQueueOverflows = MutableStateFlow(0L)
    val recoveryRequests = MutableStateFlow(0L)
    val bitrateLimitMbps = MutableStateFlow(10)
    val controlRttMs = MutableStateFlow(0)
    val controlJitterMs = MutableStateFlow(0)
    private var previousRttMs = 0
    private var frameWindowMs = SystemClock.elapsedRealtime()
    private var windowFrames = 0
    private var totalSubmittedFrames = 0L
    private data class VideoUnit(val flags: Int, val pts: Long, val bytes: ByteArray)
    private val decodeLock = Any()
    private val videoQueue = ArrayBlockingQueue<VideoUnit>(12)
    private val rxWindowBytes = AtomicLong()
    private val running = AtomicBoolean(false)
    private val decodeWorker = Thread({
        var lastMetricsMs = SystemClock.elapsedRealtime()
        while (true) {
            try {
                val unit = videoQueue.poll(100, java.util.concurrent.TimeUnit.MILLISECONDS)
                if (unit != null && running.get()) {
                    synchronized(decodeLock) {
                        if (running.get()) processVideo(unit.flags, unit.pts, unit.bytes)
                    }
                }
                val now = SystemClock.elapsedRealtime()
                if (now - lastMetricsMs >= 1000L) {
                    val elapsed = (now - lastMetricsMs).coerceAtLeast(1L)
                    receiveMbps.value = rxWindowBytes.getAndSet(0L) * 8.0 / elapsed / 1000.0
                    decodeQueueDepth.value = videoQueue.size
                    lastMetricsMs = now
                }
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                break
            } catch (e: Exception) {
                Log.w("NearCastVideo", "Decode worker recovered", e)
            }
        }
    }, "NearCast-video-decode").apply { isDaemon = true; start() }
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

    private val generation = AtomicLong(0L)
    val reconnecting = MutableStateFlow(false)
    val disconnectReason = MutableStateFlow("暂无")

    fun connect(peer: NearbyPeer, pin: String) {
        val token = generation.incrementAndGet()
        connectAttempt(peer, pin, 0, token)
    }

    private fun connectAttempt(peer: NearbyPeer, pin: String,
                               attempt: Int, token: Long) {
        Thread({
            if (generation.get() != token) return@Thread
            closeTransport()
            if (generation.get() != token) return@Thread
            running.set(true)
            reconnecting.value = attempt > 0
        receivedFps.value = 0
        renderedFps.value = 0
        videoQueueOverflows.value = 0
        totalSubmittedFrames = 0L
        decodedFrames.value = 0L
        status.value = "正在连接 " + peer.name
            val session = Socket()
            var rejection = false
            var failureDetail = "连接关闭"
            try {
                val s = session
                socket = s
                s.connect(InetSocketAddress(peer.address, peer.port), 4000)
                s.tcpNoDelay = true
                s.soTimeout = 5000
                val writer = DataOutputStream(s.getOutputStream())
                val reader = DataInputStream(s.getInputStream())
                writer.writeUTF(Wire.MAGIC)
                writer.writeUTF(pin)
                writer.writeInt(CodecProbe.decoderMask(CodecProbe.scan()))
                writer.flush()
                if (!reader.readBoolean()) {
                    rejection = true
                    throw SecurityException("配对码错误或通信版本不兼容")
                }
                s.soTimeout = 0
                output = writer
                connected.value = true
                reconnecting.value = false
                disconnectReason.value = "暂无"
                status.value = if (attempt > 0) "已自动重连 · 等待恢复视频"
                    else "已连接 · 等待对方开始共享"
                // The host must receive this before or during its encoder startup.
                setBitrateLimit(bitrateLimitMbps.value)
                Thread({
                    while (running.get() && socket === s && !s.isClosed) {
                        val sentAt = SystemClock.elapsedRealtime()
                        sendCommand(Wire.PING) {
                            writer.writeLong(sentAt)
                        }
                        try { Thread.sleep(1000) }
                        catch (_: InterruptedException) { break }
                    }
                }, "NearCast-control-ping").apply { isDaemon = true; start() }
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
                                synchronized(decodeLock) {
                                    videoQueue.clear()
                                    codecConfigs.clear()
                                    configApplied = false
                                    needKeyframe = true
                                }
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
                        Wire.PONG -> {
                            val sentAt = reader.readLong()
                            val rtt = (SystemClock.elapsedRealtime() - sentAt).coerceIn(0, 60_000).toInt()
                            if (previousRttMs > 0) {
                                controlJitterMs.value =
                                    (controlJitterMs.value * 0.8 + abs(rtt - previousRttMs) * 0.2).toInt()
                            }
                            previousRttMs = rtt
                            controlRttMs.value = rtt
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
                failureDetail = e.javaClass.simpleName + ": " +
                    (e.message ?: "连接中断")
                Log.w("NearCastClient", "Session lost (attempt=" + attempt + ")", e)
            } finally {
                if (socket === session && generation.get() == token) {
                    val canRetry = !rejection && attempt < 5 &&
                        running.get() && generation.get() == token
                    disconnectReason.value = failureDetail
                    closeTransport()
                    reconnecting.value = canRetry
                    if (canRetry) {
                        val delayMs = minOf(2500L, 400L * (attempt + 1))
                        status.value = "连接中断：" + failureDetail +
                            "；正在重连 " + (attempt + 1) + "/5"
                        Thread({
                            try { Thread.sleep(delayMs) }
                            catch (_: InterruptedException) { return@Thread }
                            if (generation.get() == token) {
                                connectAttempt(peer, pin, attempt + 1, token)
                            }
                        }, "NearCast-retry").apply { isDaemon = true; start() }
                    } else {
                        status.value = if (rejection) "配对拒绝：" + failureDetail
                            else "连接已断开：" + failureDetail
                    }
                } else {
                    try { session.close() } catch (_: Exception) {}
                }
            }
        }, "NearCast-client-attempt-$attempt").apply { isDaemon = true; start() }
    }

    /** Producer threads never wait for a MediaCodec buffer: UDP/TCP read stays responsive. */
    private fun receiveVideo(flags: Int, pts: Long, data: ByteArray) {
        rxWindowBytes.addAndGet(data.size.toLong())
        if (!running.get()) return
        if (videoQueue.offer(VideoUnit(flags, pts, data))) return
        // A decoder backlog implies loss of temporal reference continuity.
        // Discard the stale GOP and request an IDR rather than accumulate latency.
        videoQueueOverflows.value += 1
        videoQueue.clear()
        needKeyframe = true
        requestSync()
    }

    private fun processVideo(flags: Int, pts: Long, data: ByteArray) {
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
                            // UI state is published once/second, not every
                            // frame: 120 state writes/s otherwise trigger
                            // heavyweight Compose recomposition and GC.
                            totalSubmittedFrames++
                            windowFrames++
                            val now = SystemClock.elapsedRealtime()
                            if (now - frameWindowMs >= 1000) {
                                receivedFps.value = (windowFrames * 1000L /
                                    (now - frameWindowMs).coerceAtLeast(1L)).toInt()
                                decodedFrames.value = totalSubmittedFrames
                                frameWindowMs = now
                                windowFrames = 0
                            }
                        }
                    }
                }
    }


    private fun createDecoderIfReady() {
        synchronized(decodeLock) {
            val dims = dimensions ?: return
            val target = surface ?: return
            videoQueue.clear()
            decoder?.close()
            decoder = try {
                AvcSurfaceDecoder(
                    target, dims.first, dims.second, dims.third,
                    { lowestLatencyRender.value }
                ) {
                    countRenderedOutput()
                }
            } catch (e: Exception) {
                status.value = "解码器不可用：" + (e.message ?: e.javaClass.simpleName)
                null
            }
            needKeyframe = true
            configApplied = false
        }
        requestSync()
    }

    private var renderedWindowMs = SystemClock.elapsedRealtime()
    private var renderedWindowFrames = 0
    private val renderedStatsLock = Any()
    private fun countRenderedOutput() = synchronized(renderedStatsLock) {
        renderedWindowFrames++
        val now = SystemClock.elapsedRealtime()
        if (now - renderedWindowMs >= 1000L) {
            renderedFps.value =
                (renderedWindowFrames * 1000L / (now - renderedWindowMs).coerceAtLeast(1L)).toInt()
            renderedWindowMs = now
            renderedWindowFrames = 0
        }
    }

    fun attachSurface(value: Surface?) {
        synchronized(decodeLock) {
            surface = value
            if (value == null) {
                videoQueue.clear()
                decoder?.close()
                decoder = null
                configApplied = false
                needKeyframe = true
            }
        }
        if (value != null) createDecoderIfReady()
    }

    @Synchronized private fun requestSync() {
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

    /** Callable from Compose UI; sends at most once per finished slider gesture. */
    fun setBitrateLimit(mbps: Int) {
        val requested = mbps.coerceIn(2, 60)
        bitrateLimitMbps.value = requested
        sendCommand(Wire.SET_BITRATE) {
            output?.writeInt(requested)
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
        generation.incrementAndGet() // Explicit user disconnect cancels retries.
        reconnecting.value = false
        closeTransport()
        status.value = "已手动断开"
    }

    private fun closeTransport() {
        running.set(false)
        try { socket?.close() } catch (_: Exception) {}
        socket = null
        output = null
        udpReceiver?.close()
        udpReceiver = null
        udpPort = 0
        synchronized(decodeLock) {
            videoQueue.clear()
            decoder?.close()
            decoder = null
            codecConfigs.clear()
            configApplied = false
            needKeyframe = true
        }
        audio?.close()
        audio = null
        dimensions = null
        receivedFps.value = 0
        renderedFps.value = 0
        controlRttMs.value = 0
        controlJitterMs.value = 0
        previousRttMs = 0
        connected.value = false
    }
}
