package app.omit.shield.service

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
import app.omit.shield.R
import app.omit.shield.data.FilterMetadata
import app.omit.shield.data.OmitPrefs
import app.omit.shield.data.ShieldState
import app.omit.shield.dns.BlockEngine
import app.omit.shield.dns.BlockVerdict
import app.omit.shield.dns.DnsPacket
import app.omit.shield.dns.DohClient
import app.omit.shield.dns.IpUdpPacket
import app.omit.shield.dns.ParsedDnsQuery
import app.omit.shield.dns.ParsedTunDnsPacket
import app.omit.shield.dns.VerdictType
import app.omit.shield.ui.MainActivity
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

class OmitVpnService : VpnService() {

    companion object {
        const val ACTION_START = "app.omit.shield.START"
        const val ACTION_RELOAD = "app.omit.shield.RELOAD"
        const val ACTION_STOP = "app.omit.shield.STOP"
        const val ACTION_PAUSE_60S = "app.omit.shield.PAUSE_60S"
        const val ACTION_RESUME = "app.omit.shield.RESUME"
        private const val CHANNEL_ID = "omit_quiet_status_v2"
        private const val NOTIF_ID = 1042

        // Virtual tunnel addresses. All DNS traffic to these is intercepted by the TUN.
        const val TUN_IPV4_GATEWAY = "10.215.173.1"
        const val TUN_IPV4_DNS = "10.215.173.2"
        const val TUN_IPV6_GATEWAY = "fd00:7665:6c6c::1"
        const val TUN_IPV6_DNS = "fd00:7665:6c6c::2"

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
    @Volatile
    private var lastForwarders: List<String> = emptyList()

    // Scale down to 0 threads after 25s idle so zero worker threads sit in RAM overnight
    private val queryPool = ThreadPoolExecutor(
        0,
        3,
        25L,
        TimeUnit.SECONDS,
        LinkedBlockingQueue(256)
    ) { r ->
        Thread(r, "omit-doh-worker").apply { isDaemon = true }
    }

    private val resolverPool = ThreadPoolExecutor(
        0,
        1,
        30L,
        TimeUnit.SECONDS,
        LinkedBlockingQueue(16)
    ) { r ->
        Thread(r, "omit-system-resolver").apply { isDaemon = true }
    }

    // 2048-entry RAM DNS cache drastically cuts repeated radio wakeups
    private val dnsCache = object : LruCache<String, CachedDnsEntry>(2048) {}
    private val mainHandler = Handler(Looper.getMainLooper())
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    private val pauseTicker = object : Runnable {
        override fun run() {
            val rem = computePauseRemainingSec()
            if (rem > 0 && activeTunPfd != null) {
                OmitPrefs.updateRuntimeState(ShieldState.PAUSED, rem)
                mainHandler.postDelayed(this, 1000L)
            } else {
                pauseUntilEpochMs = 0L
                if (activeTunPfd != null) {
                    OmitPrefs.updateRuntimeState(computeActiveShieldState(), 0)
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
        return if (OmitPrefs.offlineGameMode && OmitPrefs.offlineGamePackages.isNotEmpty()) {
            ShieldState.OFFLINE_SANDBOX
        } else {
            ShieldState.PROTECTED
        }
    }

    override fun onCreate() {
        super.onCreate()
        OmitPrefs.init(applicationContext)
        BlockEngine.ensureLoaded(applicationContext)
        registerNetworkMonitor()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        OmitPrefs.init(applicationContext)
        when (intent?.action) {
            ACTION_STOP -> {
                OmitPrefs.setDesiredEnabled(false)
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
                    OmitPrefs.updateRuntimeState(computeActiveShieldState(), 0)
                }
                updateForegroundNotification()
                return START_STICKY
            }
            ACTION_RELOAD -> {
                synchronized(dnsCache) { dnsCache.evictAll() }
                if (OmitPrefs.homeShieldEnabled) {
                    // A failed bind (port taken) must switch the pref back off so the
                    // UI never shows a proxy address that is not listening.
                    if (!app.omit.shield.net.HomeShieldServer.start()) {
                        OmitPrefs.setHomeShieldEnabled(false)
                    }
                } else {
                    app.omit.shield.net.HomeShieldServer.stop()
                }
                // Rebuild only when per-app routing actually changed; a plain rule/engine
                // toggle is picked up live by the running TUN loop (no churn, no flicker).
                if (needsTunnelRebuild()) {
                    startForegroundWithNotification()
                    startOrRebuildTunnel()
                } else if (activeTunPfd != null) {
                    OmitPrefs.updateRuntimeState(computeActiveShieldState(), 0)
                    updateForegroundNotification()
                } else if (OmitPrefs.desiredEnabled) {
                    startForegroundWithNotification()
                    startOrRebuildTunnel()
                }
                return START_STICKY
            }
            else -> {
                OmitPrefs.setDesiredEnabled(true)
                pauseUntilEpochMs = 0L
                startForegroundWithNotification()
                startOrRebuildTunnel()
                return START_STICKY
            }
        }
    }

    /**
     * The TUN only needs rebuilding when the set of routed/bypassed apps changes or the
     * Offline-Lock sandbox toggles. Everything else is read live from OmitPrefs.
     */
    private fun needsTunnelRebuild(): Boolean {
        return routingSignature() != lastRoutingSignature
    }

    @Volatile
    private var lastRoutingSignature: String = ""

    private fun routingSignature(): String {
        val offline = OmitPrefs.offlineGameMode && OmitPrefs.offlineGamePackages.isNotEmpty()
        return buildString {
            append(if (offline) "sandbox" else "split")
            append('|')
            append(OmitPrefs.bypassPackages.sorted().joinToString(","))
            append('|')
            append(if (offline) OmitPrefs.offlineGamePackages.sorted().joinToString(",") else "")
        }
    }

    override fun onRevoke() {
        OmitPrefs.setDesiredEnabled(false)
        pauseUntilEpochMs = 0L
        mainHandler.removeCallbacks(pauseTicker)
        stopVpnTunnel()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        super.onRevoke()
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(pauseTicker)
        unregisterNetworkMonitor()
        stopVpnTunnel()
        queryPool.shutdownNow()
        resolverPool.shutdownNow()
        super.onDestroy()
    }

    private fun registerNetworkMonitor() {
        try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
            OmitPrefs.updatePrivateDnsWarning(detectStrictPrivateDnsHost(this))
            val cb = object : ConnectivityManager.NetworkCallback() {
                override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        val host = linkProperties.privateDnsServerName?.takeIf { it.isNotBlank() }
                        OmitPrefs.updatePrivateDnsWarning(host)
                    }
                    // Keep a copy of the network's real resolvers for the recursive fallback.
                    val servers = try {
                        linkProperties.dnsServers.mapNotNull { it.hostAddress }
                    } catch (_: Throwable) {
                        emptyList()
                    }
                    if (servers.isNotEmpty()) lastForwarders = servers
                }
            }
            cm.registerDefaultNetworkCallback(cb)
            networkCallback = cb
        } catch (_: Throwable) {}
    }

    private fun unregisterNetworkMonitor() {
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

        lastRoutingSignature = routingSignature()

        Thread({
            BlockEngine.ensureLoaded(applicationContext)
            OmitPrefs.updatePrivateDnsWarning(detectStrictPrivateDnsHost(applicationContext))

            val pfd = try {
                buildTunnel()
            } catch (_: Throwable) {
                OmitPrefs.updateRuntimeState(ShieldState.OFF, 0)
                stopSelf()
                return@Thread
            }

            if (pfd == null || sessionGen.get() != myGen) {
                try { pfd?.close() } catch (_: Throwable) {}
                if (sessionGen.get() == myGen) {
                    OmitPrefs.updateRuntimeState(ShieldState.OFF, 0)
                    stopSelf()
                }
                return@Thread
            }

            activeTunPfd = pfd
            val rem = computePauseRemainingSec()
            if (rem > 0) {
                OmitPrefs.updateRuntimeState(ShieldState.PAUSED, rem)
            } else {
                OmitPrefs.updateRuntimeState(computeActiveShieldState(), 0)
            }
            updateForegroundNotification()

            val dohClient = DohClient(this@OmitVpnService)
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
                    val blockTtlSec = if (OmitPrefs.antiStormTtl) 300 else 15

                    // Strip HTTPS/SVCB Type 65 when Browser DoH Guard is enabled to prevent ECH/DoH bypass
                    if (!isPaused && parsedDns.qtype == 65 && OmitPrefs.blockDohCanary) {
                        val emptyNoErr = DnsPacket.buildBlockedResponse(
                            packetBuf, parsedIp.dnsOffset, parsedDns, nxdomain = false, ttlSeconds = blockTtlSec
                        )
                        writeTunPacket(fos, writeLock, IpUdpPacket.buildUdpResponse(parsedIp, emptyNoErr))
                        continue
                    }

                    val verdict = if (isPaused) {
                        BlockVerdict(VerdictType.ALLOWED, 0, "Paused (60s)")
                    } else {
                        BlockEngine.checkDomain(parsedDns.qname, parsedDns.qtype)
                    }

                    if (verdict.isBlocked) {
                        val nxdomain = verdict.type == VerdictType.BLOCKED_CANARY_NXDOMAIN
                        // BLOCKED_EMPTY keeps the same synthetic 0.0.0.0 answer but without
                        // NXDOMAIN so AAAA-only clients fall back cleanly to IPv4.
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
                        OmitPrefs.recordQuery(
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
                        OmitPrefs.recordQuery(
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

                    // Capture an immutable copy of the query for the async upstream call.
                    val dnsQueryBytes = packetBuf.copyOfRange(
                        parsedIp.dnsOffset,
                        parsedIp.dnsOffset + parsedIp.dnsLength
                    )
                    val responderIp = parsedIp
                    queryPool.execute {
                        handleUpstreamResolution(
                            myGen = myGen,
                            dohClient = dohClient,
                            fos = fos,
                            writeLock = writeLock,
                            parsedIp = responderIp,
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
                    OmitPrefs.updateRuntimeState(ShieldState.OFF, 0)
                }
            }
        }, "omit-tun-loop-$myGen").apply {
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
        var resolved = dohClient.resolve(dnsQueryBytes)
        var usedRecursive = false
        if (resolved == null) {
            // Every encrypted/plain upstream failed (captive portal, hotel Wi-Fi, ISP
            // hijack). Fall back to the OS resolver so the device never loses internet.
            resolved = resolveViaSystem(parsedDns)
            usedRecursive = resolved != null
        }
        val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNs).toInt()

        if (resolved == null) {
            val servFail = DnsPacket.buildServFailResponse(dnsQueryBytes, 0, parsedDns)
            writeTunPacket(fos, writeLock, IpUdpPacket.buildUdpResponse(parsedIp, servFail))
            OmitPrefs.recordQuery(
                domain = parsedDns.qname,
                qtype = parsedDns.qtypeName(),
                blocked = false,
                cached = false,
                cnameCloaked = false,
                reason = "Upstream Unreachable",
                latencyMs = elapsedMs
            )
            return
        }

        val (upstreamWire, providerLabel) = resolved
        val inspection = DnsPacket.inspectResponse(upstreamWire)

        // Deep CNAME Uncloaking check
        if (!isPaused && OmitPrefs.cnameUncloak && inspection != null && inspection.cnameTargets.isNotEmpty()) {
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
                    OmitPrefs.recordQuery(
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
        // Never cache a fallback answer for long: a captive portal can answer with junk.
        val effectiveTtl = if (usedRecursive) 60 else ttlSec
        synchronized(dnsCache) {
            dnsCache.put(cacheKey, CachedDnsEntry(finalWire, System.currentTimeMillis() + effectiveTtl * 1000L))
        }

        OmitPrefs.recordQuery(
            domain = parsedDns.qname,
            qtype = parsedDns.qtypeName(),
            blocked = false,
            cached = false,
            cnameCloaked = false,
            reason = providerLabel,
            latencyMs = elapsedMs
        )
    }

    /**
     * Hard fallback: ask the OS/network resolver (via InetAddress) and synthesise an
     * A/AAAA answer. Used only when every DoH provider is unreachable.
     */
    private fun resolveViaSystem(parsedDns: ParsedDnsQuery): Pair<ByteArray, String>? {
        if (parsedDns.qtype != 1 && parsedDns.qtype != 28) return null
        return try {
            val addresses = java.net.InetAddress.getAllByName(parsedDns.qname)
            val bytes = DnsPacket.buildAddressAnswer(parsedDns, addresses.map { it.address }, ttlSeconds = 60)
            if (bytes != null) Pair(bytes, "System Resolver") else null
        } catch (_: Throwable) {
            null
        }
    }

    private fun writeTunPacket(fos: FileOutputStream, lock: Any, packet: ByteArray) {
        try {
            synchronized(lock) {
                fos.write(packet)
            }
        } catch (_: Throwable) {}
    }

    private fun buildTunnel(): ParcelFileDescriptor? {
        val offlineGames = OmitPrefs.offlineGamePackages
        val useOfflineSandbox = OmitPrefs.offlineGameMode && offlineGames.isNotEmpty()

        if (useOfflineSandbox) {
            val builder = Builder()
                .setSession("Omit Offline Lock")
                .setMtu(1500)
                .setBlocking(true)
                .allowFamily(OsConstants.AF_INET)
                .allowFamily(OsConstants.AF_INET6)
                .addAddress(TUN_IPV4_GATEWAY, 30)
                .addDnsServer(TUN_IPV4_DNS)
                .addAddress(TUN_IPV6_GATEWAY, 126)
                .addDnsServer(TUN_IPV6_DNS)
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
            OmitPrefs.setOfflineGameMode(false)
        }

        return buildStandardDnsSplitTun()
    }

    private fun buildStandardDnsSplitTun(): ParcelFileDescriptor? {
        val builder = Builder()
            .setSession("Omit")
            .setMtu(1500)
            .setBlocking(true)
            .allowFamily(OsConstants.AF_INET)
            .allowFamily(OsConstants.AF_INET6)
            .addAddress(TUN_IPV4_GATEWAY, 30)
            .addDnsServer(TUN_IPV4_DNS)
            .addRoute(TUN_IPV4_DNS, 32)
            .addRoute("198.18.0.0", 15)
            .addAddress(TUN_IPV6_GATEWAY, 126)
            .addDnsServer(TUN_IPV6_DNS)
            .addRoute(TUN_IPV6_DNS, 128)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            builder.setMetered(false)
        }

        try { builder.addDisallowedApplication(packageName) } catch (_: Throwable) {}
        for (pkg in OmitPrefs.bypassPackages) {
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
        OmitPrefs.flushStats()
        OmitPrefs.updateRuntimeState(ShieldState.OFF, 0)
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
        val stopIntent = Intent(this, OmitVpnService::class.java).apply { action = ACTION_STOP }
        val stopPi = PendingIntent.getService(
            this, 2, stopIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val pausedRem = computePauseRemainingSec()
        val title = when {
            pausedRem > 0 -> "Omit · Paused (${pausedRem}s)"
            OmitPrefs.offlineGameMode && OmitPrefs.offlineGamePackages.isNotEmpty() -> "Omit · Offline Lock"
            else -> "Omit · Protected"
        }
        val intensity = FilterMetadata.intensityTitleForMask(OmitPrefs.activeCategoryMask.get())
        val upstreamName = FilterMetadata.upstreamById(OmitPrefs.upstreamId).name
        val subtitle = "$intensity · $upstreamName"

        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_qs_shield)
            .setContentTitle(title)
            .setContentText(subtitle)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setContentIntent(openPi)
            .addAction(Notification.Action.Builder(null, "Turn Off", stopPi).build())
            .build()
    }
}
