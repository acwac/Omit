package app.omit.shield.ui

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.provider.Settings
import android.webkit.JavascriptInterface
import app.omit.shield.data.FilterMetadata
import app.omit.shield.data.ShieldState
import app.omit.shield.data.OmitPrefs
import app.omit.shield.dns.BlockEngine
import app.omit.shield.service.OmitVpnService
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class OmitJsBridge(
    private val activity: MainActivity
) {
    private val appContext: Context = activity.applicationContext
    private val bgPool = Executors.newSingleThreadExecutor { r ->
        Thread(r, "omit-ui-bg").apply { isDaemon = true }
    }

    private data class CachedAppEntry(
        val pkg: String,
        val name: String,
        val initials: String,
        val color: String,
        val isSystem: Boolean
    )

    @Volatile
    private var cachedApps: List<CachedAppEntry>? = null
    private val loadingApps = AtomicBoolean(false)

    private val avatarPalette = arrayOf(
        "#3f9875", "#477bc6", "#7768d9", "#cb8a39",
        "#419b64", "#db5a51", "#3b8d99", "#8c5ea8"
    )

    @JavascriptInterface
    fun getStateJson(): String {
        return buildStateJson().toString()
    }

    @JavascriptInterface
    fun getInstalledAppsJson(): String {
        val current = cachedApps
        if (current != null) {
            return serializeApps(current).toString()
        }
        triggerLoadAppsAsync()
        return "[]"
    }

    fun triggerLoadAppsAsync() {
        if (!loadingApps.compareAndSet(false, true)) return
        bgPool.execute {
            try {
                val pm = appContext.packageManager
                val rawApps = pm.getInstalledApplications(0)
                val myPkg = appContext.packageName
                val entries = ArrayList<CachedAppEntry>(rawApps.size)

                for (ai in rawApps) {
                    val pkg = ai.packageName ?: continue
                    if (pkg == myPkg) continue
                    val hasLaunch = pm.getLaunchIntentForPackage(pkg) != null
                    val isSys = (ai.flags and ApplicationInfo.FLAG_SYSTEM) != 0 &&
                        (ai.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0
                    if (!hasLaunch && isSys) continue

                    val label = try {
                        pm.getApplicationLabel(ai).toString().trim().takeUnless { it.isEmpty() } ?: pkg
                    } catch (_: Throwable) {
                        pkg
                    }
                    val words = label.split(Regex("\\s+")).filter { it.isNotEmpty() }
                    val initials = when {
                        words.size >= 2 -> "${words[0].first().uppercaseChar()}${words[1].first().uppercaseChar()}"
                        label.isNotEmpty() -> label.first().uppercaseChar().toString()
                        else -> "A"
                    }
                    val color = avatarPalette[(pkg.hashCode() and 0x7FFFFFFF) % avatarPalette.size]

                    entries.add(
                        CachedAppEntry(
                            pkg = pkg,
                            name = label,
                            initials = initials,
                            color = color,
                            isSystem = isSys
                        )
                    )
                }

                entries.sortWith(compareBy<CachedAppEntry> { it.isSystem }.thenBy { it.name.lowercase() })
                cachedApps = entries
                activity.pushAppsJson(serializeApps(entries).toString())
            } catch (_: Throwable) {
            } finally {
                loadingApps.set(false)
            }
        }
    }

    private fun serializeApps(entries: List<CachedAppEntry>): JSONArray {
        val arr = JSONArray()
        for (item in entries) {
            val modeCode = OmitPrefs.getAppMode(item.pkg)
            val modeStr = when (modeCode) {
                1 -> "Bypassed"
                2 -> "Offline Lock"
                else -> "Protected"
            }
            val obj = JSONObject()
            obj.put("pkg", item.pkg)
            obj.put("name", item.name)
            obj.put("initials", item.initials)
            obj.put("color", item.color)
            obj.put("isSystem", item.isSystem)
            obj.put("mode", modeStr)
            obj.put("hasIcon", true)
            arr.put(obj)
        }
        return arr
    }

    @JavascriptInterface
    fun setDarkMode(dark: Boolean) {
        OmitPrefs.setDarkModePref(dark)
        activity.runOnUiThread {
            activity.applySystemBarAppearance(dark)
            activity.pushCurrentState()
        }
    }

    @JavascriptInterface
    fun toggleShield() {
        activity.runOnUiThread {
            activity.requestToggleVpn()
        }
    }

    @JavascriptInterface
    fun pause60s() {
        activity.runOnUiThread {
            if (OmitPrefs.shieldState != ShieldState.OFF) {
                val svc = Intent(appContext, OmitVpnService::class.java).apply {
                    action = OmitVpnService.ACTION_PAUSE_60S
                }
                appContext.startService(svc)
            }
        }
    }

    @JavascriptInterface
    fun resumeShield() {
        activity.runOnUiThread {
            if (OmitPrefs.shieldState != ShieldState.OFF) {
                val svc = Intent(appContext, OmitVpnService::class.java).apply {
                    action = OmitVpnService.ACTION_RESUME
                }
                appContext.startService(svc)
            } else {
                activity.requestStartVpn()
            }
        }
    }

    @JavascriptInterface
    fun setOfflineGameMode(enabled: Boolean): Boolean {
        if (enabled && OmitPrefs.offlineGamePackages.isEmpty()) {
            return false
        }
        OmitPrefs.setOfflineGameMode(enabled)
        activity.runOnUiThread {
            if (OmitPrefs.shieldState != ShieldState.OFF) {
                reloadVpnIfRunning()
            } else if (enabled) {
                activity.requestStartVpn()
            }
        }
        return true
    }

    @JavascriptInterface
    fun selectIntensity(index: Int) {
        val nextMask = when (index) {
            0 -> FilterMetadata.MASK_LIGHT
            1 -> FilterMetadata.MASK_BALANCED
            2 -> FilterMetadata.MASK_STRICT
            else -> return
        }
        OmitPrefs.setCategoryMask(nextMask)
        reloadVpnIfRunning()
    }

    @JavascriptInterface
    fun toggleCategory(index: Int) {
        if (index !in FilterMetadata.CATEGORY_BITS.indices) return
        OmitPrefs.toggleCategoryBit(FilterMetadata.CATEGORY_BITS[index])
        reloadVpnIfRunning()
    }

    @JavascriptInterface
    fun toggleEngine(index: Int) {
        if (index !in FilterMetadata.ENGINE_KEYS.indices) return
        val key = FilterMetadata.ENGINE_KEYS[index]
        val cur = OmitPrefs.getEngineToggle(key)
        OmitPrefs.setEngineToggle(key, !cur)
        reloadVpnIfRunning()
    }

    @JavascriptInterface
    fun setUpstreamDns(index: Int, customUrl: String) {
        val spec = FilterMetadata.upstreamByIndex(index)
        OmitPrefs.setUpstream(spec.id, customUrl.takeIf { it.isNotBlank() })
        reloadVpnIfRunning()
    }

    @JavascriptInterface
    fun addAllowDomain(domain: String): Boolean {
        val clean = OmitPrefs.normalizeDomain(domain)
        if (clean.length < 3 || !clean.contains('.')) return false
        OmitPrefs.addAllowDomain(clean)
        reloadVpnIfRunning()
        return true
    }

    @JavascriptInterface
    fun removeAllowDomain(domain: String) {
        OmitPrefs.removeAllowDomain(domain)
        reloadVpnIfRunning()
    }

    @JavascriptInterface
    fun addBlockDomain(domain: String): Boolean {
        val clean = OmitPrefs.normalizeDomain(domain)
        if (clean.length < 3 || !clean.contains('.')) return false
        OmitPrefs.addBlockDomain(clean)
        reloadVpnIfRunning()
        return true
    }

    @JavascriptInterface
    fun removeBlockDomain(domain: String) {
        OmitPrefs.removeBlockDomain(domain)
        reloadVpnIfRunning()
    }

    @JavascriptInterface
    fun removeCustomRuleForDomain(domain: String) {
        OmitPrefs.removeAllowDomain(domain)
        OmitPrefs.removeBlockDomain(domain)
        reloadVpnIfRunning()
    }

    @JavascriptInterface
    fun addCustomListUrl(url: String): Boolean {
        val trimmed = url.trim()
        if (!trimmed.startsWith("https://") && !trimmed.startsWith("http://")) return false
        OmitPrefs.addCustomListUrl(trimmed)
        return true
    }

    @JavascriptInterface
    fun removeCustomListUrl(url: String) {
        OmitPrefs.removeCustomListUrl(url)
    }

    @JavascriptInterface
    fun syncLists() {
        if (OmitPrefs.isSyncingLists) return
        OmitPrefs.setSyncing(true)
        bgPool.execute {
            val res = BlockEngine.syncOnlineLists(appContext)
            OmitPrefs.setSyncing(false)
            res.onSuccess { total ->
                reloadVpnIfRunning()
                activity.pushToast("Synced ${String.format("%,d", total)} rules")
            }.onFailure {
                activity.pushToast("Offline — using built-in rules")
            }
        }
    }

    @JavascriptInterface
    fun setAppMode(pkg: String, mode: String) {
        if (pkg.isBlank()) return
        val modeInt = when (mode) {
            "Bypassed" -> 1
            "Offline Lock" -> 2
            else -> 0
        }
        OmitPrefs.setAppMode(pkg, modeInt)
        cachedApps?.let {
            activity.pushAppsJson(serializeApps(it).toString())
        }
        reloadVpnIfRunning()
    }

    @JavascriptInterface
    fun setLogging(enabled: Boolean) {
        OmitPrefs.setLiveLogEnabled(enabled)
    }

    @JavascriptInterface
    fun clearLogs() {
        OmitPrefs.clearLogs()
    }

    @JavascriptInterface
    fun getSpyStatsJson(): String {
        val arr = org.json.JSONArray()
        for (s in OmitPrefs.getSpyStatsSnapshot().take(20)) {
            val o = org.json.JSONObject()
            o.put("packageName", s.packageName)
            o.put("appName", s.appName)
            o.put("spyCount", s.spyCount)
            o.put("bgWakeups", s.bgWakeups)
            o.put("lastDomain", s.lastDomain)
            arr.put(o)
        }
        return arr.toString()
    }

    @JavascriptInterface
    fun openExternalUrl(url: String) {
        if (url.isBlank()) return
        try {
            val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))
            activity.startActivity(intent)
        } catch (_: Throwable) {}
    }

    @JavascriptInterface
    fun copyToClipboard(text: String) {
        try {
            val cm = activity.getSystemService(Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager ?: return
            cm.setPrimaryClip(android.content.ClipData.newPlainText("Omit", text))
        } catch (_: Throwable) {}
    }

    @JavascriptInterface
    fun getBurnerEmailJson(forceNew: Boolean): String {
        return app.omit.shield.net.BurnerManager.getOrCreateEmail(activity, forceNew).toString()
    }

    @JavascriptInterface
    fun fetchBurnerEmailAsync(forceNew: Boolean) {
        bgPool.execute {
            val emailObj = app.omit.shield.net.BurnerManager.getOrCreateEmail(activity, forceNew)
            val inboxArr = app.omit.shield.net.BurnerManager.fetchEmailInbox(activity)
            val payload = JSONObject().put("type", "email").put("email", emailObj).put("inbox", inboxArr).toString()
            val escaped = JSONObject.quote(payload)
            activity.runOnUiThread {
                activity.evalJs("window.__onBurnerData && window.__onBurnerData($escaped)")
            }
        }
    }

    @JavascriptInterface
    fun getBurnerInboxJson(): String {
        return app.omit.shield.net.BurnerManager.fetchEmailInbox(activity).toString()
    }

    /** Returns true only when the proxy actually bound a port. */
    @JavascriptInterface
    fun setHomeShieldEnabled(enabled: Boolean): Boolean {
        if (enabled) {
            // Omit excludes itself from its own TUN, so the LAN proxy's sockets never loop
            // back into the VPN and need no protect() call.
            val bound = app.omit.shield.net.HomeShieldServer.start()
            OmitPrefs.setHomeShieldEnabled(bound)
            return bound
        }
        app.omit.shield.net.HomeShieldServer.stop()
        OmitPrefs.setHomeShieldEnabled(false)
        return true
    }

    /** False when there is no Wi-Fi address other devices could reach. */
    @JavascriptInterface
    fun hasLanAddress(): Boolean {
        val ip = app.omit.shield.net.HomeShieldServer.getLanIp()
        return ip.isNotEmpty() && ip != "127.0.0.1"
    }

    @JavascriptInterface
    fun getHomeShieldInfoJson(): String {
        val o = org.json.JSONObject()
        val server = app.omit.shield.net.HomeShieldServer
        val running = server.isRunning
        // Never advertise a port unless the socket is actually listening.
        o.put("enabled", running)
        o.put("lanIp", if (running) server.getLanIp() else "")
        o.put("port", if (running) server.activePort else 0)
        o.put("blockedCount", server.blockedCount.get())
        return o.toString()
    }

    @JavascriptInterface
    fun getTamperWarning(): String {
        return app.omit.shield.net.WifiTamperGuard.activeWarning
    }

    @JavascriptInterface
    fun applyOtaPatch(otaUrl: String, expectedSha256: String, patchCode: Int) {
        if (!otaUrl.startsWith("https://")) {
            activity.pushToast("OTA update blocked: insecure URL")
            return
        }
        val nativeCode = installedVersionCode()
        if (patchCode < nativeCode) {
            activity.pushToast("This patch is older than the installed app")
            return
        }
        Thread {
            try {
                activity.pushToast("Downloading UI patch...")
                val conn = (java.net.URL(otaUrl).openConnection() as java.net.HttpURLConnection).apply {
                    connectTimeout = 10000
                    readTimeout = 20000
                    instanceFollowRedirects = true
                }
                val zipBytes = conn.inputStream.use { it.readBytes() }
                if (zipBytes.isEmpty()) {
                    activity.pushToast("OTA download was empty")
                    return@Thread
                }
                if (expectedSha256.isNotBlank()) {
                    val digest = java.security.MessageDigest.getInstance("SHA-256").digest(zipBytes)
                    val hex = digest.joinToString("") { "%02x".format(it) }
                    if (!hex.equals(expectedSha256, ignoreCase = true)) {
                        activity.pushToast("OTA checksum mismatch — update cancelled")
                        return@Thread
                    }
                }
                val root = java.io.File(activity.filesDir, "ota-ui")
                val staging = java.io.File(activity.filesDir, "ota-ui-staging")
                staging.deleteRecursively()
                staging.mkdirs()
                val rootPath = staging.canonicalPath + java.io.File.separator
                var extracted = 0
                java.util.zip.ZipInputStream(java.io.ByteArrayInputStream(zipBytes)).use { zis ->
                    var entry = zis.nextEntry
                    while (entry != null) {
                        val outFile = java.io.File(staging, entry.name)
                        // Reject zip-slip paths that escape the staging directory.
                        if (!outFile.canonicalPath.startsWith(rootPath)) {
                            activity.pushToast("OTA patch contained unsafe paths")
                            staging.deleteRecursively()
                            return@Thread
                        }
                        if (entry.isDirectory) {
                            outFile.mkdirs()
                        } else {
                            outFile.parentFile?.mkdirs()
                            java.io.FileOutputStream(outFile).use { fos -> zis.copyTo(fos) }
                            extracted++
                        }
                        zis.closeEntry()
                        entry = zis.nextEntry
                    }
                }
                if (extracted == 0 || !java.io.File(staging, "index.html").isFile) {
                    staging.deleteRecursively()
                    activity.pushToast("OTA patch missing index.html")
                    return@Thread
                }
                root.deleteRecursively()
                if (!staging.renameTo(root)) {
                    staging.copyRecursively(root, overwrite = true)
                    staging.deleteRecursively()
                }
                activity.getSharedPreferences("omit_ota_prefs", Context.MODE_PRIVATE)
                    .edit().putInt("patch_code", patchCode).apply()
                activity.runOnUiThread {
                    activity.pushToast("Updated! Reloading...")
                    activity.recreate()
                }
            } catch (_: Throwable) {
                activity.pushToast("OTA update failed")
            }
        }.start()
    }

    private fun installedVersionCode(): Int {
        return try {
            val info = appContext.packageManager.getPackageInfo(appContext.packageName, 0)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                info.longVersionCode.toInt()
            } else {
                @Suppress("DEPRECATION")
                info.versionCode
            }
        } catch (_: Throwable) {
            0
        }
    }

    @JavascriptInterface
    fun getVersionCode(): Int = installedVersionCode()

    @JavascriptInterface
    fun checkUpdateJson(): String {
        return fetchUpdateJson()?.toString() ?: "{}"
    }

    @JavascriptInterface
    fun checkUpdateAsync(currentVersionCode: Int) {
        bgPool.execute {
            val payload = fetchUpdateJson()
            val info = try {
                payload?.let { parseUpdateInfo(it, currentVersionCode) }
            } catch (_: Throwable) {
                null
            }
            val literal = if (info != null) JSONObject.quote(info.toString()) else "null"
            activity.runOnUiThread {
                activity.evalJs(
                    "window.__omitOnUpdate && window.__omitOnUpdate($literal)"
                )
            }
        }
    }

    @JavascriptInterface
    fun dismissUpdate(versionCode: Int) {
        activity.getSharedPreferences("omit_ota_prefs", Context.MODE_PRIVATE)
            .edit().putInt("dismissed_update", versionCode).apply()
    }

    private fun fetchUpdateJson(): JSONObject? {
        return try {
            val url = java.net.URL(VERSION_JSON_URL)
            val conn = (url.openConnection() as java.net.HttpURLConnection).apply {
                connectTimeout = 7000
                readTimeout = 7000
                instanceFollowRedirects = true
                setRequestProperty("Cache-Control", "no-cache")
            }
            val text = conn.inputStream.bufferedReader().use { it.readText() }
            conn.disconnect()
            JSONObject(text)
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * Accepts both the flat v2 JSON (version/versionCode/...) and the nested
     * {"android": {...}} form, so either server schema keeps working.
     */
    private fun parseUpdateInfo(raw: JSONObject, currentVersionCode: Int): JSONObject? {
        val android = raw.optJSONObject("android") ?: raw
        val versionCode = android.optInt("versionCode", raw.optInt("versionCode", 0))
        val versionName = android.optString("versionName", raw.optString("version", ""))
        if (versionCode <= currentVersionCode || versionName.isBlank()) return null
        val dismissed = activity.getSharedPreferences("omit_ota_prefs", Context.MODE_PRIVATE)
            .getInt("dismissed_update", 0)
        if (dismissed >= versionCode) return null
        val minNative = raw.optInt("minNativeCode", 0)
        val otaUrl = android.optString("otaUrl", raw.optString("otaUrl", ""))
        // A UI-only patch is only offered when it targets this exact native version.
        val otaCompatible = otaUrl.isNotBlank() &&
            minNative <= installedVersionCode() &&
            versionCode >= installedVersionCode()
        return JSONObject()
            .put("version", versionName)
            .put("versionCode", versionCode)
            .put("notes", android.optString("notes", raw.optString("notes", "")))
            .put("apkUrl", android.optString("apkUrl", raw.optString("androidApkUrl", "")))
            .put("otaUrl", if (otaCompatible) otaUrl else "")
            .put("otaSha256", raw.optString("otaSha256", ""))
            .put("otaCode", raw.optInt("otaCode", versionCode))
    }

    @JavascriptInterface
    fun downloadAndInstallUpdate(apkUrl: String) {
        val targetUrl = if (apkUrl.isBlank()) "https://acwac.github.io/Omit/downloads/Omit.apk" else apkUrl
        Thread {
            try {
                activity.pushToast("Downloading Omit update...")
                val updatesDir = java.io.File(activity.cacheDir, "updates").apply { mkdirs() }
                val apkFile = java.io.File(updatesDir, "Omit-update.apk")
                val conn = (java.net.URL(targetUrl).openConnection() as java.net.HttpURLConnection).apply {
                    connectTimeout = 10000
                    readTimeout = 30000
                }
                conn.inputStream.use { input ->
                    java.io.FileOutputStream(apkFile).use { output ->
                        input.copyTo(output)
                    }
                }
                activity.runOnUiThread {
                    if (android.os.Build.VERSION.SDK_INT >= 26 && !activity.packageManager.canRequestPackageInstalls()) {
                        val permIntent = android.content.Intent(
                            android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                            android.net.Uri.parse("package:${activity.packageName}")
                        ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                        activity.startActivity(permIntent)
                    }
                    val contentUri = android.net.Uri.parse("content://app.omit.shield.updateprovider/Omit-update.apk")
                    val installIntent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                        setDataAndType(contentUri, "application/vnd.android.package-archive")
                        addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    activity.startActivity(installIntent)
                }
            } catch (_: Throwable) {
                activity.pushToast("Update download failed")
            }
        }.start()
    }

    @JavascriptInterface
    fun openPrivateDnsSettings() {
        activity.runOnUiThread {
            val intents = listOf(
                Intent(Settings.ACTION_WIRELESS_SETTINGS),
                Intent(Settings.ACTION_WIFI_SETTINGS),
                Intent(Settings.ACTION_SETTINGS)
            )
            for (intent in intents) {
                try {
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    activity.startActivity(intent)
                    break
                } catch (_: Throwable) {}
            }
        }
    }

    private fun reloadVpnIfRunning() {
        if (OmitPrefs.shieldState != ShieldState.OFF) {
            val svc = Intent(appContext, OmitVpnService::class.java).apply {
                action = OmitVpnService.ACTION_RELOAD
            }
            appContext.startService(svc)
        }
    }

    fun shutdown() {
        bgPool.shutdownNow()
    }

    companion object {
        private const val VERSION_JSON_URL = "https://acwac.github.io/Omit/version.json"

        fun buildStateJson(): JSONObject {
            val nowMs = System.currentTimeMillis()
            val obj = JSONObject()

            obj.put("dark", OmitPrefs.darkMode)

            val shieldStr = when (OmitPrefs.shieldState) {
                ShieldState.OFF -> "off"
                ShieldState.PAUSED -> "paused"
                ShieldState.OFFLINE_SANDBOX -> "game"
                ShieldState.PROTECTED -> "protected"
            }
            obj.put("shield", shieldStr)

            val countdownVal: Int = if (OmitPrefs.shieldState == ShieldState.PAUSED) {
                OmitPrefs.pauseRemainingSec.coerceAtLeast(1)
            } else {
                60
            }
            obj.put("countdown", countdownVal)

            val mask = OmitPrefs.activeCategoryMask.get()
            obj.put("intensity", FilterMetadata.intensityTitleForMask(mask))

            val catsArr = JSONArray()
            for (bit in FilterMetadata.CATEGORY_BITS) {
                catsArr.put((mask and bit) != 0)
            }
            obj.put("categories", catsArr)

            val engArr = JSONArray()
            for (key in FilterMetadata.ENGINE_KEYS) {
                engArr.put(OmitPrefs.getEngineToggle(key))
            }
            obj.put("engine", engArr)

            obj.put("dns", FilterMetadata.upstreamById(OmitPrefs.upstreamId).index)
            obj.put("customDohUrl", OmitPrefs.customDohUrl)
            obj.put("gameMode", OmitPrefs.offlineGameMode)
            obj.put("offlineGameCount", OmitPrefs.offlineGamePackages.size)
            obj.put("blockedCount", OmitPrefs.blockedQueries.get())
            obj.put("totalQueries", OmitPrefs.totalQueries.get())

            val ruleCount: Int = BlockEngine.getRuleCount().let {
                if (it > 0) it else OmitPrefs.compiledRuleCount
            }
            obj.put("activeRules", ruleCount)
            obj.put("privateDnsHost", OmitPrefs.privateDnsHostWarning ?: "")

            val allowArr = JSONArray()
            for (d in OmitPrefs.customAllowDomains.sorted()) allowArr.put(d)
            obj.put("allowDomains", allowArr)

            val blockArr = JSONArray()
            for (d in OmitPrefs.customBlockDomains.sorted()) blockArr.put(d)
            obj.put("blockDomains", blockArr)

            val urlsArr = JSONArray()
            for (u in OmitPrefs.customListUrls) urlsArr.put(u)
            obj.put("customUrls", urlsArr)

            obj.put("logging", OmitPrefs.liveLogEnabled)
            obj.put("syncing", OmitPrefs.isSyncingLists)

            val logsSnapshot = OmitPrefs.getLogsSnapshot()
            val logsArr = JSONArray()
            val recentBlockedArr = JSONArray()
            val seenRecent = HashSet<String>(4)
            val allowSet = OmitPrefs.customAllowDomains
            val blockSet = OmitPrefs.customBlockDomains

            for (entry in logsSnapshot) {
                val relTime = formatRelativeTime(nowMs, entry.timestampMs)
                val logObj = JSONObject()
                logObj.put("id", entry.id)
                logObj.put("domain", entry.domain)
                logObj.put("time", relTime)
                logObj.put("status", if (entry.blocked) "Blocked" else "Allowed")
                logObj.put("category", entry.reason)
                if (allowSet.contains(entry.domain) || blockSet.contains(entry.domain)) {
                    logObj.put("overridden", true)
                }
                logsArr.put(logObj)

                if (entry.blocked && recentBlockedArr.length() < 3 && seenRecent.add(entry.domain)) {
                    val rb = JSONObject()
                    rb.put("domain", entry.domain)
                    rb.put("category", entry.reason)
                    rb.put("time", relTime)
                    recentBlockedArr.put(rb)
                }
            }

            obj.put("recentBlocked", recentBlockedArr)
            obj.put("logs", logsArr)
            return obj
        }

        private fun formatRelativeTime(nowMs: Long, tsMs: Long): String {
            val diffSec = ((nowMs - tsMs) / 1000L).coerceAtLeast(0L)
            return when {
                diffSec < 3L -> "Now"
                diffSec < 60L -> "${diffSec}s"
                diffSec < 3600L -> "${diffSec / 60L}m"
                else -> "${diffSec / 3600L}h"
            }
        }
    }
}
