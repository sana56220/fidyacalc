package com.fidyacalc.app

import android.annotation.SuppressLint
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.webkit.WebViewAssetLoader
import java.io.ByteArrayInputStream

// ہماری HTML اس ایڈریس سے چلتی ہے (assets فولڈر کے اندر سے، انٹرنیٹ کے بغیر)
private const val START_URL = "https://appassets.androidplatform.net/assets/index.html"

class MainActivity : ComponentActivity() {

    // [نیا] ویب پیج کا بیک گراؤنڈ رنگ۔ نیچے نیویگیشن بار کے پیچھے والی پٹی اسی رنگ کی ہوگی،
    // تاکہ ڈارک تھیم میں نیچے سفید پٹی نہ بنے۔ شروع میں سفید۔
    private var pageBg by mutableStateOf(Color.White)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // [نیا] ایپ کو اسکرین کے بالکل اوپر تک پھیلنے دیں (ہر اینڈرائیڈ ورژن پر ایک جیسا رویہ)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        // [نیا] نوچ/پنچ ہول والے فونز پر بھی ایپ اوپر کے کنارے تک چلے
        if (Build.VERSION.SDK_INT >= 28) {
            val lp = window.attributes
            lp.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            window.attributes = lp
        }

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = pageBg) {
                    FidyaWebScreen(
                        onExit = { finish() },
                        // [نیا] ویب پیج سے رنگ آئے تو محفوظ کریں اور نیچے والے آئیکنز کا رنگ ٹھیک کریں
                        onPageBackground = { argb ->
                            val c = Color(argb)
                            pageBg = c
                            // روشن بیک گراؤنڈ پر گہرے آئیکنز، گہرے پر روشن آئیکنز
                            WindowCompat.getInsetsController(window, window.decorView)
                                .isAppearanceLightNavigationBars = c.luminance() > 0.5f
                        }
                    )
                }
            }
        }

        hideStatusBar()
    }

    // [نیا] جب بھی ایپ پر فوکس واپس آئے (مثلاً نوٹیفکیشن شیڈ بند ہونے کے بعد)
    // اسٹیٹس بار دوبارہ چھپا دیں، ورنہ سسٹم اسے واپس دکھا دیتا ہے
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideStatusBar()
    }

    // [نیا] صرف اوپر کی اسٹیٹس بار چھپتی ہے (وقت، تاریخ، بیٹری، سگنل وغیرہ)
    // نیچے کی نیویگیشن بار (بیک/ہوم/ریسنٹ یا سوائپ) بالکل ویسی ہی رہتی ہے اور کام کرتی ہے
    private fun hideStatusBar() {
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        // اوپر سے سوائپ کرنے پر اسٹیٹس بار عارضی طور پر نظر آتی ہے، پھر خود چھپ جاتی ہے
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.statusBars())
        // نیویگیشن بار کو ہر حال میں نظر آنے والی رکھیں
        controller.show(WindowInsetsCompat.Type.navigationBars())
    }
}

/**
 * JS سے کوٹلن کو بلانے کا پل (bridge)۔ JS میں اس کا نام `Android` ہے۔
 * ابھی exitApp اور setBackground ہیں؛ مرحلہ 2 میں share/PDF/storage یہیں آئیں گے۔
 */
class NativeBridge(
    private val onExit: () -> Unit,
    private val onPageBackground: (Int) -> Unit   // [نیا]
) {
    @JavascriptInterface
    fun exitApp() {
        // JS بیک گراؤنڈ تھریڈ پر چلتی ہے، اس لیے مین تھریڈ پر بھیجنا ضروری ہے
        Handler(Looper.getMainLooper()).post { onExit() }
    }

    // [نیا] JS صفحے کا بیک گراؤنڈ رنگ (مثلاً "rgb(244, 247, 246)") بھیجتی ہے
    @JavascriptInterface
    fun setBackground(css: String) {
        val argb = parseCssColor(css) ?: return
        Handler(Looper.getMainLooper()).post { onPageBackground(argb) }
    }
}

// [نیا] CSS کا rgb()/rgba() رنگ Android کے رنگ میں بدلتا ہے؛ شفاف رنگ نظر انداز
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

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun FidyaWebScreen(
    onExit: () -> Unit,
    onPageBackground: (Int) -> Unit = {}   // [نیا]
) {
    var web by remember { mutableStateOf<WebView?>(null) }

    // سسٹم بیک: HTML کا اپنا popstate لاجک (رپورٹ/ہسٹری بند کرنا، ایگزٹ کنفرم) ویسے ہی چلے گا
    BackHandler {
        val w = web
        if (w != null && w.canGoBack()) w.goBack() else onExit()
    }

    AndroidView(
        // [نیا] ویب ویو کو سسٹم بارز کے نیچے جانے سے روکنے کے لیے پیڈنگ:
        //  1) اوپر: صرف نوچ/کیمرہ کٹ آؤٹ کی جگہ چھوڑیں (نوچ نہ ہو تو صفر، یعنی بٹن بالکل اوپر)
        //  2) نیچے: نیویگیشن بار کی جگہ چھوڑیں تاکہ بیک/ہوم/سوائپ کام کریں اور ایپ اس کے نیچے نہ جائے
        //  3) کی بورڈ کھلے تو ویب ویو اوپر اٹھ جائے (adjustResize کا نیا طریقہ)
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.displayCutout.only(WindowInsetsSides.Top))
            .navigationBarsPadding()
            .imePadding(),
        factory = { ctx ->
            val assetLoader = WebViewAssetLoader.Builder()
                .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(ctx))
                .build()

            WebView(ctx).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true      // localStorage (ہسٹری، تھیم، زبان)
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                settings.useWideViewPort = true
                settings.textZoom = 100                // فون کے فونٹ سائز سے لے آؤٹ نہ بگڑے
                settings.setSupportZoom(false)
                settings.builtInZoomControls = false

                addJavascriptInterface(NativeBridge(onExit, onPageBackground), "Android")

                webViewClient = object : WebViewClient() {
                    override fun shouldInterceptRequest(
                        view: WebView,
                        request: WebResourceRequest
                    ): WebResourceResponse? {
                        // Service Worker کی ضرورت نہیں (APK خود آف لائن ہے، اور پرانا کیش اپڈیٹ روکتا)
                        if (request.url.path?.endsWith("/service-worker.js") == true) {
                            return WebResourceResponse(
                                "text/plain", "utf-8", 404, "Not Found",
                                emptyMap(), ByteArrayInputStream(ByteArray(0))
                            )
                        }
                        return assetLoader.shouldInterceptRequest(request.url)
                    }

                    override fun onPageFinished(view: WebView, url: String?) {
                        // HTML میں "ایگزٹ" پر window.close() بلایا جاتا ہے؛ اسے ایپ بند کرنے سے جوڑ دیا
                        view.evaluateJavascript(
                            "window.close=function(){Android.exitApp();};", null
                        )
                        // [نیا] صفحے کا بیک گراؤنڈ رنگ کوٹلن کو بھیجیں، اور تھیم بدلنے (لائٹ/ڈارک) پر دوبارہ
                        view.evaluateJavascript(
                            """
                            (function(){
                              function send(){
                                try{
                                  var c=getComputedStyle(document.body).backgroundColor;
                                  Android.setBackground(c);
                                }catch(e){}
                              }
                              send();
                              setTimeout(send,400);
                              new MutationObserver(function(){ send(); setTimeout(send,400); })
                                .observe(document.documentElement,
                                  {attributes:true,attributeFilter:['data-theme','class']});
                            })();
                            """.trimIndent(),
                            null
                        )
                    }
                }

                loadUrl(START_URL)
            }.also { web = it }
        }
    )
}
