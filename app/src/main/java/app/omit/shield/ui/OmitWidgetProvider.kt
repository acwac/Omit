package app.omit.shield.ui

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import app.omit.shield.R
import app.omit.shield.data.ShieldState
import app.omit.shield.data.OmitPrefs
import app.omit.shield.service.OmitVpnService

class OmitWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        for (id in appWidgetIds) {
            updateWidget(context, appWidgetManager, id)
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == ACTION_TOGGLE_PAUSE) {
            OmitPrefs.init(context)
            val svcIntent = Intent(context, OmitVpnService::class.java)
            if (OmitPrefs.shieldState == ShieldState.PAUSED) {
                svcIntent.action = OmitVpnService.ACTION_RESUME
            } else {
                svcIntent.action = OmitVpnService.ACTION_PAUSE_60S
            }
            context.startService(svcIntent)
            updateAll(context)
        }
    }

    companion object {
        const val ACTION_TOGGLE_PAUSE = "app.omit.shield.WIDGET_TOGGLE_PAUSE"

        fun updateAll(context: Context) {
            val mgr = AppWidgetManager.getInstance(context)
            val ids = mgr.getAppWidgetIds(ComponentName(context, OmitWidgetProvider::class.java))
            for (id in ids) updateWidget(context, mgr, id)
        }

        private fun updateWidget(context: Context, mgr: AppWidgetManager, id: Int) {
            OmitPrefs.init(context)
            val views = RemoteViews(context.packageName, R.layout.widget_omit)
            val paused = OmitPrefs.shieldState == ShieldState.PAUSED
            views.setTextViewText(
                R.id.widget_status,
                if (paused) "Omit · Paused" else "Omit · Protected"
            )
            views.setTextViewText(
                R.id.widget_count,
                "${OmitPrefs.blockedQueries.get()} blocked"
            )
            views.setTextViewText(
                R.id.widget_action_btn,
                if (paused) "Resume" else "60s Pause"
            )

            val openIntent = Intent(context, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            val openPi = PendingIntent.getActivity(
                context, 1, openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.widget_root, openPi)

            val toggleIntent = Intent(context, OmitWidgetProvider::class.java).apply {
                action = ACTION_TOGGLE_PAUSE
            }
            val pi = PendingIntent.getBroadcast(
                context, 0, toggleIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.widget_action_btn, pi)
            mgr.updateAppWidget(id, views)
        }
    }
}
