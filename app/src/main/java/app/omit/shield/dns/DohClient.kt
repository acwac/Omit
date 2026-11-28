package app.omit.shield.dns

import android.net.VpnService
import app.omit.shield.data.FilterMetadata
import app.omit.shield.data.OmitPrefs
import java.io.ByteArrayOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLSocketFactory

/**
 * RFC 8484 DNS-over-HTTPS client.
 *
 * Every socket is `VpnService.protect()`-ed so the encrypted queries leave through the real
 * network instead of being routed back into our own TUN (which would deadlock).
 *
 * Endpoints are IP-literal (https://8.8.8.8/dns-query) so they work before the device has
 * any working DNS, which is exactly the state an ISP-blocked phone is in.
 */
class DohClient(private val vpnService: VpnService?) {

    /**
     * After a provider fails we remember it briefly so a flaky network does not pay the
     * full 3-provider timeout chain on every single query (which stalls browsing badly).
     */
    private class ProviderHealth {
        @Volatile var deadUntilMs: Long = 0L
    }

    private val health = HashMap<String, ProviderHealth>()

    private fun isHealthy(key: String): Boolean {
        val h = synchronized(health) { health[key] } ?: return true
        return h.deadUntilMs <= System.currentTimeMillis()
    }

    private fun markFailure(key: String) {
        synchronized(health) {
            health.getOrPut(key) { ProviderHealth() }.deadUntilMs =
                System.currentTimeMillis() + FAILURE_COOLDOWN_MS
        }
    }

    private fun markSuccess(key: String) {
        synchronized(health) {
            health.getOrPut(key) { ProviderHealth() }.deadUntilMs = 0L
        }
    }

    private fun tryEndpoint(key: String, url: String, queryWire: ByteArray): ByteArray? {
        if (!isHealthy(key)) return null
        val result = queryDohUrl(url, queryWire)
        if (result == null) markFailure(key) else markSuccess(key)
        return result
    }

    private val protectedSslFactory: SSLSocketFactory = object : SSLSocketFactory() {
        private val delegate = SSLSocketFactory.getDefault() as SSLSocketFactory

        override fun getDefaultCipherSuites(): Array<String> = delegate.defaultCipherSuites
        override fun getSupportedCipherSuites(): Array<String> = delegate.supportedCipherSuites

        override fun createSocket(): Socket {
            val s = Socket()
            vpnService?.protect(s)
            return s
        }

        override fun createSocket(s: Socket?, host: String?, port: Int, autoClose: Boolean): Socket {
            if (s != null) vpnService?.protect(s)
            return delegate.createSocket(s, host, port, autoClose)
        }

        override fun createSocket(host: String?, port: Int): Socket {
            val s = Socket()
            vpnService?.protect(s)
            s.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
            return delegate.createSocket(s, host, port, true)
        }

        override fun createSocket(host: String?, port: Int, localHost: InetAddress?, localPort: Int): Socket {
            val s = Socket()
            vpnService?.protect(s)
            s.bind(InetSocketAddress(localHost, localPort))
            s.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
            return delegate.createSocket(s, host, port, true)
        }

        override fun createSocket(host: InetAddress?, port: Int): Socket {
            val s = Socket()
            vpnService?.protect(s)
            s.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
            return delegate.createSocket(s, host?.hostAddress, port, true)
        }

        override fun createSocket(
            address: InetAddress?,
            port: Int,
            localAddress: InetAddress?,
            localPort: Int
        ): Socket {
            val s = Socket()
            vpnService?.protect(s)
            s.bind(InetSocketAddress(localAddress, localPort))
            s.connect(InetSocketAddress(address, port), CONNECT_TIMEOUT_MS)
            return delegate.createSocket(s, address?.hostAddress, port, true)
        }
    }

    fun resolve(queryWire: ByteArray): Pair<ByteArray, String>? {
        val selectedId = OmitPrefs.upstreamId
        if (selectedId == "custom") {
            val customUrl = OmitPrefs.customDohUrl
            if (customUrl.startsWith("https://")) {
                tryEndpoint("custom:$customUrl", customUrl, queryWire)?.let {
                    return Pair(it, "Custom DoH")
                }
            }
        }

        val spec = FilterMetadata.upstreamById(selectedId)

        // 1. Primary IP-bootstrapped DoH (Google https://8.8.8.8/dns-query by default)
        if (spec.primaryDohUrl.startsWith("https://")) {
            tryEndpoint(spec.primaryDohUrl, spec.primaryDohUrl, queryWire)?.let {
                return Pair(it, spec.name)
            }
        }

        // 2. Secondary IP-bootstrapped DoH (https://8.8.4.4/dns-query)
        if (spec.secondaryDohUrl.startsWith("https://")) {
            tryEndpoint(spec.secondaryDohUrl, spec.secondaryDohUrl, queryWire)?.let {
                return Pair(it, spec.name)
            }
        }

        // 3. Automatic cross-provider DoH failover (Google <-> Cloudflare)
        val backupSpec = if (spec.id == "google") {
            FilterMetadata.upstreamById("cloudflare")
        } else {
            FilterMetadata.upstreamById("google")
        }
        tryEndpoint(backupSpec.primaryDohUrl, backupSpec.primaryDohUrl, queryWire)?.let {
            return Pair(it, "${backupSpec.name} (Failover)")
        }

        // 4. Optional Hotel/Cafe Wi-Fi Captive Portal Helper (plain UDP, last resort)
        if (OmitPrefs.captivePortalFallback) {
            queryProtectedUdp(spec.bootstrapUdpIp, queryWire)?.let {
                return Pair(it, "${spec.name} (Portal)")
            }
        }

        return null
    }

    private fun queryDohUrl(endpointUrl: String, queryWire: ByteArray): ByteArray? {
        var conn: HttpsURLConnection? = null
        return try {
            val url = URL(endpointUrl)
            conn = (url.openConnection() as HttpsURLConnection).apply {
                sslSocketFactory = protectedSslFactory
                requestMethod = "POST"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                doInput = true
                doOutput = true
                useCaches = false
                instanceFollowRedirects = false
                setRequestProperty("Content-Type", "application/dns-message")
                setRequestProperty("Accept", "application/dns-message")
                setRequestProperty("User-Agent", "Omit-Android/2.5")
                setFixedLengthStreamingMode(queryWire.size)
            }
            conn.outputStream.use { out ->
                out.write(queryWire)
                out.flush()
            }
            if (conn.responseCode == 200) {
                val baos = ByteArrayOutputStream(512)
                conn.inputStream.use { inp ->
                    val buf = ByteArray(1024)
                    var total = 0
                    while (true) {
                        val r = inp.read(buf)
                        if (r <= 0) break
                        total += r
                        if (total > MAX_RESPONSE_BYTES) return null
                        baos.write(buf, 0, r)
                    }
                }
                val bytes = baos.toByteArray()
                if (bytes.size >= 12) bytes else null
            } else {
                null
            }
        } catch (_: Throwable) {
            null
        } finally {
            try { conn?.disconnect() } catch (_: Throwable) {}
        }
    }

    private fun queryProtectedUdp(serverIp: String, queryWire: ByteArray): ByteArray? {
        var sock: DatagramSocket? = null
        return try {
            sock = DatagramSocket()
            vpnService?.protect(sock)
            sock.soTimeout = UDP_TIMEOUT_MS
            val addr = InetAddress.getByName(serverIp)
            sock.send(DatagramPacket(queryWire, queryWire.size, addr, 53))
            val respBuf = ByteArray(2048)
            val respPkt = DatagramPacket(respBuf, respBuf.size)
            sock.receive(respPkt)
            if (respPkt.length >= 12) respBuf.copyOf(respPkt.length) else null
        } catch (_: Throwable) {
            null
        } finally {
            try { sock?.close() } catch (_: Throwable) {}
        }
    }

    private companion object {
        // Tuned for 2G/3G and congested Wi-Fi: too-short timeouts turn into SERVFAILs.
        const val CONNECT_TIMEOUT_MS = 3500
        const val READ_TIMEOUT_MS = 4500
        const val UDP_TIMEOUT_MS = 2500
        const val MAX_RESPONSE_BYTES = 8192
        const val FAILURE_COOLDOWN_MS = 20_000L
    }
}
