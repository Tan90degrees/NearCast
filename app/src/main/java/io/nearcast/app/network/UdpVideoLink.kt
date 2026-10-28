package io.nearcast.app.network

import android.media.MediaCodec
import android.os.SystemClock
import android.util.Log
import java.io.ByteArrayOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Experimental low-latency LAN datagrams for VIDEO only.
 * No encryption/authentication yet: MUST only be used on a trusted private LAN.
 *
 * Header (26 bytes, network byte order):
 * magic:i32 frameId:i32 flags:i32 ptsUs:i64 fragment:i16 total:i16 length:i16
 * Max UDP packet: 1200 bytes to avoid IP fragmentation.
 */
private const val MAGIC = 0x4E435632
private const val HEADER = 26
private const val PAYLOAD = 1174
private const val MAX_FRAGMENT_COUNT = 2048

class UdpVideoSender {
    private val socket = DatagramSocket(0)
    val localPort: Int get() = socket.localPort
    private var destination: InetSocketAddress? = null
    private var nextId = 0

    @Synchronized fun setDestination(address: InetAddress, port: Int) {
        require(port in 1..65535)
        destination = InetSocketAddress(address, port)
    }

    @Synchronized fun send(flags: Int, ptsUs: Long, bytes: ByteArray) {
        val dst = destination ?: return
        val count = maxOf(1, (bytes.size + PAYLOAD - 1) / PAYLOAD)
        if (count > MAX_FRAGMENT_COUNT) return
        val frameId = nextId++
        for (i in 0 until count) {
            val start = i * PAYLOAD
            val size = minOf(PAYLOAD, bytes.size - start)
            val datagram = ByteBuffer.allocate(HEADER + size).order(ByteOrder.BIG_ENDIAN)
            datagram.putInt(MAGIC)
            datagram.putInt(frameId)
            datagram.putInt(flags)
            datagram.putLong(ptsUs)
            datagram.putShort(i.toShort())
            datagram.putShort(count.toShort())
            datagram.putShort(size.toShort())
            datagram.put(bytes, start, size)
            val pkt = DatagramPacket(datagram.array(), HEADER + size, dst)
            socket.send(pkt)
        }
    }

    fun close() { socket.close() }
}

class UdpVideoReceiver(
    serverAddress: InetAddress,
    serverPort: Int,
    private val onVideo: (Int, Long, ByteArray) -> Unit,
    private val requestKeyframe: () -> Unit
) {
    private data class Assembly(
        val flags: Int,
        val ptsUs: Long,
        val startMs: Long,
        val parts: Array<ByteArray?>,
        var received: Int = 0
    )

    private val socket = DatagramSocket(0).apply {
        connect(serverAddress, serverPort)
        soTimeout = 120
        receiveBufferSize = 512 * 1024
    }
    val localPort: Int get() = socket.localPort
    private val running = AtomicBoolean(true)
    private val pending = HashMap<Int, Assembly>()
    private var lastDelivered = -1
    private var waitForKeyframe = true
    private var lastTraffic = SystemClock.elapsedRealtime()
    private val reader = Thread({ readLoop() }, "NearCast-udp-video").also { it.start() }

    private fun readLoop() {
        val receive = ByteArray(1300)
        while (running.get()) {
            try {
                val packet = DatagramPacket(receive, receive.size)
                socket.receive(packet)
                lastTraffic = SystemClock.elapsedRealtime()
                if (packet.length < HEADER) continue
                val input = ByteBuffer.wrap(packet.data, packet.offset, packet.length)
                    .order(ByteOrder.BIG_ENDIAN)
                if (input.int != MAGIC) continue
                val id = input.int
                val flags = input.int
                val pts = input.long
                val index = input.short.toInt() and 0xffff
                val total = input.short.toInt() and 0xffff
                val size = input.short.toInt() and 0xffff
                if (total !in 1..MAX_FRAGMENT_COUNT || index >= total ||
                    size > PAYLOAD || size != input.remaining()) continue
                val now = SystemClock.elapsedRealtime()
                val expired = pending.filterValues { now - it.startMs > 100 }.keys
                if (expired.isNotEmpty()) {
                    expired.forEach { pending.remove(it) }
                    waitForKeyframe = true
                    requestKeyframe()
                }
                val assembly = pending.getOrPut(id) {
                    Assembly(flags, pts, now, arrayOfNulls(total))
                }
                if (assembly.parts.size != total || assembly.flags != flags || assembly.ptsUs != pts)
                    continue
                if (assembly.parts[index] == null) {
                    val part = ByteArray(size)
                    input.get(part)
                    assembly.parts[index] = part
                    assembly.received++
                }
                // Bound memory under corrupt/reordered datagrams.
                if (pending.size > 12) {
                    pending.clear()
                    waitForKeyframe = true
                    requestKeyframe()
                    continue
                }
                if (assembly.received != total) continue
                pending.remove(id)
                val output = ByteArrayOutputStream(total * PAYLOAD)
                assembly.parts.forEach { output.write(it ?: ByteArray(0)) }
                val bytes = output.toByteArray()
                when {
                    flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0 ->
                        onVideo(flags, pts, bytes)
                    flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0 -> {
                        waitForKeyframe = false
                        lastDelivered = id
                        pending.clear()
                        onVideo(flags, pts, bytes)
                    }
                    waitForKeyframe -> Unit
                    id == lastDelivered + 1 -> {
                        lastDelivered = id
                        onVideo(flags, pts, bytes)
                    }
                    else -> {
                        waitForKeyframe = true
                        requestKeyframe()
                    }
                }
            } catch (_: SocketTimeoutException) {
                if (SystemClock.elapsedRealtime() - lastTraffic > 800) {
                    requestKeyframe()
                    lastTraffic = SystemClock.elapsedRealtime()
                }
            } catch (e: Exception) {
                if (running.get()) Log.w("NearCastUDP", "Receiver stopped", e)
                break
            }
        }
    }

    fun close() {
        if (!running.getAndSet(false)) return
        socket.close()
        pending.clear()
        if (Thread.currentThread() !== reader) {
            try { reader.join(300) }
            catch (_: InterruptedException) { Thread.currentThread().interrupt() }
        }
    }
}
