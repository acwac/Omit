package app.omit.shield.net

import app.omit.shield.dns.BlockEngine
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.util.Locale

/**
 * Wi-Fi sharing: other devices on the LAN send traffic through this phone and
 * get the same filtering as the VPN.
 *
 * Only an HTTP proxy is served, on [activePort], plus a PAC file. DNS sharing
 * is deliberately not offered: an unprivileged Android app cannot bind port
 * 53, and Android's Private DNS needs DoT, so advertising it would not work.
 * The phone's own DNS goes through the VPN, so devices must point at this
 * phone's LAN address; a device resolving through its router bypasses this.
 *
 * Correctness details that decide whether this works at all:
 *  - A proxy receives `GET http://host/path HTTP/1.1` and must forward
 *    `GET /path HTTP/1.1`. Forwarding the absolute URI verbatim makes most
 *    servers answer 400.
 *  - Hop-by-hop headers (`Proxy-Connection`, `Connection`, ...) describe this
 *    hop only and must not reach the origin server.
 *  - The LAN address must be found on 172.16/12, 100.64/10 and hotspot
 *    interfaces too, not just 192.168/10 ranges.
 */
object HomeShieldServer {
    private val running = java.util.concurrent.atomic.AtomicBoolean(false)
    val blockedCount = java.util.concurrent.atomic.AtomicLong(0)

    @Volatile var activePort: Int = 8080
        private set
    @Volatile private var boundIp: String = ""

    private var serverSocket: ServerSocket? = null

    // Daemon threads so a stopped server never keeps the process alive.
    private val pool: java.util.concurrent.ExecutorService =
        java.util.concurrent.Executors.newCachedThreadPool { r ->
            Thread(r, "omit-homeshield").apply { isDaemon = true }
        }

    private val HOP_BY_HOP = setOf(
        "proxy-connection",
        "connection",
        "keep-alive",
        "proxy-authenticate",
        "proxy-authorization",
        "te",
        "trailer",
        "transfer-encoding",
        "upgrade",
    )

    /**
     * The address other devices should use. Prefers the wireless interface and
     * never returns loopback when a real LAN address exists.
     */
    fun getLanIp(): String {
        if (boundIp.isNotEmpty()) return boundIp
        return try {
            val ifaces = NetworkInterface.getNetworkInterfaces() ?: return "127.0.0.1"
            var fallback = ""
            while (ifaces.hasMoreElements()) {
                val iface = ifaces.nextElement()
                if (!iface.isUp || iface.isLoopback) continue
                val name = iface.name.lowercase(Locale.US)
                // Skip tunnel and cellular interfaces: the shown IP must be the
                // one a laptop on the same Wi-Fi can reach.
                if (name.startsWith("tun") || name.startsWith("ppp") || name.contains("rmnet")) continue
                val addrs = iface.inetAddresses
                while (addrs.hasMoreElements()) {
                    val addr = addrs.nextElement()
                    if (addr is Inet4Address && !addr.isLoopbackAddress && !addr.isLinkLocalAddress) {
                        val ip = addr.hostAddress ?: continue
                        if (isPrivateV4(ip)) {
                            if (name.contains("wlan") || name.contains("wifi") || name.contains("eth")) return ip
                            if (fallback.isEmpty()) fallback = ip
                        }
                    }
                }
            }
            if (fallback.isNotEmpty()) fallback else "127.0.0.1"
        } catch (_: Throwable) {
            "127.0.0.1"
        }
    }

    /** RFC1918, link-local and CGNAT ranges a phone can legitimately be on. */
    private fun isPrivateV4(ip: String): Boolean {
        val parts = ip.split(".")
        if (parts.size != 4) return false
        val a = parts[0].toIntOrNull() ?: return false
        val b = parts[1].toIntOrNull() ?: return false
        if (a == 10) return true
        if (a == 192 && b == 168) return true
        if (a == 172 && b in 16..31) return true
        if (a == 169 && b == 254) return true
        if (a == 100 && b in 64..127) return true
        return false
    }

    val isRunning: Boolean get() = running.get() && serverSocket != null

    /**
     * Binds synchronously so the UI can read [activePort] the moment the toggle
     * returns, then starts DNS and accepts connections in the background.
     */
    fun start(): Boolean {
        if (isRunning) return true
        val candidateIp = getLanIp()

        fun bindOn(port: Int): ServerSocket? = try {
            if (candidateIp == "127.0.0.1") {
                ServerSocket().apply {
                    reuseAddress = true
                    bind(InetSocketAddress(port))
                }
            } else {
                ServerSocket().apply {
                    reuseAddress = true
                    bind(InetSocketAddress(candidateIp, port))
                }
            }
        } catch (_: Throwable) {
            try {
                ServerSocket().apply {
                    reuseAddress = true
                    bind(InetSocketAddress(port))
                }
            } catch (_: Throwable) {
                null
            }
        }

        var bound = bindOn(8080)
        if (bound != null) {
            activePort = 8080
        } else {
            bound = bindOn(8088)
            if (bound != null) activePort = 8088
        }
        if (bound == null) {
            running.set(false)
            serverSocket = null
            return false
        }
        boundIp = candidateIp
        serverSocket = bound
        running.set(true)

        pool.execute {
            while (running.get()) {
                try {
                    val client = bound.accept()
                    client.tcpNoDelay = true
                    pool.execute { handleClient(client) }
                } catch (_: Throwable) {
                    if (!running.get()) break
                }
            }
        }
        return true
    }

    fun stop() {
        running.set(false)
        try {
            serverSocket?.close()
        } catch (_: Throwable) {
        }
        serverSocket = null
        boundIp = ""
    }

    private fun readHeaderBytes(input: InputStream): Pair<ByteArray, ByteArray>? {
        val buf = ByteArrayOutputStream()
        val tmp = ByteArray(1024)
        while (buf.size() < 32768) {
            val n = input.read(tmp)
            if (n <= 0) break
            buf.write(tmp, 0, n)
            val data = buf.toByteArray()
            val idx = indexOfHeaderEnd(data)
            if (idx >= 0) {
                return data.copyOfRange(0, idx) to data.copyOfRange(idx, data.size)
            }
        }
        return null
    }

    private fun indexOfHeaderEnd(data: ByteArray): Int {
        for (i in 0 until data.size - 3) {
            if (data[i] == 13.toByte() && data[i + 1] == 10.toByte() &&
                data[i + 2] == 13.toByte() && data[i + 3] == 10.toByte()
            ) {
                return i + 4
            }
        }
        return -1
    }

    /** Rewrites an absolute-URI request line into origin-form. */
    private fun rewriteToOriginForm(header: String): String {
        val split = header.indexOf("\r\n")
        if (split < 0) return header
        val requestLine = header.substring(0, split)
        val rest = header.substring(split).replace("\r\n", "\n")
        val parts = requestLine.split(" ")
        if (parts.size < 3) return header
        val method = parts[0]
        val target = parts[1]
        val version = parts[2]
        val originPath: String = try {
            if (!target.startsWith("http://", true) && !target.startsWith("https://", true)) {
                target
            } else {
                val uri = java.net.URI(target)
                val path = uri.rawPath?.takeIf { it.isNotEmpty() } ?: "/"
                val query = uri.rawQuery
                if (query.isNullOrEmpty()) path else "$path?$query"
            }
        } catch (_: Throwable) {
            target
        }
        val lines = rest.split("\n")
        val rebuilt = StringBuilder()
        rebuilt.append(method).append(' ').append(originPath).append(' ').append(version).append("\r\n")
        for (i in 1 until lines.size) {
            val line = lines[i]
            if (line.isEmpty()) continue
            val name = line.substringBefore(':').trim().lowercase(Locale.US)
            if (HOP_BY_HOP.contains(name)) continue
            rebuilt.append(line).append("\r\n")
        }
        rebuilt.append("\r\n")
        return rebuilt.toString()
    }

    /** Strips headers that describe the proxy hop before a tunnel is opened. */
    private fun rewriteConnectHeaders(header: String): String {
        val split = header.indexOf("\r\n")
        if (split < 0) return header
        val requestLine = header.substring(0, split)
        val rest = header.substring(split).replace("\r\n", "\n")
        val lines = rest.split("\n")
        val rebuilt = StringBuilder(requestLine).append("\r\n")
        for (i in 1 until lines.size) {
            val line = lines[i]
            if (line.isEmpty()) continue
            val name = line.substringBefore(':').trim().lowercase(Locale.US)
            if (HOP_BY_HOP.contains(name)) continue
            rebuilt.append(line).append("\r\n")
        }
        rebuilt.append("\r\n")
        return rebuilt.toString()
    }

    private fun hostOf(target: String): Pair<String, Int> {
        return try {
            val uri = java.net.URI(target)
            val scheme = uri.scheme?.lowercase(Locale.US)
            val port = if (uri.port > 0) uri.port else if (scheme == "https") 443 else 80
            (uri.host?.lowercase(Locale.US) ?: "") to port
        } catch (_: Throwable) {
            if (target.contains("://")) "" to 80 else target.substringBefore(":") to 80
        }
    }

    private fun handleClient(client: Socket) {
        client.use { sock ->
            try {
                val input = sock.getInputStream()
                val (headerBytes, leftover) = readHeaderBytes(input) ?: return
                val headerStr = String(headerBytes, Charsets.ISO_8859_1)
                val reqLine = headerStr.substringBefore("\r\n")
                val parts = reqLine.split(" ")
                if (parts.size < 2) return
                val method = parts[0]
                val target = parts[1]

                if (method == "GET" && target.endsWith("/omit.pac")) {
                    val lanIp = getLanIp()
                    val pac = "function FindProxyForURL(url, host) { return \"PROXY $lanIp:$activePort; DIRECT\"; }"
                    val resp = "HTTP/1.1 200 OK\r\n" +
                        "Content-Type: application/x-ns-proxy-autoconfig\r\n" +
                        "Cache-Control: no-store\r\n" +
                        "Content-Length: ${pac.length}\r\n" +
                        "Connection: close\r\n\r\n$pac"
                    sock.getOutputStream().write(resp.toByteArray())
                    return
                }

                if (method == "CONNECT") {
                    val host = target.substringBefore(":").lowercase(Locale.US)
                    val port = target.substringAfter(":", "443").toIntOrNull() ?: 443
                    if (host.isBlank()) return
                    if (BlockEngine.checkDomain(host).isBlocked) {
                        blockedCount.incrementAndGet()
                        sock.getOutputStream().write("HTTP/1.1 403 Forbidden\r\nConnection: close\r\n\r\n".toByteArray())
                        return
                    }
                    val outbound = Socket()
                    outbound.soTimeout = 20000
                    outbound.connect(InetSocketAddress(host, port), 6000)
                    sock.getOutputStream().write("HTTP/1.1 200 Connection Established\r\n\r\n".toByteArray())
                    if (leftover.isNotEmpty()) outbound.getOutputStream().write(leftover)
                    outbound.getOutputStream().flush()
                    pump(sock, outbound)
                    return
                }

                // Plain HTTP: absolute-URI in, origin-form out.
                val (host, port) = hostOf(target)
                if (host.isBlank()) {
                    sock.getOutputStream().write("HTTP/1.1 400 Bad Request\r\nConnection: close\r\n\r\n".toByteArray())
                    return
                }
                if (BlockEngine.checkDomain(host).isBlocked) {
                    blockedCount.incrementAndGet()
                    sock.getOutputStream().write("HTTP/1.1 403 Forbidden\r\nConnection: close\r\n\r\n".toByteArray())
                    return
                }
                val outbound = Socket()
                outbound.soTimeout = 20000
                outbound.connect(InetSocketAddress(host, port), 6000)
                val outStream = outbound.getOutputStream()
                outStream.write(rewriteToOriginForm(headerStr).toByteArray(Charsets.ISO_8859_1))
                if (leftover.isNotEmpty()) outStream.write(leftover)
                outStream.flush()
                pump(sock, outbound)
            } catch (_: Throwable) {
            }
        }
    }

    /** Full-duplex copy between the client and the origin. */
    private fun pump(client: Socket, outbound: Socket) {
        val up = Thread {
            try {
                client.getInputStream().copyTo(outbound.getOutputStream())
            } catch (_: Throwable) {
            } finally {
                try {
                    outbound.shutdownOutput()
                } catch (_: Throwable) {
                }
            }
        }
        up.isDaemon = true
        up.start()
        try {
            outbound.getInputStream().copyTo(client.getOutputStream())
        } catch (_: Throwable) {
        } finally {
            try {
                outbound.close()
            } catch (_: Throwable) {
            }
        }
    }
}
