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
import app.omit.shield.data.VellumPrefs
import app.omit.shield.service.VellumVpnService

class OmitWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        for (id in appWidgetIds) {
            updateWidget(context, appWidgetManager, id)
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == ACTION_TOGGLE_PAUSE) {
            VellumPrefs.init(context)
            val svcIntent = Intent(context, VellumVpnService::class.java)
            if (VellumPrefs.shieldState == ShieldState.PAUSED) {
                svcIntent.action = VellumVpnService.ACTION_RESUME
            } else {
                svcIntent.action = VellumVpnService.ACTION_PAUSE_60S
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
            VellumPrefs.init(context)
            val views = RemoteViews(context.packageName, R.layout.widget_omit)
            val paused = VellumPrefs.shieldState == ShieldState.PAUSED
            views.setTextViewText(
                R.id.widget_status,
                if (paused) "Omit · Paused" else "Omit · Protected"
            )
            views.setTextViewText(
                R.id.widget_count,
                "${VellumPrefs.blockedQueries.get()} blocked"
            )
            views.setTextViewText(
                R.id.widget_action_btn,
                if (paused) "Resume" else "60s Pause"
            )

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
