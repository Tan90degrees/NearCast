package io.nearcast.app.network

import android.content.Context
import android.view.Surface
import io.nearcast.app.capture.CaptureService
import io.nearcast.app.input.RemoteInputService
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * v0.1 local prototype framing. The 6-digit PIN verifies intent but THIS IS
 * NOT an encrypted transport. Do not use on untrusted LANs.
 *   1: video dimensions (width,height)
 *   2: AVC access unit (flags,ptsUs,size,bytes)
 *   3: remote input (action,x1,y1,x2,y2,durationMs)
 */
object Wire {
    const val MAGIC = "NEARCAST/1"
    const val SIZE = 1
    const val VIDEO = 2
    const val INPUT = 3
    const val TAP = 1
    const val SWIPE = 2
    const val BACK = 3
    const val HOME = 4
    const val RECENTS = 5
    const val MAX_FRAME = 4 * 1024 * 1024
}

class HostServer(private val context: Context) {
    private val random = SecureRandom()
    val pin: String = (100000 + random.nextInt(900000)).toString()
    private val server = ServerSocket(0)
    val port: Int = server.localPort
    val connected = MutableStateFlow(false)
    val status = MutableStateFlow("等待同一 Wi-Fi 下的设备连接")
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
            socket.soTimeout = 5000
            val input = DataInputStream(socket.getInputStream())
            val output = DataOutputStream(socket.getOutputStream())
            val magic = input.readUTF()
            val supplied = input.readUTF()
            val accepted = magic == Wire.MAGIC && MessageDigest.isEqual(
                supplied.toByteArray(Charsets.UTF_8), pin.toByteArray(Charsets.UTF_8)
            ) && current == null
            output.writeBoolean(accepted)
            output.flush()
            if (!accepted) {
                Thread.sleep(350)
                socket.close()
                return
            }
            val link = HostLink(socket, output)
            current = link
            connected.value = true
            status.value = "已配对 · 请点击共享屏幕"
            socket.soTimeout = 0
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
                            if (link === current) {
                                RemoteInputService.dispatch(action, x1, y1, x2, y2, duration)
                            }
                        }
                        else -> throw IllegalArgumentException("Unknown control packet")
                    }
                }
            } finally {
                if (current === link) {
                    current = null
                    connected.value = false
                    status.value = "控制端已断开"
                    context.startService(CaptureService.stopIntent(context))
                }
                link.close()
            }
        } catch (_: Exception) {
            try { socket.close() } catch (_: Exception) {}
        }
    }

    fun sendSize(width: Int, height: Int) = current?.sendSize(width, height) ?: Unit
    fun sendFrame(flags: Int, ptsUs: Long, data: ByteArray) =
        current?.sendFrame(flags, ptsUs, data) ?: Unit

    /** Close the socket so the receiving thread observes EOF and ends capture. */
    fun disconnectActivePeer() {
        current?.close()
    }

    fun close() {
        running.set(false)
        current?.close()
        current = null
        try { server.close() } catch (_: Exception) {}
    }

    private class HostLink(val socket: Socket, private val output: DataOutputStream) {
        private val lock = Any()
        fun sendSize(width: Int, height: Int) = synchronized(lock) {
            output.writeByte(Wire.SIZE)
            output.writeInt(width)
            output.writeInt(height)
            output.flush()
        }
        fun sendFrame(flags: Int, ptsUs: Long, data: ByteArray) = synchronized(lock) {
            if (data.size > Wire.MAX_FRAME || socket.isClosed) return@synchronized
            output.writeByte(Wire.VIDEO)
            output.writeInt(flags)
            output.writeLong(ptsUs)
            output.writeInt(data.size)
            output.write(data)
        }
        fun close() { try { socket.close() } catch (_: Exception) {} }
    }
}

class RemoteClient {
    val connected = MutableStateFlow(false)
    val status = MutableStateFlow("未连接")
    val videoSize = MutableStateFlow(Pair(9, 16))
    private val running = AtomicBoolean(false)
    @Volatile private var socket: Socket? = null
    @Volatile private var output: DataOutputStream? = null
    @Volatile private var decoder: AvcSurfaceDecoder? = null
    @Volatile private var surface: Surface? = null
    @Volatile private var dimensions: Pair<Int, Int>? = null
    private val sendLock = Any()
    // RemoteViewer invokes input() on the Compose main thread.
    // All Socket writes belong on this one dedicated I/O executor.
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
                if (!reader.readBoolean()) throw IllegalStateException("配对码错误或设备忙碌")
                s.soTimeout = 0
                output = writer
                connected.value = true
                status.value = "已连接 · 等待对方开始共享"
                while (running.get()) {
                    when (reader.readUnsignedByte()) {
                        Wire.SIZE -> {
                            val w = reader.readInt()
                            val h = reader.readInt()
                            if (w !in 128..4096 || h !in 128..4096) break
                            dimensions = w to h
                            videoSize.value = w to h
                            status.value = "正在共享屏幕"
                            createDecoderIfReady()
                        }
                        Wire.VIDEO -> {
                            val flags = reader.readInt()
                            val pts = reader.readLong()
                            val len = reader.readInt()
                            if (len !in 0..Wire.MAX_FRAME) break
                            val data = ByteArray(len)
                            reader.readFully(data)
                            decoder?.queue(flags, pts, data)
                        }
                        else -> break
                    }
                }
            } catch (e: Exception) {
                if (running.get()) status.value = e.message ?: "连接中断"
            } finally {
                close()
            }
        }, "NearCast-client").start()
    }

    @Synchronized
    private fun createDecoderIfReady() {
        val size = dimensions ?: return
        val target = surface ?: return
        decoder?.close()
        decoder = try {
            AvcSurfaceDecoder(target, size.first, size.second)
        } catch (e: Exception) {
            status.value = "解码器不可用：" + (e.message ?: "未知错误")
            null
        }
    }

    fun attachSurface(value: Surface?) {
        surface = value
        if (value == null) {
            decoder?.close()
            decoder = null
        } else createDecoderIfReady()
    }

    fun input(action: Int, x1: Float = 0f, y1: Float = 0f,
              x2: Float = 0f, y2: Float = 0f, duration: Int = 0) {
        if (!connected.value) return
        // Bind this command to the session that produced it; queued events
        // must never be delivered to a newly-connected device.
        val targetSocket = socket ?: return
        val writer = output ?: return
        inputWriter.execute {
            if (!connected.value || socket !== targetSocket || output !== writer) return@execute
            try {
                synchronized(sendLock) {
                    if (socket !== targetSocket || output !== writer) return@synchronized
                    writer.writeByte(Wire.INPUT)
                    writer.writeInt(action)
                    writer.writeFloat(x1.coerceIn(0f, 1f))
                    writer.writeFloat(y1.coerceIn(0f, 1f))
                    writer.writeFloat(x2.coerceIn(0f, 1f))
                    writer.writeFloat(y2.coerceIn(0f, 1f))
                    writer.writeInt(duration.coerceIn(0, 10000))
                    writer.flush()
                }
            } catch (_: Exception) {
                // Only tear down the connection that this command belongs to.
                if (socket === targetSocket) close()
            }
        }
    }

    fun close() {
        running.set(false)
        try { socket?.close() } catch (_: Exception) {}
        socket = null
        output = null
        decoder?.close()
        decoder = null
        connected.value = false
    }
}
