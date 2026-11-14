package app.vellum.shield.dns

import android.content.Context
import app.vellum.shield.data.FilterMetadata
import app.vellum.shield.data.VellumPrefs
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.BufferedReader
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.util.Arrays

enum class VerdictType {
    ALLOWED,
    BLOCKED_STANDARD,
    BLOCKED_CANARY_NXDOMAIN
}

data class BlockVerdict(
    val type: VerdictType,
    val matchedMask: Int = 0,
    val reason: String = ""
) {
    val isBlocked: Boolean get() = type != VerdictType.ALLOWED
}

object BlockEngine {
    class CompiledTable(val hashes: LongArray, val masks: ByteArray)

    @Volatile
    private var activeTable: CompiledTable = CompiledTable(LongArray(0), ByteArray(0))

    private const val ACTIVE_FILE = "vellum_active_v2.vbin"
    private const val STARTER_ASSET = "vellum_starter.vbin"

    private val ESSENTIAL_SAFE = setOf(
        "localhost", "google.com", "www.google.com", "google.co.in", "www.google.co.in",
        "accounts.google.com", "dns.google", "googleapis.com", "gstatic.com",
        "googleusercontent.com", "googlevideo.com", "youtube.com", "www.youtube.com",
        "ytimg.com", "play.google.com", "android.com", "mtalk.google.com",
        "fcm.googleapis.com", "cloudflare.com", "cloudflare-dns.com",
        "whatsapp.com", "whatsapp.net", "instagram.com", "cdninstagram.com",
        "facebook.com", "fbcdn.net", "spotify.com", "api.spotify.com", "scdn.co",
        "amazon.in", "amazon.com", "flipkart.com", "paytm.com", "phonepe.com",
        "razorpay.com", "billdesk.com", "npci.org.in", "connectivitycheck.gstatic.com"
    )

    fun installTableFromStream(rawIn: InputStream): Int {
        val loaded = loadFromStream(rawIn)
        activeTable = loaded
        return loaded.hashes.size
    }

    fun ensureLoaded(context: Context) {
        if (activeTable.hashes.isNotEmpty()) return
        synchronized(this) {
            if (activeTable.hashes.isNotEmpty()) return
            val customFile = File(context.filesDir, ACTIVE_FILE)
            val loaded = if (customFile.exists() && customFile.length() > 32L) {
                try {
                    FileInputStream(customFile).use { loadFromStream(it) }
                } catch (_: Throwable) {
                    null
                }
            } else null

            val finalTable = loaded ?: context.assets.open(STARTER_ASSET).use { loadFromStream(it) }
            activeTable = finalTable
            if (VellumPrefs.compiledRuleCount != finalTable.hashes.size) {
                VellumPrefs.updateSyncMetadata(finalTable.hashes.size)
            }
        }
    }

    fun loadFromStream(rawIn: InputStream): CompiledTable {
        val dis = DataInputStream(BufferedInputStream(rawIn, 64 * 1024))
        val magic = ByteArray(8)
        dis.readFully(magic)
        val magicStr = String(magic, Charsets.US_ASCII)
        require(magicStr == "VELLUM01") { "Invalid vbin header: $magicStr" }
        val count = dis.readInt()
        val version = dis.readInt()
        require(count in 0..3_000_000 && version >= 1) { "Invalid vbin count=$count" }

        val hashes = LongArray(count)
        val chunkBytes = ByteArray(8192)
        val bb = ByteBuffer.wrap(chunkBytes)
        var idx = 0
        while (idx < count) {
            val batch = minOf(count - idx, 1024)
            val byteLen = batch * 8
            dis.readFully(chunkBytes, 0, byteLen)
            bb.position(0)
            bb.limit(byteLen)
            for (i in 0 until batch) {
                hashes[idx++] = bb.long
            }
        }

        val masks = ByteArray(count)
        dis.readFully(masks)
        return CompiledTable(hashes, masks)
    }

    /**
     * Allocation-free 64-bit FNV-1a + MurmurHash3 fmix64 matching compile_blocklists.py.
     */
    fun hash64(domain: CharSequence, startIdx: Int = 0, endIdx: Int = domain.length): Long {
        var h: Long = -0x340d631b7bdddcdbL
        val prime: Long = 0x100000001b3L
        for (i in startIdx until endIdx) {
            var ch = domain[i].code
            if (ch in 65..90) ch += 32
            h = (h xor (ch.toLong() and 0xFFL)) * prime
        }
        h = h xor (h ushr 33)
        h *= -0xae502812aa7333L
        h = h xor (h ushr 33)
        h *= -0x3b314601e57a13adL
        h = h xor (h ushr 33)
        return h
    }

    fun checkDomain(
        rawDomain: String,
        activeMask: Int = VellumPrefs.activeCategoryMask.get(),
        allowSet: Set<String> = VellumPrefs.customAllowDomains,
        blockSet: Set<String> = VellumPrefs.customBlockDomains,
        blockDohCanary: Boolean = VellumPrefs.blockDohCanary
    ): BlockVerdict {
        val domain = rawDomain.trim('.').lowercase()
        if (domain.isEmpty() || !domain.contains('.')) {
            return BlockVerdict(VerdictType.ALLOWED)
        }

        // 1. User Custom Allowlist (exact + subdomain suffix match)
        if (allowSet.isNotEmpty() && matchesSuffixSet(domain, allowSet)) {
            return BlockVerdict(VerdictType.ALLOWED, 0, "Custom Allow")
        }

        // 2. Browser Private-DNS / DoH Canary domains
        if (blockDohCanary && (domain == "use-application-dns.net" ||
                domain == "mask.icloud.com" || domain == "mask-h2.icloud.com")
        ) {
            return BlockVerdict(VerdictType.BLOCKED_CANARY_NXDOMAIN, 0, "Browser DNS Guard")
        }

        // 3. User Custom Blocklist
        if (blockSet.isNotEmpty() && matchesSuffixSet(domain, blockSet)) {
            return BlockVerdict(VerdictType.BLOCKED_STANDARD, 0x100, "Custom Rule")
        }

        // 4. Essential safety guard
        if (ESSENTIAL_SAFE.contains(domain)) {
            return BlockVerdict(VerdictType.ALLOWED)
        }

        // 5. Suffix walk across sorted binary table (< 0.2 microseconds, 0 heap allocations)
        if (activeMask == 0) {
            return BlockVerdict(VerdictType.ALLOWED)
        }

        val table = activeTable
        val hashes = table.hashes
        if (hashes.isEmpty()) return BlockVerdict(VerdictType.ALLOWED)
        val masks = table.masks

        var cursor = 0
        val len = domain.length
        while (cursor < len) {
            val nextDot = domain.indexOf('.', cursor)
            if (nextDot < 0) break

            val h = hash64(domain, cursor, len)
            val idx = Arrays.binarySearch(hashes, h)
            if (idx >= 0) {
                val entryMask = masks[idx].toInt() and 0xFF
                val hit = entryMask and activeMask
                if (hit != 0) {
                    return BlockVerdict(
                        type = VerdictType.BLOCKED_STANDARD,
                        matchedMask = hit,
                        reason = FilterMetadata.categoryLabelForMask(hit)
                    )
                }
            }
            cursor = nextDot + 1
        }

        return BlockVerdict(VerdictType.ALLOWED)
    }

    private fun matchesSuffixSet(domain: String, ruleSet: Set<String>): Boolean {
        var cur = 0
        val len = domain.length
        while (cur < len) {
            val nextDot = domain.indexOf('.', cur)
            if (nextDot < 0) break
            if (ruleSet.contains(domain.substring(cur))) return true
            cur = nextDot + 1
        }
        return false
    }

    fun getRuleCount(): Int = activeTable.hashes.size

    fun syncOnlineLists(context: Context): Result<Int> {
        return try {
            ensureLoaded(context)
            val starter = context.assets.open(STARTER_ASSET).use { loadFromStream(it) }
            val merged = HashMap<Long, Int>(starter.hashes.size + 48000)
            for (i in starter.hashes.indices) {
                merged[starter.hashes[i]] = starter.masks[i].toInt() and 0xFF
            }

            val liveSources = mutableListOf(
                Pair("https://small.oisd.nl/domainswild2", FilterMetadata.BIT_GAME_ADS or FilterMetadata.BIT_WEB_ADS or FilterMetadata.BIT_TRACKERS),
                Pair("https://adaway.org/hosts.txt", FilterMetadata.BIT_GAME_ADS),
                Pair("https://pgl.yoyo.org/adservers/serverlist.php?hostformat=nohtml&showintro=0", FilterMetadata.BIT_WEB_ADS)
            )
            for (customUrl in VellumPrefs.customListUrls) {
                liveSources.add(Pair(customUrl, FilterMetadata.BIT_GAME_ADS or FilterMetadata.BIT_WEB_ADS))
            }

            for ((url, catBit) in liveSources) {
                try {
                    val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                        connectTimeout = 8000
                        readTimeout = 14000
                        setRequestProperty("User-Agent", "Vellum-Android/1.1")
                    }
                    if (conn.responseCode in 200..299) {
                        BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8)).use { reader ->
                            var line: String?
                            var count = 0
                            while (reader.readLine().also { line = it } != null && count < 75000) {
                                val dom = parseHostLine(line!!)
                                if (dom != null && !ESSENTIAL_SAFE.contains(dom)) {
                                    val h = hash64(dom)
                                    val prev = merged[h] ?: 0
                                    merged[h] = prev or catBit
                                    count++
                                }
                            }
                        }
                    }
                    conn.disconnect()
                } catch (_: Throwable) {}
            }

            val finalCount = merged.size
            val newHashes = LongArray(finalCount)
            var p = 0
            for (k in merged.keys) newHashes[p++] = k
            Arrays.sort(newHashes)
            val newMasks = ByteArray(finalCount)
            for (i in 0 until finalCount) {
                newMasks[i] = ((merged[newHashes[i]] ?: 0) and 0xFF).toByte()
            }

            val tmpFile = File(context.filesDir, "$ACTIVE_FILE.tmp")
            val dstFile = File(context.filesDir, ACTIVE_FILE)
            DataOutputStream(BufferedOutputStream(FileOutputStream(tmpFile), 64 * 1024)).use { dos ->
                dos.write("VELLUM01".toByteArray(Charsets.US_ASCII))
                dos.writeInt(finalCount)
                dos.writeInt(1)
                for (h in newHashes) dos.writeLong(h)
                dos.write(newMasks)
            }
            if (dstFile.exists()) dstFile.delete()
            tmpFile.renameTo(dstFile)

            activeTable = CompiledTable(newHashes, newMasks)
            VellumPrefs.updateSyncMetadata(finalCount)
            Result.success(finalCount)
        } catch (t: Throwable) {
            Result.failure(t)
        }
    }

    private fun parseHostLine(raw: String): String? {
        var s = raw.trim().lowercase()
        if (s.isEmpty() || s.startsWith("#") || s.startsWith("!") || s.startsWith("[") || s.startsWith("@@")) return null
        val hashIdx = s.indexOf('#')
        if (hashIdx >= 0) s = s.substring(0, hashIdx).trim()
        if (s.startsWith("||") && s.contains("^")) {
            val d = s.substring(2, s.indexOf('^')).trim('.')
            return if (isValidDomain(d)) d else null
        }
        if (s.startsWith("*.")) {
            val d = s.substring(2).trim('.')
            return if (isValidDomain(d)) d else null
        }
        val parts = s.split(Regex("\\s+"))
        val candidate = when {
            parts.size >= 2 && (parts[0] == "0.0.0.0" || parts[0] == "127.0.0.1" || parts[0] == "::") -> parts[1].trim('.')
            parts.size == 1 -> parts[0].trim('.')
            else -> return null
        }
        return if (isValidDomain(candidate)) candidate else null
    }

    private fun isValidDomain(d: String): Boolean {
        if (d.length < 4 || d.length > 250 || !d.contains('.')) return false
        for (ch in d) {
            if (!(ch in 'a'..'z' || ch in '0'..'9' || ch == '.' || ch == '-')) return false
        }
        return !d.startsWith('.') && !d.endsWith('.') && !d.contains("..")
    }
}
