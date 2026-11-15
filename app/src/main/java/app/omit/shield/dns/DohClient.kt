package app.omit.shield.dns

import android.net.VpnService
import app.omit.shield.data.FilterMetadata
import app.omit.shield.data.VellumPrefs
import java.io.ByteArrayOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLSocketFactory

class DohClient(private val vpnService: VpnService?) {

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
            s.connect(InetSocketAddress(host, port), 3500)
            return delegate.createSocket(s, host, port, true)
        }

        override fun createSocket(host: String?, port: Int, localHost: InetAddress?, localPort: Int): Socket {
            val s = Socket()
            vpnService?.protect(s)
            s.bind(InetSocketAddress(localHost, localPort))
            s.connect(InetSocketAddress(host, port), 3500)
            return delegate.createSocket(s, host, port, true)
        }

        override fun createSocket(host: InetAddress?, port: Int): Socket {
            val s = Socket()
            vpnService?.protect(s)
            s.connect(InetSocketAddress(host, port), 3500)
            return delegate.createSocket(s, host?.hostAddress, port, true)
        }

        override fun createSocket(address: InetAddress?, port: Int, localAddress: InetAddress?, localPort: Int): Socket {
            val s = Socket()
            vpnService?.protect(s)
            s.bind(InetSocketAddress(localAddress, localPort))
            s.connect(InetSocketAddress(address, port), 3500)
            return delegate.createSocket(s, address?.hostAddress, port, true)
        }
    }

    fun resolve(queryWire: ByteArray): Pair<ByteArray, String>? {
        val selectedId = VellumPrefs.upstreamId
        if (selectedId == "custom") {
            val customUrl = VellumPrefs.customDohUrl
            if (customUrl.startsWith("https://")) {
                queryDohUrl(customUrl, queryWire)?.let { return Pair(it, "Custom DoH") }
            }
        }

        val spec = FilterMetadata.upstreamById(selectedId)

        // 1. Primary IP-bootstrapped DoH (Google https://8.8.8.8/dns-query by default)
        if (spec.primaryDohUrl.startsWith("https://")) {
            queryDohUrl(spec.primaryDohUrl, queryWire)?.let {
                return Pair(it, spec.name)
            }
        }

        // 2. Secondary IP-bootstrapped DoH (https://8.8.4.4/dns-query)
        if (spec.secondaryDohUrl.startsWith("https://")) {
            queryDohUrl(spec.secondaryDohUrl, queryWire)?.let {
                return Pair(it, spec.name)
            }
        }

        // 3. Automatic cross-provider DoH failover (Google <-> Cloudflare)
        val backupSpec = if (spec.id == "google") {
            FilterMetadata.upstreamById("cloudflare")
        } else {
            FilterMetadata.upstreamById("google")
        }
        queryDohUrl(backupSpec.primaryDohUrl, queryWire)?.let {
            return Pair(it, "${backupSpec.name} (Failover)")
        }

        // 4. Optional Hotel/Cafe Wi-Fi Captive Portal Helper
        if (VellumPrefs.captivePortalFallback) {
            queryProtectedUdp(spec.bootstrapUdpIp, queryWire)?.let {
                return Pair(it, "${spec.name} (Portal)")
            }
        }

        return null
    }

    private fun queryDohUrl(endpointUrl: String, queryWire: ByteArray): ByteArray? {
        return try {
            val url = URL(endpointUrl)
            val conn = (url.openConnection() as HttpsURLConnection).apply {
                sslSocketFactory = protectedSslFactory
                requestMethod = "POST"
                connectTimeout = 3200
                readTimeout = 3800
                doInput = true
                doOutput = true
                useCaches = false
                setRequestProperty("Content-Type", "application/dns-message")
                setRequestProperty("Accept", "application/dns-message")
                setRequestProperty("Connection", "keep-alive")
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
                        if (total > 8192) return null
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
        }
    }

    private fun queryProtectedUdp(serverIp: String, queryWire: ByteArray): ByteArray? {
        var sock: DatagramSocket? = null
        return try {
            sock = DatagramSocket()
            vpnService?.protect(sock)
            sock.soTimeout = 2200
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
}
