package io.github.daniturn3.nightjarradio

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import android.webkit.GeolocationPermissions
import android.webkit.JavascriptInterface
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

@UnstableApi
class MainActivity : Activity() {
    companion object {
        const val HOME = "https://daniturn3.github.io/nightjar-radio/"
    }

    private lateinit var web: WebView
    private var ctl: MediaController? = null
    private var ctlFuture: ListenableFuture<MediaController>? = null
    private val pending = mutableListOf<(MediaController) -> Unit>()
    private var fileCb: ValueCallback<Array<Uri>>? = null
    private var geoCb: Pair<String, GeolocationPermissions.Callback>? = null
    private var pageReady = false
    /** True only while one of our own Nightjar Radio pages is showing. Phone features answer only then. */
    @Volatile private var trusted = false
    private fun ours(url: String?) = url != null && url.startsWith(HOME)
    private var pendingAct: String? = null

    private fun handleIntent(i: Intent?) {
        val a = i?.action ?: return
        if (!a.startsWith("njr.do.")) return
        val name = a.removePrefix("njr.do.").filter { it.isLetter() }
        if (pageReady) js("window.njrAction&&window.njrAction('$name')") else pendingAct = name
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent); handleIntent(intent)
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.BLACK
        window.navigationBarColor = Color.BLACK
        web = WebView(this)
        web.setBackgroundColor(Color.parseColor("#0c0e16"))
        setContentView(web)
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            mediaPlaybackRequiresUserGesture = false
            setGeolocationEnabled(true)
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            allowFileAccess = false
            allowContentAccess = false
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)
            userAgentString = "$userAgentString NightjarRadioApp/${BuildConfig.VERSION_CODE}"
        }
        web.addJavascriptInterface(Bridge(), "NJR")
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val u = request.url
                if (u.host == "daniturn3.github.io" && (u.path ?: "").startsWith("/nightjar-radio")) return false
                openUrl(u.toString())
                return true
            }

            override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
                trusted = ours(url)
            }

            override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
                trusted = ours(url)
            }

            override fun onPageFinished(view: WebView, url: String?) {
                trusted = ours(url)
                if (url?.startsWith(HOME) == true) {
                    pageReady = true
                    pendingAct?.let { a -> view.postDelayed({ js("window.njrAction&&window.njrAction('$a')") }, 900) }
                    pendingAct = null
                }
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame) view.loadDataWithBaseURL(HOME, OFFLINE, "text/html", "utf-8", null)
            }
        }
        web.webChromeClient = object : WebChromeClient() {
            override fun onGeolocationPermissionsShowPrompt(origin: String, callback: GeolocationPermissions.Callback) {
                if (!origin.startsWith("https://daniturn3.github.io")) { callback.invoke(origin, false, false); return }
                if (checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                    callback.invoke(origin, true, false)
                } else {
                    geoCb = origin to callback
                    requestPermissions(arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION), 1)
                }
            }

            override fun onShowFileChooser(view: WebView, cb: ValueCallback<Array<Uri>>, params: FileChooserParams): Boolean {
                if (!trusted) { cb.onReceiveValue(null); return true }
                fileCb?.onReceiveValue(null)
                fileCb = cb
                return try {
                    startActivityForResult(Intent(Intent.ACTION_GET_CONTENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"), 2)
                    true
                } catch (e: Exception) {
                    fileCb = null; false
                }
            }
        }
        // Always fetch the newest page so web updates show up straight away.
        handleIntent(intent)
        web.loadUrl(HOME + "?t=" + System.currentTimeMillis())
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 3)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState); web.saveState(outState)
    }

    override fun onStart() {
        super.onStart()
        val f = MediaController.Builder(this, SessionToken(this, ComponentName(this, PlaybackService::class.java))).buildAsync()
        ctlFuture = f
        f.addListener({
            try {
                val c = f.get()
                ctl = c
                c.addListener(listener)
                pending.forEach { it(c) }
                pending.clear()
                push()
            } catch (_: Exception) {
            }
        }, ContextCompat.getMainExecutor(this))
    }

    override fun onStop() {
        ctl?.removeListener(listener)
        ctlFuture?.let { MediaController.releaseFuture(it) }
        ctl = null
        super.onStop()
    }

    override fun onDestroy() {
        web.destroy(); super.onDestroy()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (web.canGoBack()) web.goBack() else moveTaskToBack(true)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1) {
            val ok = grantResults.any { it == PackageManager.PERMISSION_GRANTED }
            geoCb?.let { (o, cb) -> cb.invoke(o, ok, false) }
            geoCb = null
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 2) {
            fileCb?.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(resultCode, data))
            fileCb = null
        }
    }

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) = push()
    }

    private fun push() {
        val c = ctl ?: return
        val s = when {
            c.isPlaying -> "playing"
            c.playWhenReady && c.playerError == null && c.mediaItemCount > 0 &&
                (c.playbackState == Player.STATE_BUFFERING || c.playbackState == Player.STATE_IDLE) -> "buffering"
            else -> "paused"
        }
        val o = JSONObject()
            .put("s", s)
            .put("u", c.currentMediaItem?.mediaId?.substringAfter('/') ?: "")
            .put("song", c.mediaMetadata.extras?.getString("song") ?: "")
            .put("err", c.playerError?.errorCodeName ?: "")
            .put("n", c.mediaItemCount)
        js("window.njrNative&&window.njrNative($o)")
    }

    private fun js(code: String) = web.post { web.evaluateJavascript(code, null) }

    private fun withCtl(f: (MediaController) -> Unit) {
        val c = ctl
        if (c != null) f(c) else pending.add(f)
    }

    private fun ui(f: () -> Unit) { if (!trusted) return; runOnUiThread { try { if (trusted) f() } catch (_: Exception) { } } }

    /** Only ever hands secure web links (https) to the browser. */
    private fun openUrl(u: String) {
        val uri = try { Uri.parse(u) } catch (e: Exception) { return }
        if (uri.scheme != "https") return
        try { startActivity(Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE)) } catch (_: Exception) { }
    }

    inner class Bridge {
        @JavascriptInterface fun version(): Int = BuildConfig.VERSION_CODE

        @JavascriptInterface fun playList(json: String, index: Int) = ui {
            if (json.length > 200_000) return@ui
            val a = JSONArray(json)
            val items = (0 until a.length()).map { Store.item(a.getJSONObject(it), "q/") }
            Store.put(this@MainActivity, "queue", json)
            withCtl { c ->
                c.setMediaItems(items, index.coerceIn(0, maxOf(0, items.size - 1)), C.TIME_UNSET)
                c.prepare(); c.play()
            }
        }

        @JavascriptInterface fun resume() = ui {
            // Live radio: reconnect so you hear what's on now, not an old buffer.
            withCtl { c -> c.stop(); c.prepare(); c.play() }
        }

        @JavascriptInterface fun pause() = ui { withCtl { it.pause() } }

        @JavascriptInterface fun setVolume(v: Float) = ui { withCtl { it.volume = v.coerceIn(0f, 1f) } }

        @JavascriptInterface fun sync(key: String, json: String) {
            if (!trusted || json.length > 500_000) return
            if (key in listOf("presets", "favs", "recent")) Store.put(this@MainActivity, key, json)
            if (key == "presets") RadioWidget.refresh(this@MainActivity)
        }

        @JavascriptInterface fun setAlarm(json: String): String {
            if (!trusted || json.length > 20_000) return "0"
            Store.put(this@MainActivity, "alarm", json)
            return Alarm.schedule(this@MainActivity).toString()
        }

        @JavascriptInterface fun getAlarm(): String = Alarm.config(this@MainActivity)?.toString() ?: ""

        @JavascriptInterface fun nextAlarm(): String = Alarm.schedule(this@MainActivity).toString()

        @JavascriptInterface fun batteryOk(): Boolean {
            val pm = getSystemService(android.os.PowerManager::class.java)
            return pm?.isIgnoringBatteryOptimizations(packageName) ?: true
        }

        @JavascriptInterface fun askBattery() = ui {
            try {
                startActivity(Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
            } catch (_: Exception) {
                startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
            }
        }

        @JavascriptInterface fun appSettings() = ui {
            startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        }

        @JavascriptInterface fun sleep(min: Int) = ui {
            withCtl { it.sendCustomCommand(SessionCommand(CMD_SLEEP, Bundle.EMPTY), Bundle().apply { putInt("min", min) }) }
        }

        @JavascriptInterface fun keepAwake(on: Boolean) = ui {
            if (on) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }

        @JavascriptInterface fun immersive(on: Boolean) = ui {
            val c = WindowCompat.getInsetsController(window, web)
            if (on) {
                c.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                c.hide(WindowInsetsCompat.Type.systemBars())
            } else c.show(WindowInsetsCompat.Type.systemBars())
        }

        @JavascriptInterface fun share(title: String, text: String) = ui {
            startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), title))
        }

        @JavascriptInterface fun saveFile(name: String, text: String) = ui {
            if (text.length > 5_000_000) return@ui
            val f = File(cacheDir, name.replace(Regex("[^A-Za-z0-9._-]"), "_"))
            f.writeText(text)
            val u = FileProvider.getUriForFile(this@MainActivity, "$packageName.files", f)
            val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_STREAM, u)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            startActivity(Intent.createChooser(send, "Save backup"))
        }

        @JavascriptInterface fun openUrl(u: String) = ui { this@MainActivity.openUrl(u) }
    }

    private val OFFLINE = """<!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1">
<style>body{margin:0;height:100vh;display:flex;flex-direction:column;align-items:center;justify-content:center;gap:14px;background:#0c0e16;color:#f3ecdf;font-family:sans-serif;text-align:center;padding:24px;box-sizing:border-box}
h1{font:italic 400 30px Georgia,serif;margin:0}p{color:#a3a7b8;margin:0}button{margin-top:10px;border:0;border-radius:999px;padding:13px 26px;font-size:16px;background:linear-gradient(135deg,#ffb347,#ff6b4a);color:#1a1210}</style></head>
<body><h1>Static on the line</h1><p>Nightjar Radio needs the internet to tune in.</p><button onclick="location.href='$HOME?t='+Date.now()">Try again</button></body></html>"""
}
