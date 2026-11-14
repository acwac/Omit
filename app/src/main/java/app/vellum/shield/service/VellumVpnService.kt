package app.vellum.shield.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.VpnService
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.system.OsConstants
import android.util.LruCache
import app.vellum.shield.R
import app.vellum.shield.data.FilterMetadata
import app.vellum.shield.data.ShieldState
import app.vellum.shield.data.VellumPrefs
import app.vellum.shield.dns.BlockEngine
import app.vellum.shield.dns.BlockVerdict
import app.vellum.shield.dns.DnsPacket
import app.vellum.shield.dns.DohClient
import app.vellum.shield.dns.IpUdpPacket
import app.vellum.shield.dns.ParsedDnsQuery
import app.vellum.shield.dns.ParsedTunDnsPacket
import app.vellum.shield.dns.VerdictType
import app.vellum.shield.ui.MainActivity
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

class VellumVpnService : VpnService() {

    companion object {
        const val ACTION_START = "app.vellum.shield.START"
        const val ACTION_RELOAD = "app.vellum.shield.RELOAD"
        const val ACTION_STOP = "app.vellum.shield.STOP"
        const val ACTION_PAUSE_60S = "app.vellum.shield.PAUSE_60S"
        const val ACTION_RESUME = "app.vellum.shield.RESUME"
        private const val CHANNEL_ID = "vellum_quiet_status_v2"
        private const val NOTIF_ID = 1042

        fun detectStrictPrivateDnsHost(context: Context): String? {
            return try {
                val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                val net = cm?.activeNetwork ?: return null
                val lp = cm.getLinkProperties(net) ?: return null
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    lp.privateDnsServerName?.takeIf { it.isNotBlank() }
                } else {
                    null
                }
            } catch (_: Throwable) {
                null
            }
        }
    }

    private data class CachedDnsEntry(val wire: ByteArray, val expiresAtMs: Long)

    private val sessionGen = AtomicLong(0)
    @Volatile
    private var activeTunPfd: ParcelFileDescriptor? = null
    @Volatile
    private var pauseUntilEpochMs: Long = 0L

    // Scale down to 0 threads after 25s idle so zero worker threads sit in RAM overnight
    private val queryPool = ThreadPoolExecutor(
        0,
        3,
        25L,
        TimeUnit.SECONDS,
        LinkedBlockingQueue(128)
    ) { r ->
        Thread(r, "vellum-doh-worker").apply { isDaemon = true }
    }

    // 2048-entry RAM DNS cache drastically cuts repeated radio wakeups
    private val dnsCache = object : LruCache<String, CachedDnsEntry>(2048) {}
    private val mainHandler = Handler(Looper.getMainLooper())
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    private val pauseTicker = object : Runnable {
        override fun run() {
            val rem = computePauseRemainingSec()
            if (rem > 0 && activeTunPfd != null) {
                VellumPrefs.updateRuntimeState(ShieldState.PAUSED, rem)
                mainHandler.postDelayed(this, 1000L)
            } else {
                pauseUntilEpochMs = 0L
                if (activeTunPfd != null) {
                    VellumPrefs.updateRuntimeState(computeActiveShieldState(), 0)
                    updateForegroundNotification()
                }
            }
        }
    }

    private fun computePauseRemainingSec(): Int {
        val until = pauseUntilEpochMs
        if (until <= 0L) return 0
        val diff = until - System.currentTimeMillis()
        return if (diff <= 0L) 0 else ((diff + 999L) / 1000L).toInt()
    }

    private fun computeActiveShieldState(): ShieldState {
        return if (VellumPrefs.offlineGameMode && VellumPrefs.offlineGamePackages.isNotEmpty()) {
            ShieldState.OFFLINE_SANDBOX
        } else {
            ShieldState.PROTECTED
        }
    }

    override fun onCreate() {
        super.onCreate()
        VellumPrefs.init(applicationContext)
        BlockEngine.ensureLoaded(applicationContext)
        registerPrivateDnsMonitor()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        VellumPrefs.init(applicationContext)
        when (intent?.action) {
            ACTION_STOP -> {
                VellumPrefs.setDesiredEnabled(false)
                pauseUntilEpochMs = 0L
                mainHandler.removeCallbacks(pauseTicker)
                stopVpnTunnel()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_PAUSE_60S -> {
                pauseUntilEpochMs = System.currentTimeMillis() + 60_000L
                mainHandler.removeCallbacks(pauseTicker)
                mainHandler.post(pauseTicker)
                updateForegroundNotification()
                return START_STICKY
            }
            ACTION_RESUME -> {
                pauseUntilEpochMs = 0L
                mainHandler.removeCallbacks(pauseTicker)
                if (activeTunPfd != null) {
                    VellumPrefs.updateRuntimeState(computeActiveShieldState(), 0)
                }
                updateForegroundNotification()
                return START_STICKY
            }
            ACTION_RELOAD -> {
                synchronized(dnsCache) { dnsCache.evictAll() }
                if (VellumPrefs.shieldState != ShieldState.OFF || VellumPrefs.desiredEnabled) {
                    startOrRebuildTunnel()
                }
                return START_STICKY
            }
            else -> {
                VellumPrefs.setDesiredEnabled(true)
                pauseUntilEpochMs = 0L
                startForegroundWithNotification()
                startOrRebuildTunnel()
                return START_STICKY
            }
        }
    }

    override fun onRevoke() {
        VellumPrefs.setDesiredEnabled(false)
        pauseUntilEpochMs = 0L
        mainHandler.removeCallbacks(pauseTicker)
        stopVpnTunnel()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        super.onRevoke()
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(pauseTicker)
        unregisterPrivateDnsMonitor()
        stopVpnTunnel()
        queryPool.shutdownNow()
        super.onDestroy()
    }

    private fun registerPrivateDnsMonitor() {
        try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
            VellumPrefs.updatePrivateDnsWarning(detectStrictPrivateDnsHost(this))
            val cb = object : ConnectivityManager.NetworkCallback() {
                override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        val host = linkProperties.privateDnsServerName?.takeIf { it.isNotBlank() }
                        VellumPrefs.updatePrivateDnsWarning(host)
                    }
                }
            }
            cm.registerDefaultNetworkCallback(cb)
            networkCallback = cb
        } catch (_: Throwable) {}
    }

    private fun unregisterPrivateDnsMonitor() {
        try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            networkCallback?.let { cm?.unregisterNetworkCallback(it) }
        } catch (_: Throwable) {}
        networkCallback = null
    }

    private fun startOrRebuildTunnel() {
        val myGen = sessionGen.incrementAndGet()
        val oldPfd = activeTunPfd
        activeTunPfd = null
        try { oldPfd?.close() } catch (_: Throwable) {}

        Thread({
            BlockEngine.ensureLoaded(applicationContext)
            VellumPrefs.updatePrivateDnsWarning(detectStrictPrivateDnsHost(applicationContext))

            val pfd = try {
                buildSplitOrSandboxTun()
            } catch (_: Throwable) {
                VellumPrefs.updateRuntimeState(ShieldState.OFF, 0)
                stopSelf()
                return@Thread
            }

            if (pfd == null || sessionGen.get() != myGen) {
                try { pfd?.close() } catch (_: Throwable) {}
                if (sessionGen.get() == myGen) {
                    VellumPrefs.updateRuntimeState(ShieldState.OFF, 0)
                    stopSelf()
                }
                return@Thread
            }

            activeTunPfd = pfd
            val rem = computePauseRemainingSec()
            if (rem > 0) {
                VellumPrefs.updateRuntimeState(ShieldState.PAUSED, rem)
            } else {
                VellumPrefs.updateRuntimeState(computeActiveShieldState(), 0)
            }
            updateForegroundNotification()

            val dohClient = DohClient(this@VellumVpnService)
            val fis = FileInputStream(pfd.fileDescriptor)
            val fos = FileOutputStream(pfd.fileDescriptor)
            val writeLock = Any()
            val packetBuf = ByteArray(4096)

            try {
                while (sessionGen.get() == myGen && !Thread.currentThread().isInterrupted) {
                    val readLen = fis.read(packetBuf)
                    if (readLen <= 0) {
                        if (readLen < 0) break
                        continue
                    }

                    val parsedIp = IpUdpPacket.parseIncomingUdp53(packetBuf, readLen)
                    if (parsedIp == null) {
                        val rstPkt = IpUdpPacket.buildTcpRstIfSyn(packetBuf, readLen)
                        if (rstPkt != null) {
                            writeTunPacket(fos, writeLock, rstPkt)
                        }
                        continue
                    }

                    val parsedDns = DnsPacket.parseQuery(
                        packetBuf,
                        parsedIp.dnsOffset,
                        parsedIp.dnsLength
                    ) ?: continue

                    val startNs = System.nanoTime()
                    val isPaused = computePauseRemainingSec() > 0
                    val blockTtlSec = if (VellumPrefs.antiStormTtl) 300 else 15

                    // Strip HTTPS/SVCB Type 65 when Browser DoH Guard is enabled to prevent ECH/DoH bypass
                    if (!isPaused && parsedDns.qtype == 65 && VellumPrefs.blockDohCanary) {
                        val emptyNoErr = DnsPacket.buildBlockedResponse(
                            packetBuf, parsedIp.dnsOffset, parsedDns, nxdomain = false, ttlSeconds = blockTtlSec
                        )
                        writeTunPacket(fos, writeLock, IpUdpPacket.buildUdpResponse(parsedIp, emptyNoErr))
                        continue
                    }

                    val verdict = if (isPaused) {
                        BlockVerdict(VerdictType.ALLOWED, 0, "Paused (60s)")
                    } else {
                        BlockEngine.checkDomain(parsedDns.qname)
                    }

                    if (verdict.isBlocked) {
                        val nxdomain = verdict.type == VerdictType.BLOCKED_CANARY_NXDOMAIN
                        val syntheticDns = DnsPacket.buildBlockedResponse(
                            queryBuf = packetBuf,
                            queryOffset = parsedIp.dnsOffset,
                            parsed = parsedDns,
                            nxdomain = nxdomain,
                            ttlSeconds = blockTtlSec
                        )
                        val outIp = IpUdpPacket.buildUdpResponse(parsedIp, syntheticDns)
                        writeTunPacket(fos, writeLock, outIp)

                        val elapsedMs = ((System.nanoTime() - startNs) / 1_000_000L).toInt()
                        VellumPrefs.recordQuery(
                            domain = parsedDns.qname,
                            qtype = parsedDns.qtypeName(),
                            blocked = true,
                            cached = false,
                            cnameCloaked = false,
                            reason = verdict.reason,
                            latencyMs = elapsedMs
                        )
                        continue
                    }

                    val cacheKey = "${parsedDns.qname}|${parsedDns.qtype}|${if (isPaused) 1 else 0}"
                    val nowMs = System.currentTimeMillis()
                    val cached = synchronized(dnsCache) { dnsCache.get(cacheKey) }
                    if (cached != null && cached.expiresAtMs > nowMs) {
                        val cachedWire = DnsPacket.cloneWithTxId(cached.wire, parsedDns.txId)
                        writeTunPacket(fos, writeLock, IpUdpPacket.buildUdpResponse(parsedIp, cachedWire))
                        VellumPrefs.recordQuery(
                            domain = parsedDns.qname,
                            qtype = parsedDns.qtypeName(),
                            blocked = false,
                            cached = true,
                            cnameCloaked = false,
                            reason = "RAM Cache",
                            latencyMs = 0
                        )
                        continue
                    }

                    val dnsQueryBytes = packetBuf.copyOfRange(
                        parsedIp.dnsOffset,
                        parsedIp.dnsOffset + parsedIp.dnsLength
                    )
                    queryPool.execute {
                        handleUpstreamResolution(
                            myGen = myGen,
                            dohClient = dohClient,
                            fos = fos,
                            writeLock = writeLock,
                            parsedIp = parsedIp,
                            parsedDns = parsedDns,
                            dnsQueryBytes = dnsQueryBytes,
                            cacheKey = cacheKey,
                            isPaused = isPaused,
                            blockTtlSec = blockTtlSec,
                            startNs = startNs
                        )
                    }
                }
            } catch (_: Throwable) {
            } finally {
                try { fis.close() } catch (_: Throwable) {}
                try { fos.close() } catch (_: Throwable) {}
                if (sessionGen.get() == myGen) {
                    try { pfd.close() } catch (_: Throwable) {}
                    activeTunPfd = null
                    VellumPrefs.updateRuntimeState(ShieldState.OFF, 0)
                }
            }
        }, "vellum-tun-loop-$myGen").apply {
            priority = Thread.NORM_PRIORITY + 1
            start()
        }
    }

    private fun handleUpstreamResolution(
        myGen: Long,
        dohClient: DohClient,
        fos: FileOutputStream,
        writeLock: Any,
        parsedIp: ParsedTunDnsPacket,
        parsedDns: ParsedDnsQuery,
        dnsQueryBytes: ByteArray,
        cacheKey: String,
        isPaused: Boolean,
        blockTtlSec: Int,
        startNs: Long
    ) {
        if (sessionGen.get() != myGen) return
        val resolved = dohClient.resolve(dnsQueryBytes)
        val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNs).toInt()

        if (resolved == null) {
            val servFail = DnsPacket.buildServFailResponse(dnsQueryBytes, 0, parsedDns)
            writeTunPacket(fos, writeLock, IpUdpPacket.buildUdpResponse(parsedIp, servFail))
            VellumPrefs.recordQuery(
                domain = parsedDns.qname,
                qtype = parsedDns.qtypeName(),
                blocked = false,
                cached = false,
                cnameCloaked = false,
                reason = "Upstream Timeout",
                latencyMs = elapsedMs
            )
            return
        }

        val (upstreamWire, providerLabel) = resolved
        val inspection = DnsPacket.inspectResponse(upstreamWire)

        // Deep CNAME Uncloaking check
        if (!isPaused && VellumPrefs.cnameUncloak && inspection != null && inspection.cnameTargets.isNotEmpty()) {
            for (cnameTarget in inspection.cnameTargets) {
                val cnameVerdict = BlockEngine.checkDomain(cnameTarget)
                if (cnameVerdict.isBlocked) {
                    val synth = DnsPacket.buildBlockedResponse(
                        queryBuf = dnsQueryBytes,
                        queryOffset = 0,
                        parsed = parsedDns,
                        nxdomain = false,
                        ttlSeconds = blockTtlSec
                    )
                    writeTunPacket(fos, writeLock, IpUdpPacket.buildUdpResponse(parsedIp, synth))
                    VellumPrefs.recordQuery(
                        domain = parsedDns.qname,
                        qtype = parsedDns.qtypeName(),
                        blocked = true,
                        cached = false,
                        cnameCloaked = true,
                        reason = "CNAME -> $cnameTarget",
                        latencyMs = elapsedMs
                    )
                    return
                }
            }
        }

        val finalWire = DnsPacket.cloneWithTxId(upstreamWire, parsedDns.txId)
        writeTunPacket(fos, writeLock, IpUdpPacket.buildUdpResponse(parsedIp, finalWire))

        val ttlSec = (inspection?.minTtlSec ?: 300).coerceIn(180, 3600)
        synchronized(dnsCache) {
            dnsCache.put(cacheKey, CachedDnsEntry(finalWire, System.currentTimeMillis() + ttlSec * 1000L))
        }

        VellumPrefs.recordQuery(
            domain = parsedDns.qname,
            qtype = parsedDns.qtypeName(),
            blocked = false,
            cached = false,
            cnameCloaked = false,
            reason = providerLabel,
            latencyMs = elapsedMs
        )
    }

    private fun writeTunPacket(fos: FileOutputStream, lock: Any, packet: ByteArray) {
        try {
            synchronized(lock) {
                fos.write(packet)
            }
        } catch (_: Throwable) {}
    }

    private fun buildSplitOrSandboxTun(): ParcelFileDescriptor? {
        val offlineGames = VellumPrefs.offlineGamePackages
        val useOfflineSandbox = VellumPrefs.offlineGameMode && offlineGames.isNotEmpty()

        if (useOfflineSandbox) {
            val builder = Builder()
                .setSession("Vellum Game Mode")
                .setMtu(1500)
                .setBlocking(true)
                .allowFamily(OsConstants.AF_INET)
                .allowFamily(OsConstants.AF_INET6)
                .addAddress("10.215.173.1", 30)
                .addDnsServer("10.215.173.2")
                .addAddress("fd00:7665:6c6c::1", 126)
                .addDnsServer("fd00:7665:6c6c::2")
                .addRoute("0.0.0.0", 0)
                .addRoute("::", 0)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                builder.setMetered(false)
            }

            val launchIntent = Intent(this, MainActivity::class.java)
            val piFlags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            builder.setConfigureIntent(PendingIntent.getActivity(this, 0, launchIntent, piFlags))

            var addedCount = 0
            for (pkg in offlineGames) {
                if (pkg == packageName) continue
                try {
                    builder.addAllowedApplication(pkg)
                    addedCount++
                } catch (_: Throwable) {}
            }
            if (addedCount > 0) {
                return builder.establish()
            }
            // If none of the marked games remain installed, reset game mode & fall back cleanly
            VellumPrefs.setOfflineGameMode(false)
        }

        return buildStandardDnsSplitTun()
    }

    private fun buildStandardDnsSplitTun(): ParcelFileDescriptor? {
        val builder = Builder()
            .setSession("Vellum Shield")
            .setMtu(1500)
            .setBlocking(true)
            .allowFamily(OsConstants.AF_INET)
            .allowFamily(OsConstants.AF_INET6)
            .addAddress("10.215.173.1", 30)
            .addDnsServer("10.215.173.2")
            .addRoute("10.215.173.2", 32)
            .addAddress("fd00:7665:6c6c::1", 126)
            .addDnsServer("fd00:7665:6c6c::2")
            .addRoute("fd00:7665:6c6c::2", 128)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            builder.setMetered(false)
        }

        try { builder.addDisallowedApplication(packageName) } catch (_: Throwable) {}
        for (pkg in VellumPrefs.bypassPackages) {
            if (pkg.isNotBlank() && pkg != packageName) {
                try { builder.addDisallowedApplication(pkg) } catch (_: Throwable) {}
            }
        }

        val launchIntent = Intent(this, MainActivity::class.java)
        val piFlags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        builder.setConfigureIntent(PendingIntent.getActivity(this, 0, launchIntent, piFlags))
        return builder.establish()
    }

    private fun stopVpnTunnel() {
        sessionGen.incrementAndGet()
        val old = activeTunPfd
        activeTunPfd = null
        try { old?.close() } catch (_: Throwable) {}
        VellumPrefs.flushStats()
        VellumPrefs.updateRuntimeState(ShieldState.OFF, 0)
    }

    private fun startForegroundWithNotification() {
        try {
            ensureNotificationChannel()
            val notif = buildNotification()
            if (Build.VERSION.SDK_INT >= 34) {
                try {
                    startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
                } catch (_: Throwable) {
                    startForeground(NOTIF_ID, notif)
                }
            } else {
                startForeground(NOTIF_ID, notif)
            }
        } catch (_: Throwable) {}
    }

    private fun updateForegroundNotification() {
        try {
            ensureNotificationChannel()
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(NOTIF_ID, buildNotification())
        } catch (_: Throwable) {}
    }

    private fun ensureNotificationChannel() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val ch = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notif_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.notif_channel_desc)
            setShowBadge(false)
        }
        nm.createNotificationChannel(ch)
    }

    private fun buildNotification(): Notification {
        val openIntent = Intent(this, MainActivity::class.java)
        val openPi = PendingIntent.getActivity(
            this, 1, openIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = Intent(this, VellumVpnService::class.java).apply { action = ACTION_STOP }
        val stopPi = PendingIntent.getService(
            this, 2, stopIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val pausedRem = computePauseRemainingSec()
        val title = when {
            pausedRem > 0 -> "Vellum · Paused (${pausedRem}s)"
            VellumPrefs.offlineGameMode && VellumPrefs.offlineGamePackages.isNotEmpty() -> "Vellum · Offline Game Mode"
            else -> "Vellum · Shield Active"
        }
        val intensity = FilterMetadata.intensityTitleForMask(VellumPrefs.activeCategoryMask.get())
        val upstreamName = FilterMetadata.upstreamById(VellumPrefs.upstreamId).name
        val subtitle = "$intensity · $upstreamName"

        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_qs_shield)
            .setContentTitle(title)
            .setContentText(subtitle)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openPi)
            .addAction(Notification.Action.Builder(null, "Turn Off", stopPi).build())
            .build()
    }
}
