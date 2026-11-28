package app.omit.shield.net

import app.omit.shield.data.OmitPrefs
import app.omit.shield.dns.BlockEngine
import java.io.ByteArrayOutputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

object HomeShieldServer {
    private val running = AtomicBoolean(false)
    val blockedCount = AtomicLong(0)
    @Volatile var activePort: Int = 8080
        private set
    private var serverSocket: ServerSocket? = null
    private val pool = Executors.newCachedThreadPool()

    fun getLanIp(): String {
        try {
            val ifaces = NetworkInterface.getNetworkInterfaces()
            while (ifaces.hasMoreElements()) {
                val iface = ifaces.nextElement()
                if (!iface.isUp || iface.isLoopback) continue
                val addrs = iface.inetAddresses
                while (addrs.hasMoreElements()) {
                    val addr = addrs.nextElement()
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        val ip = addr.hostAddress ?: continue
                        if (ip.startsWith("192.168.") || ip.startsWith("10.")) return ip
                    }
                }
            }
        } catch (_: Throwable) {}
        return "127.0.0.1"
    }

    /** True once a listening socket is bound. */
    val isRunning: Boolean get() = running.get() && serverSocket != null

    /**
     * Binds synchronously so the UI can read [activePort] the moment the toggle
     * returns. Port 8080 is the documented default; 8088 is the fallback when
     * something else already owns 8080.
     */
    fun start(): Boolean {
        if (isRunning) return true
        val bound = try {
            ServerSocket(8080).also { activePort = 8080 }
        } catch (_: Throwable) {
            try {
                ServerSocket(8088).also { activePort = 8088 }
            } catch (_: Throwable) {
                null
            }
        }
        if (bound == null) {
            // Leave the object clean so a later attempt can succeed.
            running.set(false)
            serverSocket = null
            return false
        }
        serverSocket = bound
        running.set(true)
        pool.execute {
            while (running.get()) {
                try {
                    val client = bound.accept()
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
        try { serverSocket?.close() } catch (_: Throwable) {}
        serverSocket = null
    }

    private fun readHeaderBytes(input: java.io.InputStream): Pair<ByteArray, ByteArray>? {
        val buf = ByteArrayOutputStream()
        val tmp = ByteArray(1024)
        while (buf.size() < 32768) {
            val n = input.read(tmp)
            if (n <= 0) break
            buf.write(tmp, 0, n)
            val data = buf.toByteArray()
            for (i in 0 until data.size - 3) {
                if (data[i] == 13.toByte() && data[i + 1] == 10.toByte() &&
                    data[i + 2] == 13.toByte() && data[i + 3] == 10.toByte()
                ) {
                    val headerEnd = i + 4
                    return data.copyOfRange(0, headerEnd) to data.copyOfRange(headerEnd, data.size)
                }
            }
        }
        return null
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
                    val resp = "HTTP/1.1 200 OK\r\nContent-Type: application/x-ns-proxy-autoconfig\r\nContent-Length: ${pac.length}\r\nConnection: close\r\n\r\n$pac"
                    sock.getOutputStream().write(resp.toByteArray())
                    return
                }

                val host = if (method == "CONNECT") {
                    target.substringBefore(":")
                } else {
                    java.net.URI(target).host ?: ""
                }
                val port = if (method == "CONNECT") {
                    target.substringAfter(":", "443").toIntOrNull() ?: 443
                } else {
                    80
                }

                if (host.isNotBlank() && BlockEngine.checkDomain(host).isBlocked) {
                    blockedCount.incrementAndGet()
                    sock.getOutputStream().write("HTTP/1.1 403 Forbidden\r\nConnection: close\r\n\r\n".toByteArray())
                    return
                }

                val outbound = Socket()
                outbound.soTimeout = 20000
                outbound.connect(java.net.InetSocketAddress(host, port), 5000)
                val outStream = outbound.getOutputStream()
                if (method == "CONNECT") {
                    sock.getOutputStream().write("HTTP/1.1 200 Connection Established\r\n\r\n".toByteArray())
                } else {
                    outStream.write(headerBytes)
                }
                if (leftover.isNotEmpty()) {
                    outStream.write(leftover)
                }
                outStream.flush()

                val t1 = Thread {
                    try { input.copyTo(outStream) } catch (_: Throwable) {}
                }
                t1.isDaemon = true
                t1.start()
                try { outbound.getInputStream().copyTo(sock.getOutputStream()) } catch (_: Throwable) {}
                outbound.close()
            } catch (_: Throwable) {}
        }
    }
}
