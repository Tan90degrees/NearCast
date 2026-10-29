package io.nearcast.app.network

import android.media.MediaCodec
import android.os.SystemClock
import android.util.Log
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
private const val FRAGMENTS_PER_PAUSE = 16
private const val PAUSE_NS = 120_000L

class UdpVideoSender {
    private val socket = DatagramSocket(0).apply { sendBufferSize = 512 * 1024 }
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
        // Reuse one packet and buffer per access unit, not one allocation per
        // ~1 KB fragment: at 120 FPS the previous code created thousands of
        // temporary objects per second, causing GC pauses and bursty sending.
        val buffer = ByteArray(HEADER + PAYLOAD)
        val header = ByteBuffer.wrap(buffer).order(ByteOrder.BIG_ENDIAN)
        val packet = DatagramPacket(buffer, buffer.size, dst)
        for (i in 0 until count) {
            val start = i * PAYLOAD
            val size = minOf(PAYLOAD, bytes.size - start)
            header.clear()
            header.putInt(MAGIC)
            header.putInt(frameId)
            header.putInt(flags)
            header.putLong(ptsUs)
            header.putShort(i.toShort())
            header.putShort(count.toShort())
            header.putShort(size.toShort())
            System.arraycopy(bytes, start, buffer, HEADER, size)
            packet.length = HEADER + size
            socket.send(packet)
            // Small capped pacing prevents a single large IDR from
            // overflowing the Wi-Fi chip's short-term Tx descriptor queue.
            // UDP sending has its own thread, so this cannot block control
            // heartbeats or the encoder drain.
            if (count > 32 && i > 0 && i % FRAGMENTS_PER_PAUSE == 0) {
                java.util.concurrent.locks.LockSupport.parkNanos(PAUSE_NS)
            }
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
        receiveBufferSize = 2 * 1024 * 1024
    }
    val localPort: Int get() = socket.localPort
    private val running = AtomicBoolean(true)
    private data class Complete(val flags: Int, val pts: Long,
                                val data: ByteArray, val receivedAtMs: Long)
    private val pending = HashMap<Int, Assembly>()
    private val completed = java.util.TreeMap<Int, Complete>()
    private var lastDelivered = -1
    private var waitForKeyframe = true
    private var lastTraffic = SystemClock.elapsedRealtime()
    private val reader = Thread({ readLoop() }, "NearCast-udp-video").also { it.start() }

    private fun readLoop() {
        val receive = ByteArray(1300)
        val packet = DatagramPacket(receive, receive.size)
        val input = ByteBuffer.wrap(receive).order(ByteOrder.BIG_ENDIAN)
        var packetsUntilCleanup = 0
        while (running.get()) {
            try {
                packet.length = receive.size
                socket.receive(packet)
                lastTraffic = SystemClock.elapsedRealtime()
                if (packet.length < HEADER) continue
                input.clear()
                input.limit(packet.length)
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
                // Scan once per 16 packets, not for every ~1KB fragment.
                // Repeated HashMap traversals can dominate 120FPS streams.
                if (++packetsUntilCleanup >= 16) {
                    packetsUntilCleanup = 0
                    var expired = false
                    val iterator = pending.values.iterator()
                    while (iterator.hasNext()) {
                        if (now - iterator.next().startMs > 100) {
                            iterator.remove()
                            expired = true
                        }
                    }
                    if (expired) {
                        completed.clear()
                        waitForKeyframe = true
                        requestKeyframe()
                    }
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
                    completed.clear()
                    waitForKeyframe = true
                    requestKeyframe()
                    continue
                }
                if (assembly.received != total) continue
                pending.remove(id)
                // One allocation/copy per frame; previously ByteArrayOutputStream
                // allocated another full-sized array during toByteArray().
                val bytes = ByteArray(assembly.parts.sumOf { it?.size ?: 0 })
                var writeAt = 0
                assembly.parts.forEach { part ->
                    if (part != null) {
                        System.arraycopy(part, 0, bytes, writeAt, part.size)
                        writeAt += part.size
                    }
                }
                when {
                    flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0 ->
                        onVideo(flags, pts, bytes)
                    waitForKeyframe -> {
                        if (flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0) {
                            waitForKeyframe = false
                            lastDelivered = id
                            pending.clear()
                            completed.clear()
                            onVideo(flags, pts, bytes)
                        }
                    }
                    id <= lastDelivered -> Unit
                    else -> {
                        // UDP frames may finish out-of-order. Keep a tiny
                        // reorder window instead of resetting the GOP at
                        // the very first reordered packet.
                        completed[id] = Complete(flags, pts, bytes, now)
                        while (true) {
                            val next = completed.remove(lastDelivered + 1) ?: break
                            lastDelivered++
                            onVideo(next.flags, next.pts, next.data)
                        }
                        if (completed.isNotEmpty()) {
                            val oldest = completed.values.first().receivedAtMs
                            if (completed.size > 4 || now - oldest > 40) {
                                completed.clear()
                                pending.clear()
                                waitForKeyframe = true
                                requestKeyframe()
                            }
                        }
                    }
                }
            } catch (_: SocketTimeoutException) {
                // No new frames on a static screen is normal. Do NOT spam
                // IDR every 800ms while idle: that creates unnecessary
                // bandwidth spikes and interrupts decoder frame pacing.
                if (pending.isNotEmpty()) {
                    val now = SystemClock.elapsedRealtime()
                    if (pending.values.any { now - it.startMs > 130 }) {
                        pending.clear()
                        completed.clear()
                        waitForKeyframe = true
                        requestKeyframe()
                    }
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
        completed.clear()
        if (Thread.currentThread() !== reader) {
            try { reader.join(300) }
            catch (_: InterruptedException) { Thread.currentThread().interrupt() }
        }
    }
}
