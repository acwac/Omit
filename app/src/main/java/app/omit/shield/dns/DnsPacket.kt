package app.omit.shield.dns

import java.io.ByteArrayOutputStream

data class ParsedDnsQuery(
    val txId: Int,
    val flags: Int,
    val qname: String,
    val qtype: Int,
    val qclass: Int,
    val questionEndRelOffset: Int
) {
    fun qtypeName(): String = when (qtype) {
        1 -> "A"
        28 -> "AAAA"
        65 -> "HTTPS"
        5 -> "CNAME"
        16 -> "TXT"
        15 -> "MX"
        33 -> "SRV"
        else -> "T$qtype"
    }
}

data class ResponseInspection(
    val rcode: Int,
    val minTtlSec: Int,
    val cnameTargets: List<String>
)

object DnsPacket {

    fun parseQuery(buf: ByteArray, offset: Int, length: Int): ParsedDnsQuery? {
        if (length < 12 || offset < 0 || offset + length > buf.size) return null
        val txId = readU16(buf, offset)
        val flags = readU16(buf, offset + 2)
        if ((flags and 0x8000) != 0) return null
        val qdCount = readU16(buf, offset + 4)
        if (qdCount < 1) return null

        val nameRes = decodeName(buf, offset, length, 12) ?: return null
        val afterName = nameRes.second
        if (afterName + 4 > length) return null

        val qtype = readU16(buf, offset + afterName)
        val qclass = readU16(buf, offset + afterName + 2)
        return ParsedDnsQuery(
            txId = txId,
            flags = flags,
            qname = nameRes.first.lowercase(),
            qtype = qtype,
            qclass = qclass,
            questionEndRelOffset = afterName + 4
        )
    }

    fun buildBlockedResponse(
        queryBuf: ByteArray,
        queryOffset: Int,
        parsed: ParsedDnsQuery,
        nxdomain: Boolean = false,
        ttlSeconds: Int = 300
    ): ByteArray {
        val out = ByteArrayOutputStream(128)
        writeU16(out, parsed.txId)

        val rdBit = parsed.flags and 0x0100
        val rcode = if (nxdomain) 3 else 0
        val respFlags = 0x8000 or rdBit or 0x0080 or rcode
        writeU16(out, respFlags)

        val hasSyntheticIp = !nxdomain && (parsed.qtype == 1 || parsed.qtype == 28)
        writeU16(out, 1)
        writeU16(out, if (hasSyntheticIp) 1 else 0)
        writeU16(out, 0)
        writeU16(out, 0)

        val qSectionLen = parsed.questionEndRelOffset - 12
        out.write(queryBuf, queryOffset + 12, qSectionLen)

        if (hasSyntheticIp) {
            writeU16(out, 0xC00C)
            writeU16(out, parsed.qtype)
            writeU16(out, 1)
            writeU32(out, ttlSeconds.toLong())
            if (parsed.qtype == 1) {
                writeU16(out, 4)
                out.write(ByteArray(4))
            } else {
                writeU16(out, 16)
                out.write(ByteArray(16))
            }
        }
        return out.toByteArray()
    }

    fun buildServFailResponse(
        queryBuf: ByteArray,
        queryOffset: Int,
        parsed: ParsedDnsQuery
    ): ByteArray {
        val out = ByteArrayOutputStream(64)
        writeU16(out, parsed.txId)
        val rdBit = parsed.flags and 0x0100
        writeU16(out, 0x8000 or rdBit or 0x0080 or 2)
        writeU16(out, 1)
        writeU16(out, 0)
        writeU16(out, 0)
        writeU16(out, 0)
        out.write(queryBuf, queryOffset + 12, parsed.questionEndRelOffset - 12)
        return out.toByteArray()
    }

    fun inspectResponse(resp: ByteArray): ResponseInspection? {
        if (resp.size < 12) return null
        val flags = readU16(resp, 2)
        val rcode = flags and 0x000F
        val qdCount = readU16(resp, 4)
        val anCount = readU16(resp, 6)

        var pos = 12
        for (i in 0 until qdCount) {
            val skipped = skipName(resp, 0, resp.size, pos) ?: return null
            pos = skipped + 4
            if (pos > resp.size) return null
        }

        var minTtl = Int.MAX_VALUE
        val cnames = ArrayList<String>(2)
        for (i in 0 until anCount) {
            val afterOwner = skipName(resp, 0, resp.size, pos) ?: break
            if (afterOwner + 10 > resp.size) break
            val rrType = readU16(resp, afterOwner)
            val ttl = readU32(resp, afterOwner + 4).coerceIn(10L, 3600L).toInt()
            if (ttl < minTtl) minTtl = ttl
            val rdLen = readU16(resp, afterOwner + 8)
            val rdataStart = afterOwner + 10
            if (rdataStart + rdLen > resp.size) break

            if (rrType == 5) {
                val target = decodeName(resp, 0, resp.size, rdataStart)?.first
                if (!target.isNullOrEmpty()) {
                    cnames.add(target.lowercase())
                }
            }
            pos = rdataStart + rdLen
        }

        val finalTtl = if (minTtl == Int.MAX_VALUE) 120 else minTtl
        return ResponseInspection(rcode = rcode, minTtlSec = finalTtl, cnameTargets = cnames)
    }

    fun cloneWithTxId(wire: ByteArray, newTxId: Int): ByteArray {
        val copy = wire.copyOf()
        if (copy.size >= 2) {
            copy[0] = ((newTxId ushr 8) and 0xFF).toByte()
            copy[1] = (newTxId and 0xFF).toByte()
        }
        return copy
    }

    private fun decodeName(
        buf: ByteArray,
        baseOffset: Int,
        msgLen: Int,
        startRelPos: Int
    ): Pair<String, Int>? {
        val sb = StringBuilder(64)
        var cur = startRelPos
        var nextAfterFirstJump = -1
        var jumps = 0

        while (true) {
            if (cur < 0 || cur >= msgLen) return null
            val b = buf[baseOffset + cur].toInt() and 0xFF
            if (b == 0) {
                if (nextAfterFirstJump == -1) nextAfterFirstJump = cur + 1
                break
            }
            if ((b and 0xC0) == 0xC0) {
                if (cur + 1 >= msgLen) return null
                val b2 = buf[baseOffset + cur + 1].toInt() and 0xFF
                val ptr = ((b and 0x3F) shl 8) or b2
                if (nextAfterFirstJump == -1) nextAfterFirstJump = cur + 2
                cur = ptr
                jumps++
                if (jumps > 20) return null
            } else if ((b and 0xC0) == 0) {
                val labelLen = b
                cur += 1
                if (cur + labelLen > msgLen) return null
                if (sb.isNotEmpty()) sb.append('.')
                for (i in 0 until labelLen) {
                    sb.append((buf[baseOffset + cur + i].toInt() and 0xFF).toChar())
                }
                cur += labelLen
                if (sb.length > 255) return null
            } else {
                return null
            }
        }
        return Pair(sb.toString(), nextAfterFirstJump)
    }

    private fun skipName(buf: ByteArray, baseOffset: Int, msgLen: Int, startRelPos: Int): Int? {
        var cur = startRelPos
        var steps = 0
        while (cur in 0 until msgLen && steps < 128) {
            val b = buf[baseOffset + cur].toInt() and 0xFF
            if (b == 0) return cur + 1
            if ((b and 0xC0) == 0xC0) {
                return if (cur + 2 <= msgLen) cur + 2 else null
            }
            if ((b and 0xC0) != 0) return null
            cur += 1 + b
            steps++
        }
        return null
    }

    private fun readU16(buf: ByteArray, idx: Int): Int {
        return ((buf[idx].toInt() and 0xFF) shl 8) or (buf[idx + 1].toInt() and 0xFF)
    }

    private fun readU32(buf: ByteArray, idx: Int): Long {
        return ((buf[idx].toLong() and 0xFFL) shl 24) or
            ((buf[idx + 1].toLong() and 0xFFL) shl 16) or
            ((buf[idx + 2].toLong() and 0xFFL) shl 8) or
            (buf[idx + 3].toLong() and 0xFFL)
    }

    private fun writeU16(out: ByteArrayOutputStream, v: Int) {
        out.write((v ushr 8) and 0xFF)
        out.write(v and 0xFF)
    }

    private fun writeU32(out: ByteArrayOutputStream, v: Long) {
        out.write(((v ushr 24) and 0xFFL).toInt())
        out.write(((v ushr 16) and 0xFFL).toInt())
        out.write(((v ushr 8) and 0xFFL).toInt())
        out.write((v and 0xFFL).toInt())
    }
}
