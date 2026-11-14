package app.vellum.shield.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import app.vellum.shield.data.VellumPrefs

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val act = intent?.action ?: return
        if (act == Intent.ACTION_BOOT_COMPLETED || act == Intent.ACTION_MY_PACKAGE_REPLACED) {
            VellumPrefs.init(context)
            if (VellumPrefs.startOnBoot && VellumPrefs.desiredEnabled && VpnService.prepare(context) == null) {
                try {
                    val svc = Intent(context, VellumVpnService::class.java).apply {
                        action = VellumVpnService.ACTION_START
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
