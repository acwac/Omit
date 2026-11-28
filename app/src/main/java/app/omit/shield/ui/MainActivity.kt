package app.omit.shield.ui

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsetsController
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.TextView
import app.omit.shield.data.ShieldState
import app.omit.shield.data.OmitPrefs
import app.omit.shield.dns.BlockEngine
import app.omit.shield.service.OmitVpnService
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class MainActivity : Activity() {

    companion object {
        private const val REQ_VPN_PERMISSION = 701
        private const val REQ_POST_NOTIF = 702
        private const val VIRTUAL_ORIGIN = "https://app.omit.local"
    }

    private var webView: WebView? = null
    private var jsBridge: OmitJsBridge? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    private var pageReady = false
    private var firstLoadHandled = false
    private var statePushScheduled = false
    private var lastPushMs = 0L

    private val iconPngCache = object : LruCache<String, ByteArray>(140) {}

    /** Validated OTA UI bundle (filesDir/ota-ui). Null when none/old patch is installed. */
    private var otaUiDir: java.io.File? = null

    private val prefsListener: () -> Unit = {
        scheduleStatePush()
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        OmitPrefs.init(applicationContext)

        otaUiDir = resolveOtaUiDir()

        val initialDark = OmitPrefs.darkMode

        // Warm compiled 209k-rule binary table off the UI thread
        Thread({
            try {
                BlockEngine.ensureLoaded(applicationContext)
                OmitPrefs.updatePrivateDnsWarning(OmitVpnService.detectStrictPrivateDnsHost(applicationContext))
                scheduleStatePush()
            } catch (_: Throwable) {}
        }, "omit-warm-rules").start()

        val bridge = OmitJsBridge(this)
        jsBridge = bridge

        val createdWebView = try {
            WebView(this).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                setBackgroundColor(if (initialDark) Color.parseColor("#111512") else Color.parseColor("#F4F6F3"))
                isVerticalScrollBarEnabled = false
                isHorizontalScrollBarEnabled = false
                overScrollMode = View.OVER_SCROLL_NEVER
                isLongClickable = false
                setOnLongClickListener { true }

                settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    databaseEnabled = false
                    allowFileAccess = false
                    allowContentAccess = false
                    blockNetworkLoads = false
                    cacheMode = WebSettings.LOAD_NO_CACHE
                    textZoom = 100
                    setSupportZoom(false)
                    builtInZoomControls = false
                    displayZoomControls = false
                    mediaPlaybackRequiresUserGesture = true
                }

                addJavascriptInterface(bridge, "OmitAndroid")
                // Legacy alias: an OTA-updated bundle from an older build still binds this name.
                addJavascriptInterface(bridge, "VellumAndroid")

                webViewClient = object : WebViewClient() {
                    override fun shouldInterceptRequest(
                        view: WebView?,
                        request: WebResourceRequest?
                    ): WebResourceResponse? {
                        val uri = request?.url ?: return blockedResponse()
                        return interceptUri(uri)
                    }

                    @Deprecated("Deprecated in Java")
                    override fun shouldInterceptRequest(
                        view: WebView?,
                        url: String?
                    ): WebResourceResponse? {
                        val parsed = try {
                            if (url != null) Uri.parse(url) else null
                        } catch (_: Throwable) {
                            null
                        } ?: return blockedResponse()
                        return interceptUri(parsed)
                    }

                    override fun onPageFinished(view: WebView?, url: String?) {
                        super.onPageFinished(view, url)
                        pageReady = true
                        pushCurrentState()
                        bridge.triggerLoadAppsAsync()

                        if (!firstLoadHandled) {
                            firstLoadHandled = true
                            handlePostUiReadyStartup()
                        }
                    }
                }
            }
        } catch (_: Throwable) {
            null
        }

        if (createdWebView != null) {
            webView = createdWebView
            setContentView(createdWebView)
            app.omit.shield.net.WifiTamperGuard.register(this)
            // Always apply system bar appearance AFTER setContentView so DecorView exists on Android 11-16
            applySystemBarAppearance(initialDark)
            createdWebView.loadUrl("$VIRTUAL_ORIGIN/index.html")
        } else {
            val fallbackView = TextView(this).apply {
                text = "Omit requires Android System WebView to display its interface.\nPlease enable or update Android System WebView in Play Store."
                gravity = Gravity.CENTER
                setPadding(64, 64, 64, 64)
                textSize = 16f
                setTextColor(if (initialDark) Color.parseColor("#EDF3EE") else Color.parseColor("#14201B"))
                setBackgroundColor(if (initialDark) Color.parseColor("#111512") else Color.parseColor("#F4F6F3"))
            }
            setContentView(fallbackView)
            applySystemBarAppearance(initialDark)
        }
    }

    /**
     * An OTA bundle is only trusted when it contains index.html and was written for a
     * patch code >= the installed native code. Older patches are ignored so a stale
     * JS bundle can never shadow a freshly-installed APK.
     */
    private fun resolveOtaUiDir(): java.io.File? {
        return try {
            val dir = java.io.File(filesDir, "ota-ui")
            if (!dir.isDirectory) return null
            if (!java.io.File(dir, "index.html").isFile) return null
            val prefs = getSharedPreferences("omit_ota_prefs", MODE_PRIVATE)
            val patchCode = prefs.getInt("patch_code", 0)
            if (patchCode < installedVersionCode()) return null
            dir
        } catch (_: Throwable) {
            null
        }
    }

    private fun installedVersionCode(): Int {
        return try {
            val info = packageManager.getPackageInfo(packageName, 0)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                info.longVersionCode.toInt()
            } else {
                @Suppress("DEPRECATION")
                info.versionCode
            }
        } catch (_: Throwable) {
            0
        }
    }

    private fun handlePostUiReadyStartup() {
        if (isFinishing || isDestroyed) return
        if (OmitPrefs.consumeFirstLaunchAutoStart()) {
            mainHandler.postDelayed({
                if (!isFinishing && !isDestroyed) {
                    requestStartVpn()
                }
            }, 450L)
        } else if (OmitPrefs.desiredEnabled && OmitPrefs.shieldState == ShieldState.OFF) {
            val canStartSilently = try {
                VpnService.prepare(this) == null
            } catch (_: Throwable) {
                false
            }
            if (canStartSilently) {
                startVpnServiceNow()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        try {
            webView?.onResume()
            webView?.resumeTimers()
        } catch (_: Throwable) {}
        OmitPrefs.updatePrivateDnsWarning(OmitVpnService.detectStrictPrivateDnsHost(applicationContext))
        OmitPrefs.addListener(prefsListener)
        applySystemBarAppearance(OmitPrefs.darkMode)
        if (pageReady) {
            pushCurrentState()
        }
    }

    override fun onPause() {
        OmitPrefs.removeListener(prefsListener)
        mainHandler.removeCallbacksAndMessages(null)
        statePushScheduled = false
        try {
            webView?.pauseTimers()
            webView?.onPause()
        } catch (_: Throwable) {}
        super.onPause()
    }

    override fun onDestroy() {
        OmitPrefs.removeListener(prefsListener)
        mainHandler.removeCallbacksAndMessages(null)
        jsBridge?.shutdown()
        try {
            webView?.removeJavascriptInterface("OmitAndroid")
            webView?.removeJavascriptInterface("VellumAndroid")
            webView?.destroy()
        } catch (_: Throwable) {}
        webView = null
        super.onDestroy()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        val wv = webView
        if (!pageReady || wv == null) {
            finish()
            return
        }
        try {
            wv.evaluateJavascript("(function(){ var f = window.__omitHandleBack; return !!(f && f()); })()") { result ->
                if (result != "true") {
                    finish()
                }
            }
        } catch (_: Throwable) {
            finish()
        }
    }

    fun applySystemBarAppearance(isDark: Boolean) {
        try {
            val statusBg = if (isDark) Color.parseColor("#111512") else Color.parseColor("#F4F6F3")
            val navBg = if (isDark) Color.parseColor("#1A1F1C") else Color.parseColor("#FFFFFF")
            window?.statusBarColor = statusBg
            window?.navigationBarColor = navBg
            webView?.setBackgroundColor(statusBg)

            val decor = window?.decorView ?: return
            decor.post {
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        val ctrl = decor.windowInsetsController
                        val mask = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
                            WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
                        ctrl?.setSystemBarsAppearance(if (isDark) 0 else mask, mask)
                    } else {
                        @Suppress("DEPRECATION")
                        var vis = decor.systemUiVisibility
                        @Suppress("DEPRECATION")
                        if (!isDark) {
                            vis = vis or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                vis = vis or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
                            }
                        } else {
                            vis = vis and View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR.inv()
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                vis = vis and View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR.inv()
                            }
                        }
                        @Suppress("DEPRECATION")
                        decor.systemUiVisibility = vis
                    }
                } catch (_: Throwable) {}
            }
        } catch (_: Throwable) {}
    }

    fun requestToggleVpn() {
        if (OmitPrefs.shieldState != ShieldState.OFF) {
            try {
                val stopIntent = Intent(this, OmitVpnService::class.java).apply {
                    action = OmitVpnService.ACTION_STOP
                }
                startService(stopIntent)
            } catch (_: Throwable) {}
        } else {
            requestStartVpn()
        }
    }

    fun requestStartVpn() {
        try {
            val prepIntent = VpnService.prepare(this)
            if (prepIntent != null) {
                @Suppress("DEPRECATION")
                startActivityForResult(prepIntent, REQ_VPN_PERMISSION)
            } else {
                startVpnServiceNow()
            }
        } catch (_: Throwable) {
            pushToast("Unable to open VPN dialog on this device")
        }
    }

    private fun startVpnServiceNow() {
        try {
            OmitPrefs.setDesiredEnabled(true)
            val svc = Intent(this, OmitVpnService::class.java).apply {
                action = OmitVpnService.ACTION_START
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(svc)
            } else {
                startService(svc)
            }
        } catch (_: Throwable) {
            pushToast("Could not start VPN service")
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_VPN_PERMISSION) {
            if (resultCode == RESULT_OK) {
                startVpnServiceNow()
                maybeRequestNotificationPermission()
            } else {
                OmitPrefs.setDesiredEnabled(false)
                pushToast("Tap the shield anytime to turn on protection")
                pushCurrentState()
            }
        }
    }

    private fun maybeRequestNotificationPermission() {
        // Zero notification permission prompts
    }

    private fun scheduleStatePush() {
        mainHandler.post {
            if (!pageReady || statePushScheduled) return@post
            val elapsed = System.currentTimeMillis() - lastPushMs
            val delay = if (elapsed >= 240L) 0L else (240L - elapsed)
            statePushScheduled = true
            mainHandler.postDelayed({
                statePushScheduled = false
                pushCurrentState()
            }, delay)
        }
    }

    fun evalJs(js: String) {
        mainHandler.post {
            try {
                webView?.evaluateJavascript(js, null)
            } catch (_: Throwable) {}
        }
    }

    fun pushCurrentState() {
        val wv = webView ?: return
        if (!pageReady) return
        lastPushMs = System.currentTimeMillis()
        try {
            val json = OmitJsBridge.buildStateJson().toString()
            wv.evaluateJavascript(
                "window.__omitOnNativeState && window.__omitOnNativeState($json);",
                null
            )
        } catch (_: Throwable) {}
    }

    fun pushAppsJson(appsJsonArray: String) {
        mainHandler.post {
            val wv = webView ?: return@post
            if (!pageReady) return@post
            try {
                wv.evaluateJavascript(
                    "window.__omitOnNativeApps && window.__omitOnNativeApps($appsJsonArray);",
                    null
                )
            } catch (_: Throwable) {}
        }
    }

    fun pushToast(msg: String) {
        mainHandler.post {
            val wv = webView ?: return@post
            if (!pageReady) return@post
            try {
                val safeLiteral = JSONObject.quote(msg)
                wv.evaluateJavascript(
                    "window.__omitOnToast && window.__omitOnToast($safeLiteral);",
                    null
                )
            } catch (_: Throwable) {}
        }
    }

    private fun interceptUri(uri: Uri): WebResourceResponse {
        // Hostname the web UI uses for native app icons. Keep in sync with
        // App.tsx's https://app.omit.local/app-icon/<package> URL.
        if (uri.scheme != "https" || uri.host != "app.omit.local") {
            return blockedResponse()
        }
        val rawPath = uri.path ?: "/"
        if (rawPath.startsWith("/app-icon/")) {
            val pkg = Uri.decode(rawPath.removePrefix("/app-icon/")).trim()
            return serveAppIconPng(pkg)
        }
        val relPath = when {
            rawPath == "/" || rawPath == "/index.html" -> "index.html"
            else -> rawPath.trimStart('/')
        }
        // A validated OTA UI patch in filesDir/ota-ui wins over the APK's bundled assets.
        if (otaUiDir != null) {
            val candidate = java.io.File(otaUiDir!!, relPath)
            if (candidate.isFile && candidate.canonicalPath.startsWith(otaUiDir!!.canonicalPath)) {
                return serveFileAsset(candidate)
            }
        }
        return serveBundledAsset("ui/$relPath")
    }

    private fun serveFileAsset(file: java.io.File): WebResourceResponse {
        return try {
            val mime = mimeForPath(file.name)
            val encoding = if (isTextMime(mime)) "UTF-8" else null
            val headers = mapOf(
                "Cache-Control" to "no-cache",
                "Access-Control-Allow-Origin" to VIRTUAL_ORIGIN
            )
            WebResourceResponse(mime, encoding, 200, "OK", headers, java.io.FileInputStream(file))
        } catch (_: Throwable) {
            notFoundResponse()
        }
    }

    private fun mimeForPath(name: String): String = when {
        name.endsWith(".html") -> "text/html"
        name.endsWith(".css") -> "text/css"
        name.endsWith(".js") || name.endsWith(".mjs") -> "application/javascript"
        name.endsWith(".woff2") -> "font/woff2"
        name.endsWith(".woff") -> "font/woff"
        name.endsWith(".svg") -> "image/svg+xml"
        name.endsWith(".png") -> "image/png"
        name.endsWith(".json") -> "application/json"
        name.endsWith(".ico") -> "image/x-icon"
        else -> "application/octet-stream"
    }

    private fun isTextMime(mime: String): Boolean {
        return mime.startsWith("text/") ||
            mime == "application/javascript" ||
            mime == "application/json" ||
            mime == "image/svg+xml"
    }

    private fun notFoundResponse(): WebResourceResponse {
        return WebResourceResponse(
            "text/plain",
            "UTF-8",
            404,
            "Not Found",
            emptyMap(),
            ByteArrayInputStream(ByteArray(0))
        )
    }

    private fun blockedResponse(): WebResourceResponse {
        return WebResourceResponse(
            "text/plain",
            "UTF-8",
            403,
            "Blocked",
            emptyMap(),
            ByteArrayInputStream(ByteArray(0))
        )
    }

    private fun serveBundledAsset(assetPath: String): WebResourceResponse {
        return try {
            val stream = assets.open(assetPath)
            val mime = mimeForPath(assetPath)
            val encoding = if (isTextMime(mime)) "UTF-8" else null
            val headers = mapOf(
                "Cache-Control" to "public, max-age=31536000, immutable",
                "Access-Control-Allow-Origin" to VIRTUAL_ORIGIN
            )
            WebResourceResponse(mime, encoding, 200, "OK", headers, stream)
        } catch (_: Throwable) {
            notFoundResponse()
        }
    }

    private fun serveAppIconPng(pkg: String): WebResourceResponse {
        if (pkg.isEmpty()) {
            return WebResourceResponse("image/png", null, 404, "Not Found", emptyMap(), ByteArrayInputStream(ByteArray(0)))
        }
        val cached = synchronized(iconPngCache) { iconPngCache.get(pkg) }
        if (cached != null) {
            return WebResourceResponse(
                "image/png",
                null,
                200,
                "OK",
                mapOf("Cache-Control" to "public, max-age=86400"),
                ByteArrayInputStream(cached)
            )
        }
        return try {
            val drawable = packageManager.getApplicationIcon(pkg)
            val pngBytes = drawableToPng(drawable, 96)
            synchronized(iconPngCache) {
                iconPngCache.put(pkg, pngBytes)
            }
            WebResourceResponse(
                "image/png",
                null,
                200,
                "OK",
                mapOf("Cache-Control" to "public, max-age=86400"),
                ByteArrayInputStream(pngBytes)
            )
        } catch (_: Throwable) {
            WebResourceResponse("image/png", null, 404, "Not Found", emptyMap(), ByteArrayInputStream(ByteArray(0)))
        }
    }

    private fun drawableToPng(drawable: Drawable, targetPx: Int): ByteArray {
        val bmp = if (drawable is BitmapDrawable && drawable.bitmap != null) {
            Bitmap.createScaledBitmap(drawable.bitmap, targetPx, targetPx, true)
        } else {
            val b = Bitmap.createBitmap(targetPx, targetPx, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(b)
            drawable.setBounds(0, 0, canvas.width, canvas.height)
            drawable.draw(canvas)
            b
        }
        val out = ByteArrayOutputStream(2048)
        bmp.compress(Bitmap.CompressFormat.PNG, 90, out)
        return out.toByteArray()
    }
}
