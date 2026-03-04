package com.v2rayng.mytv.vpn

import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.util.Log
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.DatagramChannel
import java.nio.channels.SocketChannel
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class Tun2Socks(
    private val vpnService: VpnService,
    private val vpnInterface: ParcelFileDescriptor,
    private val socksPort: Int = 10808
) {
    companion object {
        private const val TAG = "Tun2Socks"
        private const val MTU = 1500
        private const val PROTO_TCP = 6
        private const val PROTO_UDP = 17
        private const val TCP_SYN = 0x02
        private const val TCP_ACK = 0x10
        private const val TCP_FIN = 0x01
        private const val TCP_RST = 0x04
        private const val SOCKS5_VER: Byte = 0x05
    }

    private val running = AtomicBoolean(false)
    private val tcpSessions = ConcurrentHashMap<String, TcpSession>()
    private val tunWriteQueue = LinkedBlockingQueue<ByteArray>(2000)

    fun start() {
        if (running.getAndSet(true)) return
        Log.i(TAG, "Starting tun2socks on SOCKS5 port $socksPort")
        Thread({ readTunLoop() }, "t2s-read").apply { isDaemon = true; start() }
        Thread({ writeTunLoop() }, "t2s-write").apply { isDaemon = true; start() }
        Thread({ relayLoop() }, "t2s-relay").apply { isDaemon = true; start() }
    }

    fun stop() {
        if (!running.getAndSet(false)) return
        Log.i(TAG, "Stopping tun2socks, sessions=${tcpSessions.size}")
        tcpSessions.values.forEach { it.close() }
        tcpSessions.clear()
    }

    private fun readTunLoop() {
        val buf = ByteArray(MTU)
        val fis = FileInputStream(vpnInterface.fileDescriptor)
        var count = 0L
        try {
            while (running.get()) {
                val n = fis.read(buf)
                if (n <= 0) continue
                count++
                if (count <= 3 || count % 5000 == 0L) Log.d(TAG, "TUN packet #$count len=$n")
                handlePacket(buf, n)
            }
        } catch (e: IOException) {
            if (running.get()) Log.e(TAG, "TUN read error: ${e.message}")
        }
    }

    private fun writeTunLoop() {
        val fos = FileOutputStream(vpnInterface.fileDescriptor)
        try {
            while (running.get()) {
                val pkt = tunWriteQueue.poll(100, TimeUnit.MILLISECONDS) ?: continue
                fos.write(pkt)
            }
        } catch (_: InterruptedException) {
        } catch (e: IOException) {
            if (running.get()) Log.e(TAG, "TUN write error: ${e.message}")
        }
    }

    private fun relayLoop() {
        try {
            while (running.get()) {
                Thread.sleep(5)
                val iter = tcpSessions.entries.iterator()
                while (iter.hasNext()) {
                    val (_, session) = iter.next()
                    if (session.closed) { iter.remove(); continue }
                    try { session.readFromProxy() }
                    catch (_: Exception) { session.close(); iter.remove() }
                }
            }
        } catch (_: InterruptedException) {}
    }

    private fun handlePacket(data: ByteArray, len: Int) {
        if (len < 20) return
        val ver = (data[0].toInt() and 0xF0) shr 4
        if (ver != 4) return
        val ihl = (data[0].toInt() and 0x0F) * 4
        val proto = data[9].toInt() and 0xFF
        val srcIp = InetAddress.getByAddress(data.copyOfRange(12, 16))
        val dstIp = InetAddress.getByAddress(data.copyOfRange(16, 20))
        when (proto) {
            PROTO_TCP -> handleTcp(data, len, ihl, srcIp, dstIp)
            PROTO_UDP -> handleUdp(data, len, ihl, srcIp, dstIp)
        }
    }

    private fun handleTcp(data: ByteArray, len: Int, ihl: Int, srcIp: InetAddress, dstIp: InetAddress) {
        if (len < ihl + 20) return
        val o = ihl
        val srcPort = u16(data, o)
        val dstPort = u16(data, o + 2)
        val seq = u32(data, o + 4)
        val ack = u32(data, o + 8)
        val doff = ((data[o + 12].toInt() and 0xF0) shr 4) * 4
        val flags = data[o + 13].toInt() and 0xFF
        val key = "${srcIp.hostAddress}:$srcPort-${dstIp.hostAddress}:$dstPort"
        val payOff = ihl + doff
        val payLen = len - payOff
        when {
            (flags and TCP_SYN) != 0 && (flags and TCP_ACK) == 0 -> {
                tcpSessions[key]?.close()
                val s = TcpSession(srcIp, srcPort, dstIp, dstPort, seq)
                tcpSessions[key] = s
                Thread({
                    if (s.connectSocks5()) { s.sendSynAck() }
                    else { s.sendRst(); tcpSessions.remove(key) }
                }, "tcp-$dstPort").start()
            }
            (flags and TCP_RST) != 0 -> tcpSessions.remove(key)?.close()
            (flags and TCP_FIN) != 0 -> { tcpSessions[key]?.let { it.handleFin(seq); tcpSessions.remove(key) } }
            (flags and TCP_ACK) != 0 -> {
                val s = tcpSessions[key] ?: return
                s.clientAck = ack
                if (payLen > 0) s.sendToProxy(data.copyOfRange(payOff, payOff + payLen), seq)
            }
        }
    }

    private fun handleUdp(data: ByteArray, len: Int, ihl: Int, srcIp: InetAddress, dstIp: InetAddress) {
        if (len < ihl + 8) return
        val o = ihl
        val srcPort = u16(data, o)
        val dstPort = u16(data, o + 2)
        val udpLen = u16(data, o + 4)
        val payOff = o + 8
        val payLen = minOf(udpLen - 8, len - payOff)
        if (payLen <= 0) return
        if (dstPort == 53) {
            Thread({
                try {
                    val ch = DatagramChannel.open()
                    vpnService.protect(ch.socket())
                    ch.socket().soTimeout = 5000
                    ch.send(ByteBuffer.wrap(data, payOff, payLen), InetSocketAddress(dstIp, 53))
                    val resp = ByteBuffer.allocate(MTU)
                    ch.receive(resp); resp.flip()
                    val rd = ByteArray(resp.remaining()); resp.get(rd); ch.close()
                    tunWriteQueue.offer(buildUdp(dstIp, 53, srcIp, srcPort, rd))
                } catch (e: Exception) { Log.w(TAG, "DNS err: ${e.message}") }
            }, "dns").start()
        }
    }

    private fun buildUdp(sIp: InetAddress, sPort: Int, dIp: InetAddress, dPort: Int, payload: ByteArray): ByteArray {
        val total = 28 + payload.size
        val p = ByteArray(total)
        p[0] = 0x45.toByte(); p[2] = (total shr 8).toByte(); p[3] = total.toByte()
        p[6] = 0x40.toByte(); p[8] = 64; p[9] = PROTO_UDP.toByte()
        System.arraycopy(sIp.address, 0, p, 12, 4)
        System.arraycopy(dIp.address, 0, p, 16, 4)
        val cs = ipChecksum(p, 0, 20); p[10] = (cs shr 8).toByte(); p[11] = cs.toByte()
        val ul = 8 + payload.size
        p[20] = (sPort shr 8).toByte(); p[21] = sPort.toByte()
        p[22] = (dPort shr 8).toByte(); p[23] = dPort.toByte()
        p[24] = (ul shr 8).toByte(); p[25] = ul.toByte()
        System.arraycopy(payload, 0, p, 28, payload.size)
        return p
    }

    private fun buildTcp(sIp: InetAddress, sPort: Int, dIp: InetAddress, dPort: Int,
                         seq: Long, ack: Long, flags: Int, payload: ByteArray): ByteArray {
        val tcpHdrLen = 20
        val total = 20 + tcpHdrLen + payload.size
        val p = ByteArray(total)
        p[0] = 0x45.toByte(); p[2] = (total shr 8).toByte(); p[3] = total.toByte()
        p[6] = 0x40.toByte(); p[8] = 64; p[9] = PROTO_TCP.toByte()
        System.arraycopy(sIp.address, 0, p, 12, 4)
        System.arraycopy(dIp.address, 0, p, 16, 4)
        val ipCs = ipChecksum(p, 0, 20); p[10] = (ipCs shr 8).toByte(); p[11] = ipCs.toByte()
        val t = 20
        p[t] = (sPort shr 8).toByte(); p[t + 1] = sPort.toByte()
        p[t + 2] = (dPort shr 8).toByte(); p[t + 3] = dPort.toByte()
        writeU32(p, t + 4, seq); writeU32(p, t + 8, ack)
        p[t + 12] = 0x50.toByte()
        p[t + 13] = flags.toByte()
        p[t + 14] = 0xFF.toByte(); p[t + 15] = 0xFF.toByte()
        if (payload.isNotEmpty()) System.arraycopy(payload, 0, p, t + tcpHdrLen, payload.size)
        val tcpCs = tcpChecksum(sIp.address, dIp.address, p, t, tcpHdrLen + payload.size)
        p[t + 16] = (tcpCs shr 8).toByte(); p[t + 17] = tcpCs.toByte()
        return p
    }

    private fun u16(d: ByteArray, o: Int) = ((d[o].toInt() and 0xFF) shl 8) or (d[o + 1].toInt() and 0xFF)
    private fun u32(d: ByteArray, o: Int): Long =
        ((d[o].toLong() and 0xFF) shl 24) or ((d[o+1].toLong() and 0xFF) shl 16) or
        ((d[o+2].toLong() and 0xFF) shl 8) or (d[o+3].toLong() and 0xFF)
    private fun writeU32(d: ByteArray, o: Int, v: Long) {
        d[o] = (v shr 24).toByte(); d[o+1] = (v shr 16).toByte()
        d[o+2] = (v shr 8).toByte(); d[o+3] = v.toByte()
    }

    private fun ipChecksum(d: ByteArray, off: Int, len: Int): Int {
        var s = 0L; var i = off; var l = len
        while (l > 1) { s += ((d[i].toInt() and 0xFF) shl 8) or (d[i+1].toInt() and 0xFF); i += 2; l -= 2 }
        if (l > 0) s += (d[i].toInt() and 0xFF) shl 8
        while (s shr 16 != 0L) s = (s and 0xFFFF) + (s shr 16)
        return s.toInt().inv() and 0xFFFF
    }

    private fun tcpChecksum(srcIp: ByteArray, dstIp: ByteArray, data: ByteArray, tcpOff: Int, tcpLen: Int): Int {
        var s = 0L
        for (i in 0..1) s += ((srcIp[i*2].toInt() and 0xFF) shl 8) or (srcIp[i*2+1].toInt() and 0xFF)
        for (i in 0..1) s += ((dstIp[i*2].toInt() and 0xFF) shl 8) or (dstIp[i*2+1].toInt() and 0xFF)
        s += PROTO_TCP; s += tcpLen
        val saved16 = data[tcpOff + 16]; val saved17 = data[tcpOff + 17]
        data[tcpOff + 16] = 0; data[tcpOff + 17] = 0
        var i = tcpOff; var l = tcpLen
        while (l > 1) { s += ((data[i].toInt() and 0xFF) shl 8) or (data[i+1].toInt() and 0xFF); i += 2; l -= 2 }
        if (l > 0) s += (data[i].toInt() and 0xFF) shl 8
        data[tcpOff + 16] = saved16; data[tcpOff + 17] = saved17
        while (s shr 16 != 0L) s = (s and 0xFFFF) + (s shr 16)
        return s.toInt().inv() and 0xFFFF
    }

    inner class TcpSession(
        val srcIp: InetAddress, val srcPort: Int,
        val dstIp: InetAddress, val dstPort: Int,
        initialSeq: Long
    ) {
        var closed = false; private set
        private var channel: SocketChannel? = null
        private var mySeq = System.nanoTime() and 0xFFFFFFFFL
        private var clientSeq = initialSeq + 1
        var clientAck = 0L

        fun close() { closed = true; try { channel?.close() } catch (_: Exception) {} }

        fun connectSocks5(): Boolean {
            try {
                val ch = SocketChannel.open()
                ch.configureBlocking(true)
                ch.socket().soTimeout = 10000
                vpnService.protect(ch.socket())
                ch.connect(InetSocketAddress("127.0.0.1", socksPort))
                ch.write(ByteBuffer.wrap(byteArrayOf(SOCKS5_VER, 1, 0)))
                val auth = ByteBuffer.allocate(2); ch.read(auth); auth.flip()
                if (auth.get() != SOCKS5_VER || auth.get() != 0.toByte()) { ch.close(); return false }
                val req = ByteBuffer.allocate(10)
                req.put(SOCKS5_VER); req.put(1); req.put(0); req.put(1)
                req.put(dstIp.address); req.putShort(dstPort.toShort()); req.flip()
                ch.write(req)
                val resp = ByteBuffer.allocate(10); ch.read(resp); resp.flip()
                resp.get(); val rep = resp.get()
                if (rep != 0.toByte()) { ch.close(); return false }
                ch.configureBlocking(false)
                channel = ch
                return true
            } catch (e: Exception) { Log.w(TAG, "SOCKS5 err: ${e.message}"); return false }
        }

        fun sendSynAck() {
            tunWriteQueue.offer(buildTcp(dstIp, dstPort, srcIp, srcPort, mySeq, clientSeq, TCP_SYN or TCP_ACK, ByteArray(0)))
            mySeq++
        }
        fun sendRst() {
            tunWriteQueue.offer(buildTcp(dstIp, dstPort, srcIp, srcPort, mySeq, clientSeq, TCP_RST or TCP_ACK, ByteArray(0)))
        }
        fun sendToProxy(data: ByteArray, seq: Long) {
            val ch = channel ?: return
            try {
                ch.configureBlocking(true); ch.write(ByteBuffer.wrap(data)); ch.configureBlocking(false)
                clientSeq = seq + data.size
                tunWriteQueue.offer(buildTcp(dstIp, dstPort, srcIp, srcPort, mySeq, clientSeq, TCP_ACK, ByteArray(0)))
            } catch (_: Exception) { close() }
        }
        fun readFromProxy() {
            val ch = channel ?: return
            if (!ch.isOpen) return
            val buf = ByteBuffer.allocate(MTU - 40)
            val n = ch.read(buf)
            if (n > 0) {
                buf.flip(); val d = ByteArray(n); buf.get(d)
                tunWriteQueue.offer(buildTcp(dstIp, dstPort, srcIp, srcPort, mySeq, clientSeq, TCP_ACK, d))
                mySeq += n
            } else if (n == -1) {
                tunWriteQueue.offer(buildTcp(dstIp, dstPort, srcIp, srcPort, mySeq, clientSeq, TCP_FIN or TCP_ACK, ByteArray(0)))
                close()
            }
        }
        fun handleFin(seq: Long) {
            clientSeq = seq + 1
            tunWriteQueue.offer(buildTcp(dstIp, dstPort, srcIp, srcPort, mySeq, clientSeq, TCP_FIN or TCP_ACK, ByteArray(0)))
            close()
        }
    }
}