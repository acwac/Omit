package app.omit.shield.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import app.omit.shield.data.OmitPrefs

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val act = intent?.action ?: return
        if (act == Intent.ACTION_BOOT_COMPLETED || act == Intent.ACTION_MY_PACKAGE_REPLACED) {
            OmitPrefs.init(context)
            // Sharing is a promise to other devices on the network, so it comes
            // back after a reboot even when "start with boot" is off.
            val shouldRestart = OmitPrefs.homeShieldEnabled ||
                (OmitPrefs.startOnBoot && OmitPrefs.desiredEnabled)
            if (shouldRestart && VpnService.prepare(context) == null) {
                try {
                    val svc = Intent(context, OmitVpnService::class.java).apply {
                        action = OmitVpnService.ACTION_START
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        context.startForegroundService(svc)
                    } else {
                        context.startService(svc)
                    }
                } catch (_: Throwable) {}
            }
        }
    }
}
