package app.vellum.shield.dns

import java.util.concurrent.atomic.AtomicInteger

data class ParsedTunDnsPacket(
    val ipVersion: Int,
    val srcIp: ByteArray,
    val dstIp: ByteArray,
    val srcPort: Int,
    val dstPort: Int,
    val dnsOffset: Int,
    val dnsLength: Int
)

object IpUdpPacket {
    private val ipIdCounter = AtomicInteger(1000)

    fun parseIncomingUdp53(packet: ByteArray, length: Int): ParsedTunDnsPacket? {
        if (length < 28 || length > packet.size) return null
        val version = (packet[0].toInt() ushr 4) and 0x0F

        if (version == 4) {
            val ihl = (packet[0].toInt() and 0x0F) * 4
            if (ihl < 20 || length < ihl + 8) return null
            val proto = packet[9].toInt() and 0xFF
            if (proto != 17) return null

            val dstPort = readU16(packet, ihl + 2)
            if (dstPort != 53) return null
            val srcPort = readU16(packet, ihl)
            val udpLen = readU16(packet, ihl + 4)
            if (udpLen < 8) return null

            val dnsOffset = ihl + 8
            val dnsLen = minOf(length - dnsOffset, udpLen - 8)
            if (dnsLen < 12) return null

            val srcIp = packet.copyOfRange(12, 16)
            val dstIp = packet.copyOfRange(16, 20)
            return ParsedTunDnsPacket(4, srcIp, dstIp, srcPort, dstPort, dnsOffset, dnsLen)
        } else if (version == 6) {
            if (length < 48) return null
            val nextHeader = packet[6].toInt() and 0xFF
            if (nextHeader != 17) return null

            val dstPort = readU16(packet, 42)
            if (dstPort != 53) return null
            val srcPort = readU16(packet, 40)
            val udpLen = readU16(packet, 44)
            if (udpLen < 8) return null

            val dnsOffset = 48
            val dnsLen = minOf(length - dnsOffset, udpLen - 8)
            if (dnsLen < 12) return null

            val srcIp = packet.copyOfRange(8, 24)
            val dstIp = packet.copyOfRange(24, 40)
            return ParsedTunDnsPacket(6, srcIp, dstIp, srcPort, dstPort, dnsOffset, dnsLen)
        }
        return null
    }

    /**
     * Immediate TCP RST+ACK for opportunistic DoT probes on TCP 853/53 so Android netd
     * never waits 3 seconds on a SYN timeout.
     */
    fun buildTcpRstIfSyn(packet: ByteArray, length: Int): ByteArray? {
        if (length < 40 || length > packet.size) return null
        val version = (packet[0].toInt() ushr 4) and 0x0F
        if (version == 4) {
            val ihl = (packet[0].toInt() and 0x0F) * 4
            if (ihl < 20 || length < ihl + 20) return null
            if ((packet[9].toInt() and 0xFF) != 6) return null
            val flags = packet[ihl + 13].toInt() and 0xFF
            if ((flags and 0x04) != 0) return null
            val srcIp = packet.copyOfRange(12, 16)
            val dstIp = packet.copyOfRange(16, 20)
            val srcPort = readU16(packet, ihl)
            val dstPort = readU16(packet, ihl + 2)
            val seqNum = readU32(packet, ihl + 4)

            val out = ByteArray(40)
            out[0] = 0x45.toByte()
            writeU16(out, 2, 40)
            writeU16(out, 4, ipIdCounter.incrementAndGet() and 0xFFFF)
            writeU16(out, 6, 0x4000)
            out[8] = 64.toByte()
            out[9] = 6.toByte()
            System.arraycopy(dstIp, 0, out, 12, 4)
            System.arraycopy(srcIp, 0, out, 16, 4)
            writeU16(out, 10, ipv4HeaderChecksum(out, 0, 20))

            writeU16(out, 20, dstPort)
            writeU16(out, 22, srcPort)
            writeU32(out, 24, 0L)
            writeU32(out, 28, (seqNum + 1L) and 0xFFFFFFFFL)
            out[32] = 0x50.toByte()
            out[33] = 0x14.toByte()
            val tcpCsum = udpChecksum(dstIp, srcIp, 6, out, 20, 20)
            writeU16(out, 36, tcpCsum)
            return out
        } else if (version == 6) {
            if (length < 60) return null
            if ((packet[6].toInt() and 0xFF) != 6) return null
            val flags = packet[40 + 13].toInt() and 0xFF
            if ((flags and 0x04) != 0) return null
            val srcIp = packet.copyOfRange(8, 24)
            val dstIp = packet.copyOfRange(24, 40)
            val srcPort = readU16(packet, 40)
            val dstPort = readU16(packet, 42)
            val seqNum = readU32(packet, 44)

            val out = ByteArray(60)
            out[0] = 0x60.toByte()
            writeU16(out, 4, 20)
            out[6] = 6.toByte()
            out[7] = 64.toByte()
            System.arraycopy(dstIp, 0, out, 8, 16)
            System.arraycopy(srcIp, 0, out, 24, 16)

            writeU16(out, 40, dstPort)
            writeU16(out, 42, srcPort)
            writeU32(out, 44, 0L)
            writeU32(out, 48, (seqNum + 1L) and 0xFFFFFFFFL)
            out[52] = 0x50.toByte()
            out[53] = 0x14.toByte()
            val tcpCsum = udpChecksum(dstIp, srcIp, 6, out, 40, 20)
            writeU16(out, 56, tcpCsum)
            return out
        }
        return null
    }

    fun buildUdpResponse(req: ParsedTunDnsPacket, dnsPayload: ByteArray): ByteArray {
        return if (req.ipVersion == 4) {
            buildIpv4Udp(req.dstIp, req.srcIp, req.dstPort, req.srcPort, dnsPayload)
        } else {
            buildIpv6Udp(req.dstIp, req.srcIp, req.dstPort, req.srcPort, dnsPayload)
        }
    }

    private fun buildIpv4Udp(
        srcIp: ByteArray,
        dstIp: ByteArray,
        srcPort: Int,
        dstPort: Int,
        payload: ByteArray
    ): ByteArray {
        val udpLen = 8 + payload.size
        val totalLen = 20 + udpLen
        val out = ByteArray(totalLen)

        out[0] = 0x45.toByte()
        out[1] = 0x00.toByte()
        writeU16(out, 2, totalLen)
        writeU16(out, 4, ipIdCounter.incrementAndGet() and 0xFFFF)
        writeU16(out, 6, 0x4000)
        out[8] = 64.toByte()
        out[9] = 17.toByte()
        System.arraycopy(srcIp, 0, out, 12, 4)
        System.arraycopy(dstIp, 0, out, 16, 4)
        writeU16(out, 10, ipv4HeaderChecksum(out, 0, 20))

        writeU16(out, 20, srcPort)
        writeU16(out, 22, dstPort)
        writeU16(out, 24, udpLen)
        System.arraycopy(payload, 0, out, 28, payload.size)
        writeU16(out, 26, udpChecksum(srcIp, dstIp, 17, out, 20, udpLen))
        return out
    }

    private fun buildIpv6Udp(
        srcIp: ByteArray,
        dstIp: ByteArray,
        srcPort: Int,
        dstPort: Int,
        payload: ByteArray
    ): ByteArray {
        val udpLen = 8 + payload.size
        val totalLen = 40 + udpLen
        val out = ByteArray(totalLen)

        out[0] = 0x60.toByte()
        writeU16(out, 4, udpLen)
        out[6] = 17.toByte()
        out[7] = 64.toByte()
        System.arraycopy(srcIp, 0, out, 8, 16)
        System.arraycopy(dstIp, 0, out, 24, 16)

        writeU16(out, 40, srcPort)
        writeU16(out, 42, dstPort)
        writeU16(out, 44, udpLen)
        System.arraycopy(payload, 0, out, 48, payload.size)
        writeU16(out, 46, udpChecksum(srcIp, dstIp, 17, out, 40, udpLen))
        return out
    }

    fun ipv4HeaderChecksum(buf: ByteArray, offset: Int, len: Int): Int {
        var sum = 0L
        var i = 0
        while (i + 1 < len) {
            sum += readU16(buf, offset + i).toLong()
            i += 2
        }
        if (i < len) sum += ((buf[offset + i].toInt() and 0xFF) shl 8).toLong()
        while ((sum ushr 16) != 0L) sum = (sum and 0xFFFFL) + (sum ushr 16)
        return (sum.inv() and 0xFFFFL).toInt()
    }

    fun udpChecksum(
        srcIp: ByteArray,
        dstIp: ByteArray,
        proto: Int,
        packet: ByteArray,
        udpOffset: Int,
        udpLen: Int
    ): Int {
        var sum = 0L
        var i = 0
        while (i < srcIp.size) { sum += readU16(srcIp, i).toLong(); i += 2 }
        i = 0
        while (i < dstIp.size) { sum += readU16(dstIp, i).toLong(); i += 2 }
        sum += proto.toLong()
        sum += udpLen.toLong()

        i = 0
        while (i + 1 < udpLen) {
            sum += readU16(packet, udpOffset + i).toLong()
            i += 2
        }
        if (i < udpLen) sum += ((packet[udpOffset + i].toInt() and 0xFF) shl 8).toLong()
        while ((sum ushr 16) != 0L) sum = (sum and 0xFFFFL) + (sum ushr 16)
        val res = (sum.inv() and 0xFFFFL).toInt()
        return if (res == 0) 0xFFFF else res
    }

    private fun readU16(buf: ByteArray, idx: Int): Int {
        return ((buf[idx].toInt() and 0xFF) shl 8) or (buf[idx + 1].toInt() and 0xFF)
    }

    private fun writeU16(buf: ByteArray, idx: Int, v: Int) {
        buf[idx] = ((v ushr 8) and 0xFF).toByte()
        buf[idx + 1] = (v and 0xFF).toByte()
    }

    private fun readU32(buf: ByteArray, idx: Int): Long {
        return ((buf[idx].toLong() and 0xFFL) shl 24) or
            ((buf[idx + 1].toLong() and 0xFFL) shl 16) or
            ((buf[idx + 2].toLong() and 0xFFL) shl 8) or
            (buf[idx + 3].toLong() and 0xFFL)
    }

    private fun writeU32(buf: ByteArray, idx: Int, v: Long) {
        buf[idx] = ((v ushr 24) and 0xFFL).toByte()
        buf[idx + 1] = ((v ushr 16) and 0xFFL).toByte()
        buf[idx + 2] = ((v ushr 8) and 0xFFL).toByte()
        buf[idx + 3] = (v and 0xFFL).toByte()
    }
}
