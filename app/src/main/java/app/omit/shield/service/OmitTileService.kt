package app.omit.shield.service

import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import app.omit.shield.data.FilterMetadata
import app.omit.shield.data.ShieldState
import app.omit.shield.data.OmitPrefs
import app.omit.shield.ui.MainActivity

class OmitTileService : TileService() {

    private val listener: () -> Unit = { refreshTile() }

    override fun onStartListening() {
        super.onStartListening()
        OmitPrefs.init(applicationContext)
        OmitPrefs.addListener(listener)
        refreshTile()
    }

    override fun onStopListening() {
        OmitPrefs.removeListener(listener)
        super.onStopListening()
    }

    override fun onClick() {
        super.onClick()
        OmitPrefs.init(applicationContext)
        val running = OmitPrefs.shieldState != ShieldState.OFF
        if (running) {
            val stopIntent = Intent(this, OmitVpnService::class.java).apply {
                action = OmitVpnService.ACTION_STOP
            }
            startService(stopIntent)
            refreshTile(forceState = false)
        } else {
            val prep = VpnService.prepare(this)
            if (prep != null) {
                val uiIntent = Intent(this, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                if (Build.VERSION.SDK_INT >= 34) {
                    val pi = PendingIntent.getActivity(
                        this,
                        10,
                        uiIntent,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                    )
                    startActivityAndCollapse(pi)
                } else {
                    @Suppress("DEPRECATION")
                    startActivityAndCollapse(uiIntent)
                }
            } else {
                try {
                    val startIntent = Intent(this, OmitVpnService::class.java).apply {
                        action = OmitVpnService.ACTION_START
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        startForegroundService(startIntent)
                    } else {
                        startService(startIntent)
                    }
                    refreshTile(forceState = true)
                } catch (_: Throwable) {}
            }
        }
    }

    private fun refreshTile(forceState: Boolean? = null) {
        val tile = qsTile ?: return
        val active = forceState ?: (OmitPrefs.shieldState != ShieldState.OFF)
        tile.state = if (active) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = "Omit"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = if (active) {
                FilterMetadata.intensityTitleForMask(OmitPrefs.activeCategoryMask.get())
            } else {
                "Off"
            }
        }
        tile.updateTile()
    }
}
