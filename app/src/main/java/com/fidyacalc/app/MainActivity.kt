package com.fidyacalc.app

import android.annotation.SuppressLint
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.WebViewAssetLoader
import java.io.ByteArrayInputStream

// ہماری HTML اس ایڈریس سے چلتی ہے (assets فولڈر کے اندر سے، انٹرنیٹ کے بغیر)
private const val START_URL = "https://appassets.androidplatform.net/assets/index.html"

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = Color.White) {
                    FidyaWebScreen(onExit = { finish() })
                }
            }
        }
    }
}

/**
 * JS سے کوٹلن کو بلانے کا پل (bridge)۔ JS میں اس کا نام `Android` ہے۔
 * ابھی صرف exitApp ہے؛ مرحلہ 2 میں share/PDF/storage یہیں آئیں گے۔
 */
class NativeBridge(private val onExit: () -> Unit) {
    @JavascriptInterface
    fun exitApp() {
        // JS بیک گراؤنڈ تھریڈ پر چلتی ہے، اس لیے مین تھریڈ پر بھیجنا ضروری ہے
        Handler(Looper.getMainLooper()).post { onExit() }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun FidyaWebScreen(onExit: () -> Unit) {
    var web by remember { mutableStateOf<WebView?>(null) }

    // سسٹم بیک: HTML کا اپنا popstate لاجک (رپورٹ/ہسٹری بند کرنا، ایگزٹ کنفرم) ویسے ہی چلے گا
    BackHandler {
        val w = web
        if (w != null && w.canGoBack()) w.goBack() else onExit()
    }

    AndroidView(
        modifier = Modifier.fillMaxSize(),
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

                addJavascriptInterface(NativeBridge(onExit), "Android")

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
                    }
                }

                loadUrl(START_URL)
            }.also { web = it }
        }
    )
}
