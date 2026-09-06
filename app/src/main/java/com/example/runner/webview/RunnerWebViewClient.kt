package com.example.runner.webview

import android.graphics.Bitmap
import android.net.Uri
import android.webkit.MimeTypeMap
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import com.example.runner.bridge.RunnerJavaScriptBridge
import java.io.File
import java.io.FileInputStream
import java.io.InputStream

class RunnerWebViewClient(
    val projectDir: File,
    private val onPageTitleChanged: (String) -> Unit = {},
    private val onPageError: (String) -> Unit = {}
) : WebViewClient() {

    companion object {
        const val LOCAL_BASE_URL = "https://runner.local/"
    }

    override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
        val uri = request?.url ?: return null
        val uriString = uri.toString()

        if (uriString.startsWith(LOCAL_BASE_URL)) {
            val relativePath = uriString.removePrefix(LOCAL_BASE_URL).substringBefore("?").substringBefore("#")
            val targetFile = if (relativePath.isEmpty() || relativePath == "index.html") {
                File(projectDir, "index.html")
            } else {
                File(projectDir, relativePath)
            }

            if (targetFile.exists() && targetFile.isFile) {
                val mimeType = getMimeType(targetFile)
                val encoding = if (mimeType.startsWith("text/") || mimeType.contains("javascript") || mimeType.contains("json")) "utf-8" else null
                val stream: InputStream = FileInputStream(targetFile)

                val responseHeaders = mutableMapOf(
                    "Access-Control-Allow-Origin" to "*",
                    "Access-Control-Allow-Methods" to "GET, POST, OPTIONS",
                    "Access-Control-Allow-Headers" to "*"
                )

                return WebResourceResponse(mimeType, encoding, 200, "OK", responseHeaders, stream)
            }
        }

        return super.shouldInterceptRequest(view, request)
    }

    override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
        super.onPageStarted(view, url, favicon)
        // Inject JS bridge helper before page scripts run
        view?.evaluateJavascript(RunnerJavaScriptBridge.INJECTED_JS_POLYFILL, null)
    }

    override fun onPageFinished(view: WebView?, url: String?) {
        super.onPageFinished(view, url)
        view?.evaluateJavascript(RunnerJavaScriptBridge.INJECTED_JS_POLYFILL, null)
        val title = view?.title
        if (!title.isNullOrEmpty()) {
            onPageTitleChanged(title)
        }
    }

    override fun onReceivedError(view: WebView?, errorCode: Int, description: String?, failingUrl: String?) {
        super.onReceivedError(view, errorCode, description, failingUrl)
        onPageError("WebView Error ($errorCode): $description for $failingUrl")
    }

    private fun getMimeType(file: File): String {
        val extension = file.extension.lowercase()
        return when (extension) {
            "html", "htm" -> "text/html"
            "css" -> "text/css"
            "js", "mjs" -> "application/javascript"
            "json" -> "application/json"
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "gif" -> "image/gif"
            "svg" -> "image/svg+xml"
            "webp" -> "image/webp"
            "ico" -> "image/x-icon"
            "woff" -> "font/woff"
            "woff2" -> "font/woff2"
            "ttf" -> "font/ttf"
            "otf" -> "font/otf"
            "mp4" -> "video/mp4"
            "mp3" -> "audio/mpeg"
            "wav" -> "audio/wav"
            "txt" -> "text/plain"
            "xml" -> "application/xml"
            else -> MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension) ?: "application/octet-stream"
        }
    }
}
