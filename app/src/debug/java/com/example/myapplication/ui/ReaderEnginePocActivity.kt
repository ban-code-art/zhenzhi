package com.example.myapplication.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.os.Bundle
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.core.net.toUri
import java.io.ByteArrayInputStream
import java.util.concurrent.CopyOnWriteArrayList

/** Debug-only real engine experiment. No application navigation, Room or reader state is used. */
class ReaderEnginePocActivity : Activity() {
    lateinit var webView: WebView
        private set
    val console = CopyOnWriteArrayList<String>()
    val deniedRequests = CopyOnWriteArrayList<String>()

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        check(android.os.Process.myUid() / 100000 == 10) { "PoC requires isolated user 10" }
        webView = WebView(this).apply {
            tag = "M03Web"
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = false
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.blockNetworkLoads = true
            settings.textZoom = 100
            addJavascriptInterface(object {
                @JavascriptInterface fun copyText(text: String) {
                    if (text.length > 8192) return
                    runOnUiThread {
                        (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager)
                            .setPrimaryClip(ClipData.newPlainText("M03 PoC", text))
                    }
                }
            }, "PocClipboard")
            webChromeClient = object : WebChromeClient() {
                override fun onConsoleMessage(message: ConsoleMessage): Boolean {
                    console += "${message.messageLevel()}:${message.message()} @${message.sourceId()}:${message.lineNumber()}"
                    return true
                }
            }
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                    request.url.host != "m03.local"

                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse {
                    val path = request.url.path.orEmpty()
                    if (request.url.scheme != "https" || request.url.host != "m03.local" ||
                        !path.startsWith("/m03/") || path.split('/').any { it == ".." } || '\\' in path) {
                        deniedRequests += request.url.toString()
                        return response("text/plain", "External network blocked".byteInputStream(), 403)
                    }
                    val mime = when (path.substringAfterLast('.')) {
                        "html", "xhtml" -> "text/html"
                        "js", "mjs" -> "text/javascript"
                        "css" -> "text/css"
                        "pdf" -> "application/pdf"
                        "epub" -> "application/epub+zip"
                        "wasm" -> "application/wasm"
                        else -> "application/octet-stream"
                    }
                    return try {
                        response(mime, assets.open(path.removePrefix("/")))
                    } catch (_: java.io.IOException) {
                        response("text/plain", ByteArrayInputStream("Missing local resource: $path".toByteArray()), 404)
                    }
                }
            }
        }
        setContentView(webView)
        val engine = intent.getStringExtra("engine") ?: "epub"
        val sample = intent.getStringExtra("sample") ?: if (engine == "pdf") "controlled.pdf" else "controlled.epub"
        val url = "https://m03.local/m03/index.html".toUri().buildUpon()
            .appendQueryParameter("engine", engine).appendQueryParameter("sample", sample).build()
        webView.loadUrl(url.toString())
    }

    private fun response(mime: String, data: java.io.InputStream, status: Int = 200) =
        WebResourceResponse(mime, "UTF-8", status, if (status == 200) "OK" else "Blocked",
            mapOf("Cache-Control" to "no-store", "Access-Control-Allow-Origin" to "https://m03.local"), data)

    override fun onDestroy() {
        webView.stopLoading()
        webView.removeJavascriptInterface("PocClipboard")
        webView.loadUrl("about:blank")
        webView.destroy()
        super.onDestroy()
    }
}
