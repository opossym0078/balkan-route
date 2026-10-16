package com.balkanroute.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.ViewGroup
import android.webkit.*
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.webkit.ServiceWorkerClientCompat
import androidx.webkit.ServiceWorkerControllerCompat
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewFeature
import java.io.ByteArrayInputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

class MainActivity : ComponentActivity() {
    private lateinit var webView: WebView
    private var sTop = 36; private var sBot = 20; private var sLeft = 0; private var sRight = 0
    private val tileDir by lazy { File(filesDir, "tiles").apply { mkdirs() } }
    private val UA = "BalkanRoute/1.6 (Android; personal trip map)"
    private val tileRe = Regex("/(\\d+)/(\\d+)/(\\d+)(@2x)?\\.png$")
    private val cors = mapOf("Access-Control-Allow-Origin" to "*", "Cache-Control" to "public, max-age=2592000")

    private fun tileResp(bytes: ByteArray) =
        WebResourceResponse("image/png", null, 200, "OK", cors, ByteArrayInputStream(bytes))

    private fun handleTile(u: Uri): WebResourceResponse? {
        val path = u.path ?: return null
        val m = tileRe.find(path) ?: return null
        val st = when {
            u.host!!.endsWith("openstreetmap.org") -> "osm"
            path.contains("dark_all") -> "dark"
            else -> "voy"
        }
        val (z, x, y) = m.destructured
        val f = File(tileDir, "$st/$z/$x/$y.png")
        if (f.exists() && f.length() > 0) return tileResp(f.readBytes())
        return try {
            val c = (URL(u.toString()).openConnection() as HttpURLConnection).apply {
                connectTimeout = 10000; readTimeout = 15000
                setRequestProperty("User-Agent", UA)
                setRequestProperty("Accept", "image/png,image/*;q=0.8")
            }
            if (c.responseCode == 200) {
                val b = c.inputStream.use { it.readBytes() }
                f.parentFile?.mkdirs(); f.writeBytes(b)
                tileResp(b)
            } else WebResourceResponse("image/png", null, 404, "NF", cors, ByteArrayInputStream(ByteArray(0)))
        } catch (_: Exception) {
            WebResourceResponse("image/png", null, 504, "Offline", cors, ByteArrayInputStream(ByteArray(0)))
        }
    }

    private fun handleReq(loader: WebViewAssetLoader, req: WebResourceRequest): WebResourceResponse? {
        val u = req.url
        val host = u.host ?: return null
        if (host == "appassets.androidplatform.net") {
            val p = u.path ?: ""
            val fixed = if (p == "/assets/" || p == "/assets")
                Uri.parse("https://appassets.androidplatform.net/assets/index.html") else u
            return loader.shouldInterceptRequest(fixed)
        }
        if (req.method == "GET" && (host.endsWith("tile.openstreetmap.org") || host.endsWith("basemaps.cartocdn.com")))
            return handleTile(u)
        return null
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT)
        )
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED)
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), 101)

        val loader = WebViewAssetLoader.Builder()
            .setDomain("appassets.androidplatform.net")
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()

        if (WebViewFeature.isFeatureSupported(WebViewFeature.SERVICE_WORKER_BASIC_USAGE)) {
            ServiceWorkerControllerCompat.getInstance().setServiceWorkerClient(object : ServiceWorkerClientCompat() {
                override fun shouldInterceptRequest(request: WebResourceRequest): WebResourceResponse? = handleReq(loader, request)
            })
        }

        webView = WebView(this).apply {
            layoutParams = ViewGroup.LayoutParams(-1, -1)
            setBackgroundColor(Color.TRANSPARENT)
            overScrollMode = WebView.OVER_SCROLL_NEVER
            isVerticalScrollBarEnabled = false
            isHorizontalScrollBarEnabled = false
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
                allowFileAccess = false
                allowContentAccess = false
                cacheMode = WebSettings.LOAD_DEFAULT
                mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                setGeolocationEnabled(true)
                setSupportZoom(false)
                builtInZoomControls = false
                displayZoomControls = false
                textZoom = 100
            }
            addJavascriptInterface(AndroidBridge(), "AndroidBridge")
            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? = handleReq(loader, request)
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    if (request.url.toString().startsWith("https://appassets.androidplatform.net/")) return false
                    try { startActivity(Intent(Intent.ACTION_VIEW, request.url)) } catch (_: Exception) {}
                    return true
                }
                override fun onPageFinished(view: WebView, url: String) { applyInsets() }
            }
            webChromeClient = object : WebChromeClient() {
                override fun onGeolocationPermissionsShowPrompt(origin: String, callback: GeolocationPermissions.Callback) {
                    callback.invoke(origin, true, true)
                }
            }
        }
        setContentView(webView)

        ViewCompat.setOnApplyWindowInsetsListener(webView) { _, wi ->
            val ins = wi.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val d = resources.displayMetrics.density
            sTop = (ins.top / d).toInt().coerceAtLeast(24)
            sBot = (ins.bottom / d).toInt().coerceAtLeast(12)
            sLeft = (ins.left / d).toInt(); sRight = (ins.right / d).toInt()
            applyInsets()
            WindowInsetsCompat.CONSUMED
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (webView.canGoBack()) webView.goBack()
                else { isEnabled = false; onBackPressedDispatcher.onBackPressed(); isEnabled = true }
            }
        })

        if (savedInstanceState == null) webView.loadUrl("https://appassets.androidplatform.net/assets/index.html")
        else webView.restoreState(savedInstanceState)
    }

    private fun applyInsets() {
        val s = "document.documentElement.style"
        webView.evaluateJavascript("$s.setProperty('--safe-top','${sTop}px');$s.setProperty('--safe-bottom','${sBot}px');$s.setProperty('--safe-left','${sLeft}px');$s.setProperty('--safe-right','${sRight}px');window.dispatchEvent(new Event('resize'));", null)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        webView.saveState(outState)
    }

    inner class AndroidBridge {
        @JavascriptInterface
        fun onThemeChanged(theme: String) {
            runOnUiThread {
                val light = theme != "dark"
                WindowCompat.getInsetsController(window, window.decorView).apply {
                    isAppearanceLightStatusBars = light
                    isAppearanceLightNavigationBars = light
                }
            }
        }
    }
}
