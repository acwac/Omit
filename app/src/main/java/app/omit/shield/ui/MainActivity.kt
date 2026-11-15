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
import app.omit.shield.data.VellumPrefs
import app.omit.shield.dns.BlockEngine
import app.omit.shield.service.VellumVpnService
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class MainActivity : Activity() {

    companion object {
        private const val REQ_VPN_PERMISSION = 701
        private const val REQ_POST_NOTIF = 702
        private const val VIRTUAL_ORIGIN = "https://app.vellum.local"
    }

    private var webView: WebView? = null
    private var jsBridge: VellumJsBridge? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    private var pageReady = false
    private var firstLoadHandled = false
    private var statePushScheduled = false
    private var lastPushMs = 0L

    private val iconPngCache = object : LruCache<String, ByteArray>(140) {}

    private val prefsListener: () -> Unit = {
        scheduleStatePush()
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        VellumPrefs.init(applicationContext)

        val initialDark = VellumPrefs.darkMode

        // Warm compiled 209k-rule binary table off the UI thread
        Thread({
            try {
                BlockEngine.ensureLoaded(applicationContext)
                VellumPrefs.updatePrivateDnsWarning(VellumVpnService.detectStrictPrivateDnsHost(applicationContext))
                scheduleStatePush()
            } catch (_: Throwable) {}
        }, "vellum-warm-rules").start()

        val bridge = VellumJsBridge(this)
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
            // Always apply system bar appearance AFTER setContentView so DecorView exists on Android 11-16
            applySystemBarAppearance(initialDark)
            createdWebView.loadUrl("$VIRTUAL_ORIGIN/index.html")
        } else {
            val fallbackView = TextView(this).apply {
                text = "Vellum requires Android System WebView to display its interface.\nPlease enable or update Android System WebView in Play Store."
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

    private fun handlePostUiReadyStartup() {
        if (isFinishing || isDestroyed) return
        if (VellumPrefs.consumeFirstLaunchAutoStart()) {
            mainHandler.postDelayed({
                if (!isFinishing && !isDestroyed) {
                    requestStartVpn()
                }
            }, 450L)
        } else if (VellumPrefs.desiredEnabled && VellumPrefs.shieldState == ShieldState.OFF) {
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
        VellumPrefs.updatePrivateDnsWarning(VellumVpnService.detectStrictPrivateDnsHost(applicationContext))
        VellumPrefs.addListener(prefsListener)
        applySystemBarAppearance(VellumPrefs.darkMode)
        if (pageReady) {
            pushCurrentState()
        }
    }

    override fun onPause() {
        VellumPrefs.removeListener(prefsListener)
        mainHandler.removeCallbacksAndMessages(null)
        statePushScheduled = false
        try {
            webView?.pauseTimers()
            webView?.onPause()
        } catch (_: Throwable) {}
        super.onPause()
    }

    override fun onDestroy() {
        VellumPrefs.removeListener(prefsListener)
        mainHandler.removeCallbacksAndMessages(null)
        jsBridge?.shutdown()
        try {
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
            wv.evaluateJavascript("(function(){ return !!(window.__vellumHandleBack && window.__vellumHandleBack()); })()") { result ->
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
        if (VellumPrefs.shieldState != ShieldState.OFF) {
            try {
                val stopIntent = Intent(this, VellumVpnService::class.java).apply {
                    action = VellumVpnService.ACTION_STOP
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
            VellumPrefs.setDesiredEnabled(true)
            val svc = Intent(this, VellumVpnService::class.java).apply {
                action = VellumVpnService.ACTION_START
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
                VellumPrefs.setDesiredEnabled(false)
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

    fun pushCurrentState() {
        val wv = webView ?: return
        if (!pageReady) return
        lastPushMs = System.currentTimeMillis()
        try {
            val json = VellumJsBridge.buildStateJson().toString()
            wv.evaluateJavascript("window.__vellumOnNativeState && window.__vellumOnNativeState($json);", null)
        } catch (_: Throwable) {}
    }

    fun pushAppsJson(appsJsonArray: String) {
        mainHandler.post {
            val wv = webView ?: return@post
            if (!pageReady) return@post
            try {
                wv.evaluateJavascript("window.__vellumOnNativeApps && window.__vellumOnNativeApps($appsJsonArray);", null)
            } catch (_: Throwable) {}
        }
    }

    fun pushToast(msg: String) {
        mainHandler.post {
            val wv = webView ?: return@post
            if (!pageReady) return@post
            try {
                val safeLiteral = JSONObject.quote(msg)
                wv.evaluateJavascript("window.__vellumOnToast && window.__vellumOnToast($safeLiteral);", null)
            } catch (_: Throwable) {}
        }
    }

    private fun interceptUri(uri: Uri): WebResourceResponse {
        if (uri.scheme != "https" || uri.host != "app.vellum.local") {
            return blockedResponse()
        }
        val rawPath = uri.path ?: "/"
        if (rawPath.startsWith("/app-icon/")) {
            val pkg = Uri.decode(rawPath.removePrefix("/app-icon/")).trim()
            return serveAppIconPng(pkg)
        }
        val assetRel = when {
            rawPath == "/" || rawPath == "/index.html" -> "ui/index.html"
            else -> "ui/" + rawPath.trimStart('/')
        }
        return serveBundledAsset(assetRel)
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
            val mime = when {
                assetPath.endsWith(".html") -> "text/html"
                assetPath.endsWith(".css") -> "text/css"
                assetPath.endsWith(".js") -> "application/javascript"
                assetPath.endsWith(".woff2") -> "font/woff2"
                assetPath.endsWith(".svg") -> "image/svg+xml"
                assetPath.endsWith(".png") -> "image/png"
                assetPath.endsWith(".json") -> "application/json"
                else -> "application/octet-stream"
            }
            val encoding = if (
                mime.startsWith("text/") ||
                mime == "application/javascript" ||
                mime == "application/json" ||
                mime == "image/svg+xml"
            ) {
                "UTF-8"
            } else {
                null
            }
            val headers = mapOf(
                "Cache-Control" to "public, max-age=31536000, immutable",
                "Access-Control-Allow-Origin" to VIRTUAL_ORIGIN
            )
            WebResourceResponse(mime, encoding, 200, "OK", headers, stream)
        } catch (_: Throwable) {
            WebResourceResponse(
                "text/plain",
                "UTF-8",
                404,
                "Not Found",
                emptyMap(),
                ByteArrayInputStream(ByteArray(0))
            )
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
