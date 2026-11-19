package com.fidyacalc.app

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.ContentValues
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Base64
import android.view.WindowManager
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.JsResult
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.doOnLayout
import androidx.webkit.WebViewAssetLoader
import org.json.JSONArray
import java.io.ByteArrayInputStream
import java.io.File

// HTML اس ایڈریس سے چلتی ہے (assets فولڈر کے اندر سے، انٹرنیٹ کے بغیر)
private const val ASSET_HOST = "appassets.androidplatform.net"
private const val START_URL = "https://$ASSET_HOST/assets/index.html"
private const val BRIDGE_NAME = "AndroidApp"

// true ہو تو ایپ کھلنے کے 2 سیکنڈ بعد ایک ٹوسٹ میں ویو پورٹ کے ناپ دکھتے ہیں (مسئلہ ڈھونڈنے کے لیے)
private const val DEBUG_VIEWPORT = true

// true کر دیں تو اوپر کی اسٹیٹس بار (وقت، بیٹری، سگنل) چھپ جائے گی۔ عام حالت میں false۔
private const val HIDE_STATUS_BAR = false

/**
 * یہ Activity صرف ایک "ڈبہ" ہے۔ HTML کی UI اور logic کو بالکل نہیں چھیڑا جاتا۔
 * Kotlin صرف وہ کام کرتی ہے جو WebView میں خود نہیں چلتے:
 *   1) PDF / تصویر Downloads میں محفوظ کرنا   2) شیئر شیٹ کھولنا
 *   3) alert / confirm دکھانا   4) بیک بٹن   5) اسٹیٹس بار کا رنگ صفحے کے رنگ جیسا رکھنا
 */
class MainActivity : ComponentActivity() {

    private var webView: WebView? = null

    // اوپر (اسٹیٹس بار کی جگہ) اور نیچے (نیویگیشن بار کی جگہ) کے رنگ۔ HTML سے آتے ہیں،
    // تاکہ لائٹ/ڈارک دونوں میں کوئی الگ رنگ کی پٹی نظر نہ آئے۔
    private var topColor by mutableStateOf(ComposeColor.White)
    private var bottomColor by mutableStateOf(ComposeColor.White)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // ایپ اسکرین کے پورے رقبے میں؛ سسٹم بارز کی جگہ نیچے Compose خود چھوڑتا ہے
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        window.navigationBarColor = android.graphics.Color.TRANSPARENT
        if (Build.VERSION.SDK_INT >= 29) {
            window.isStatusBarContrastEnforced = false
            window.isNavigationBarContrastEnforced = false
        }
        if (Build.VERSION.SDK_INT >= 28) {
            val lp = window.attributes
            lp.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            window.attributes = lp
        }

        val wv = createWebView()
        webView = wv
        // صفحہ تب لوڈ کریں جب WebView کا اصل سائز بن چکا ہو۔ HTML کے پاپ اپس اسکرین کی اونچائی (vh)
        // پر بنے ہیں؛ سائز صفر ہونے کی حالت میں لوڈ ہو تو وہ سکڑ کر ایک پٹی رہ جاتے ہیں۔
        if (savedInstanceState == null) {
            wv.doOnLayout { wv.loadUrl(START_URL) }
        }

        setContent {
            // سسٹم بیک: HTML کی اپنی ہسٹری (رپورٹ/ہسٹری بند کرنا، ایگزٹ) ویسے ہی چلتی ہے
            BackHandler {
                if (wv.canGoBack()) wv.goBack() else finish()
            }

            Column(modifier = Modifier.fillMaxSize().background(bottomColor)) {
                // اسٹیٹس بار / نوچ کی جگہ: صفحے کے ٹاپ بار کے رنگ جیسی، اس لیے الگ پٹی نہیں لگتی
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .windowInsetsTopHeight(WindowInsets.safeDrawing)
                        .background(topColor)
                )
                AndroidView(
                    factory = { wv },
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        // نیچے نیویگیشن بار اور کی بورڈ کی جگہ، اور اطراف کی
                        .windowInsetsPadding(
                            WindowInsets.safeDrawing.only(
                                WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom
                            )
                        )
                )
            }
        }

        applyStatusBarVisibility()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) applyStatusBarVisibility()
    }

    private fun applyStatusBarVisibility() {
        if (!HIDE_STATUS_BAR) return
        val c = WindowCompat.getInsetsController(window, window.decorView)
        c.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        c.hide(WindowInsetsCompat.Type.statusBars())
    }

    // HTML سے رنگ آئیں تو لگائیں، اور آئیکنز کا رنگ (گہرا/روشن) بھی اسی کے مطابق کریں
    private fun applyColors(top: Int, bottom: Int) {
        topColor = ComposeColor(top)
        bottomColor = ComposeColor(bottom)
        val c = WindowCompat.getInsetsController(window, window.decorView)
        c.isAppearanceLightStatusBars = topColor.luminance() > 0.5f
        c.isAppearanceLightNavigationBars = bottomColor.luminance() > 0.5f
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebView(): WebView {
        val assetLoader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()

        return WebView(this).apply {
            setBackgroundColor(android.graphics.Color.WHITE)

            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true          // localStorage (ہسٹری، تھیم، زبان)
                allowFileAccess = false
                allowContentAccess = false
                useWideViewPort = true
                setSupportZoom(false)
                builtInZoomControls = false
                displayZoomControls = false
                textZoom = 100                    // فون کے فونٹ سائز سے لے آؤٹ نہ بگڑے
                minimumFontSize = 1               // کروم کی طرح، چھوٹے فونٹ کو زبردستی بڑا نہ کرے
                minimumLogicalFontSize = 6        // کروم کا ڈیفالٹ 6 ہے (WebView کا 8 ہوتا ہے)
            }

            addJavascriptInterface(Bridge(), BRIDGE_NAME)

            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(
                    view: WebView,
                    request: WebResourceRequest
                ): WebResourceResponse? {
                    // Service Worker کی ضرورت نہیں (APK خود آف لائن ہے)
                    if (request.url.path?.endsWith("/service-worker.js") == true) {
                        return WebResourceResponse(
                            "text/plain", "utf-8", 404, "Not Found",
                            emptyMap(), ByteArrayInputStream(ByteArray(0))
                        )
                    }
                    return assetLoader.shouldInterceptRequest(request.url)
                }

                // ایپ کے باہر کا کوئی لنک ہو تو فون کے براؤزر/ایپ میں کھلے
                override fun shouldOverrideUrlLoading(
                    view: WebView,
                    request: WebResourceRequest
                ): Boolean {
                    if (request.url.host == ASSET_HOST) return false
                    try {
                        startActivity(Intent(Intent.ACTION_VIEW, request.url))
                    } catch (e: Exception) {
                    }
                    return true
                }

                override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
                    super.onPageStarted(view, url, favicon)
                    view.evaluateJavascript(BRIDGE_SHIM, null)
                }

                override fun onPageFinished(view: WebView, url: String?) {
                    super.onPageFinished(view, url)
                    view.evaluateJavascript(BRIDGE_SHIM, null)
                    view.evaluateJavascript(COLOR_SYNC, null)
                    // سائز کے بعد HTML کی اسکیل ترتیب دوبارہ چلوا دیں
                    val nudge = "window.dispatchEvent(new Event('resize'));"
                    view.postDelayed({ view.evaluateJavascript(nudge, null) }, 300)
                    view.postDelayed({ view.evaluateJavascript(nudge, null) }, 1200)
                    if (DEBUG_VIEWPORT) {
                        view.postDelayed({
                            view.evaluateJavascript(
                                "(function(){var o=document.getElementById('historyOverlay');" +
                                "return innerWidth+'x'+innerHeight+' cw='+document.documentElement.clientWidth" +
                                "+' dpr='+devicePixelRatio+' hist='+(o?o.style.height+'/'+getComputedStyle(o).height:'-')})()"
                            ) { r ->
                                Toast.makeText(this@MainActivity, "VP " + r, Toast.LENGTH_LONG).show()
                            }
                        }, 2000)
                    }
                }
            }

            webChromeClient = object : WebChromeClient() {
                override fun onJsAlert(view: WebView, url: String?, message: String?, result: JsResult): Boolean {
                    AlertDialog.Builder(this@MainActivity)
                        .setMessage(message)
                        .setCancelable(false)
                        .setPositiveButton("ٹھیک ہے") { _, _ -> result.confirm() }
                        .show()
                    return true
                }

                override fun onJsConfirm(view: WebView, url: String?, message: String?, result: JsResult): Boolean {
                    AlertDialog.Builder(this@MainActivity)
                        .setMessage(message)
                        .setCancelable(false)
                        .setPositiveButton("جی ہاں") { _, _ -> result.confirm() }
                        .setNegativeButton("نہیں") { _, _ -> result.cancel() }
                        .show()
                    return true
                }

                override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean = true
            }
        }
    }

    // ------------------------------------------------------------------
    // HTML ↔ Android پل (JS میں اس کا نام AndroidApp ہے)
    // ------------------------------------------------------------------
    inner class Bridge {

        // HTML میں "ایگزٹ" پر window.close() بلایا جاتا ہے
        @JavascriptInterface
        fun exitApp() {
            Handler(Looper.getMainLooper()).post { finish() }
        }

        @JavascriptInterface
        fun setColors(top: String, bottom: String) {
            val b = parseCssColor(bottom) ?: return
            val t = parseCssColor(top) ?: b
            Handler(Looper.getMainLooper()).post { applyColors(t, b) }
        }

        @JavascriptInterface
        fun saveFile(name: String, mime: String, base64: String) {
            val bytes = try { Base64.decode(base64, Base64.DEFAULT) } catch (e: Exception) { null }
            val uri = if (bytes != null) saveToDownloads(safeName(name), mime, bytes) else null
            runOnUiThread {
                if (uri != null) {
                    Toast.makeText(this@MainActivity, "فائل Downloads میں محفوظ ہو گئی:\n${safeName(name)}", Toast.LENGTH_LONG).show()
                } else {
                    Toast.makeText(this@MainActivity, "فائل محفوظ نہیں ہو سکی", Toast.LENGTH_LONG).show()
                }
            }
        }

        @JavascriptInterface
        fun shareData(text: String, json: String) {
            try {
                val arr = JSONArray(json)
                val uris = ArrayList<Uri>()
                var firstMime = "application/octet-stream"
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val name = safeName(o.optString("name", "file"))
                    val mime = o.optString("type").ifEmpty { "application/octet-stream" }
                    val bytes = Base64.decode(o.getString("data"), Base64.DEFAULT)
                    val uri = saveToDownloads(name, mime, bytes)
                    if (uri != null) {
                        if (uris.isEmpty()) firstMime = mime
                        uris.add(uri)
                    }
                }
                runOnUiThread {
                    if (uris.isEmpty()) {
                        Toast.makeText(this@MainActivity, "شیئر ممکن نہیں", Toast.LENGTH_LONG).show()
                    } else if (Build.VERSION.SDK_INT < 29) {
                        Toast.makeText(this@MainActivity, "فائل محفوظ ہو گئی، اب فائل مینیجر سے شیئر کریں", Toast.LENGTH_LONG).show()
                    } else {
                        val send = if (uris.size == 1) {
                            Intent(Intent.ACTION_SEND).apply {
                                type = firstMime
                                putExtra(Intent.EXTRA_STREAM, uris[0])
                            }
                        } else {
                            Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                                type = "*/*"
                                putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
                            }
                        }
                        if (text.isNotBlank()) send.putExtra(Intent.EXTRA_TEXT, text)
                        send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        startActivity(Intent.createChooser(send, "شیئر کریں"))
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this@MainActivity, "شیئر ممکن نہیں", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun safeName(name: String): String =
        name.replace(Regex("[\\\\/:*?\"<>|]"), "_").ifBlank { "file" }

    private fun saveToDownloads(name: String, mime: String, bytes: ByteArray): Uri? {
        return try {
            if (Build.VERSION.SDK_INT >= 29) {
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                    put(MediaStore.MediaColumns.MIME_TYPE, mime)
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                }
                val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: return null
                contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
                uri
            } else {
                val dir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: filesDir
                dir.mkdirs()
                val f = File(dir, name)
                f.writeBytes(bytes)
                Uri.fromFile(f)
            }
        } catch (e: Exception) {
            null
        }
    }

    override fun onPause() {
        webView?.onPause()
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        webView?.onResume()
    }

    override fun onDestroy() {
        webView?.apply {
            stopLoading()
            removeJavascriptInterface(BRIDGE_NAME)
            destroy()
        }
        webView = null
        super.onDestroy()
    }
}

// CSS کا rgb()/rgba() رنگ Android کے رنگ میں بدلتا ہے؛ شفاف رنگ نظر انداز
private fun parseCssColor(css: String): Int? {
    val m = Regex("""rgba?\(\s*(\d+)[,\s]+(\d+)[,\s]+(\d+)(?:[,\s/]+([\d.]+))?\s*\)""")
        .find(css) ?: return null
    val alpha = m.groupValues[4].toFloatOrNull() ?: 1f
    if (alpha < 0.05f) return null
    val r = m.groupValues[1].toInt().coerceIn(0, 255)
    val g = m.groupValues[2].toInt().coerceIn(0, 255)
    val b = m.groupValues[3].toInt().coerceIn(0, 255)
    return android.graphics.Color.rgb(r, g, b)
}

/**
 * صفحے کے اوپر والی بار (.top-bar) اور بیک گراؤنڈ کا رنگ کوٹلن کو بھیجتا ہے،
 * اور لائٹ/ڈارک تھیم بدلنے پر دوبارہ بھیجتا ہے۔
 */
private const val COLOR_SYNC = """
(function(){
  if (window.__colorSyncDone) return;
  window.__colorSyncDone = true;
  function bg(el){ try { return getComputedStyle(el).backgroundColor; } catch(e) { return ''; } }
  function send(){
    try {
      var tb = document.querySelector('.top-bar');
      AndroidApp.setColors(tb ? bg(tb) : '', bg(document.body));
    } catch(e) {}
  }
  send();
  setTimeout(send, 400);
  setTimeout(send, 1500);
  new MutationObserver(function(){ send(); setTimeout(send, 400); })
    .observe(document.documentElement, { attributes: true, attributeFilter: ['data-theme', 'class'] });
})();
"""

/**
 * یہ چھوٹا سا JS صرف وہ چیزیں پکڑتا ہے جو WebView میں خود نہیں چلتیں:
 *  - download والے لنک (PDF محفوظ کرنا، تصویر ڈاؤن لوڈ)
 *  - navigator.share (رپورٹ کی تصویر شیئر کرنا)
 *  - window.close (ایگزٹ)
 * HTML کا اپنا کوڈ بالکل نہیں بدلتا۔
 */
private const val BRIDGE_SHIM = """
(function(){
  if (window.__androidShimDone) return;
  window.__androidShimDone = true;

  window.close = function(){ AndroidApp.exitApp(); };

  function toB64(blob){
    return new Promise(function(res, rej){
      var r = new FileReader();
      r.onload = function(){ res(String(r.result).split(',')[1] || ''); };
      r.onerror = rej;
      r.readAsDataURL(blob);
    });
  }

  function doDownload(a){
    var href = a.href;
    var name = a.getAttribute('download') || 'file';
    if (href.indexOf('data:') === 0) {
      var i = href.indexOf(',');
      var meta = href.substring(5, i);
      var mime = meta.split(';')[0] || 'application/octet-stream';
      var body = href.substring(i + 1);
      if (meta.indexOf('base64') > -1) { AndroidApp.saveFile(name, mime, body); }
      else { AndroidApp.saveFile(name, mime, btoa(unescape(body))); }
      return;
    }
    fetch(href).then(function(r){ return r.blob(); }).then(function(b){
      return toB64(b).then(function(x){
        AndroidApp.saveFile(name, b.type || 'application/octet-stream', x);
      });
    });
  }

  var origClick = HTMLAnchorElement.prototype.click;
  HTMLAnchorElement.prototype.click = function(){
    if (this.hasAttribute('download') && this.href) { doDownload(this); return; }
    return origClick.apply(this, arguments);
  };

  var origDispatch = EventTarget.prototype.dispatchEvent;
  EventTarget.prototype.dispatchEvent = function(e){
    if (this instanceof HTMLAnchorElement && e && e.type === 'click' &&
        this.hasAttribute('download') && this.href) {
      doDownload(this);
      return true;
    }
    return origDispatch.apply(this, arguments);
  };

  try {
    Object.defineProperty(navigator, 'canShare', {
      configurable: true,
      value: function(d){ return !!(d && d.files && d.files.length); }
    });
    Object.defineProperty(navigator, 'share', {
      configurable: true,
      value: function(d){
        var fs = (d && d.files) || [];
        var text = (d && (d.text || d.title)) || '';
        return Promise.all(fs.map(function(f){
          return toB64(f).then(function(x){ return { name: f.name, type: f.type, data: x }; });
        })).then(function(arr){ AndroidApp.shareData(text, JSON.stringify(arr)); });
      }
    });
  } catch (e) {}
})();
"""
