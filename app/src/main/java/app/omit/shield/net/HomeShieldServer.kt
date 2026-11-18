package app.omit.shield.net

import android.net.VpnService
import app.omit.shield.data.VellumPrefs
import app.omit.shield.dns.BlockEngine
import java.io.BufferedReader
import java.io.InputStreamReader
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

    fun start(vpn: VpnService?) {
        if (running.getAndSet(true)) return
        pool.execute {
            val ss = try {
                activePort = 8080
                ServerSocket(8080)
            } catch (_: Throwable) {
                activePort = 8088
                ServerSocket(8088)
            }
            serverSocket = ss
            while (running.get()) {
                try {
                    val client = ss.accept()
                    pool.execute { handleClient(client, vpn) }
                } catch (_: Throwable) {
                    if (!running.get()) break
                }
            }
        }
    }

    fun stop() {
        running.set(false)
        try { serverSocket?.close() } catch (_: Throwable) {}
        serverSocket = null
    }

    private fun handleClient(client: Socket, vpn: VpnService?) {
        client.use { sock ->
            try {
                val reader = BufferedReader(InputStreamReader(sock.getInputStream()))
                val reqLine = reader.readLine() ?: return
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
                vpn?.protect(outbound)
                outbound.connect(java.net.InetSocketAddress(host, port), 5000)
                if (method == "CONNECT") {
                    sock.getOutputStream().write("HTTP/1.1 200 Connection Established\r\n\r\n".toByteArray())
                } else {
                    val sb = StringBuilder(reqLine).append("\r\n")
                    while (true) {
                        val line = reader.readLine() ?: break
                        sb.append(line).append("\r\n")
                        if (line.isEmpty()) break
                    }
                    outbound.getOutputStream().write(sb.toString().toByteArray())
                }
                val t1 = Thread {
                    try { sock.getInputStream().copyTo(outbound.getOutputStream()) } catch (_: Throwable) {}
                }
                t1.isDaemon = true
                t1.start()
                try { outbound.getInputStream().copyTo(sock.getOutputStream()) } catch (_: Throwable) {}
                outbound.close()
            } catch (_: Throwable) {}
        }
    }
}
