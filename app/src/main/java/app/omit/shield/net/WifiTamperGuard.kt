package app.omit.shield.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import java.net.URL
import javax.net.ssl.HttpsURLConnection

object WifiTamperGuard {
    @Volatile
    var activeWarning: String = ""
        private set

    fun register(context: Context) {
        try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
            val req = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .build()
            cm.registerNetworkCallback(req, object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    checkInBackground()
                }
            })
        } catch (_: Throwable) {}
    }

    private fun checkInBackground() {
        Thread {
            try {
                val conn = (URL("https://8.8.8.8/dns-query").openConnection() as HttpsURLConnection).apply {
                    connectTimeout = 5000
                    readTimeout = 5000
                    requestMethod = "HEAD"
                }
                conn.connect()
                val certs = conn.serverCertificates
                if (certs.isEmpty()) {
                    activeWarning = "Wi-Fi SSL tampering detected: missing upstream certificate."
                } else {
                    activeWarning = ""
                }
                conn.disconnect()
            } catch (_: javax.net.ssl.SSLHandshakeException) {
                activeWarning = "Wi-Fi SSL interception detected! Your network may be tampered with."
            } catch (_: Throwable) {
                // Offline or captive portal; do not false-alarm
            }
        }.start()
    }
}
