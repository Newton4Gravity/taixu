package top.wkbin.taixu.core.browser

import android.content.Context
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebChromeClient
import android.webkit.ValueCallback
import android.net.Uri
import android.os.Build

/**
 * WebView configuration and management for in-app browser functionality.
 */
class BrowserManager(private val context: Context) {

    private var fileChooserCallback: ValueCallback<Array<Uri>>? = null
    private var fileChooserCallbackSingle: ValueCallback<Uri>? = null

    /**
     * Configures a WebView with secure defaults and TaiXu-specific settings.
     */
    fun configureWebView(webView: WebView, settings: BrowserSettings = BrowserSettings()): WebView {
        val webSettings = webView.settings.apply {
            // Basic settings
            javaScriptEnabled = settings.javaScriptEnabled
            domStorageEnabled = settings.domStorageEnabled
            databaseEnabled = settings.databaseEnabled
            cacheMode = if (settings.useCache) WebSettings.LOAD_DEFAULT else WebSettings.LOAD_NO_CACHE
            mixedContentMode = if (settings.allowMixedContent) WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE else WebSettings.MIXED_CONTENT_NEVER_ALLOW

            // Security settings
            allowFileAccess = false
            allowContentAccess = false
            allowFileAccessFromFileURLs = false
            allowUniversalAccessFromFileURLs = false
            safeBrowsingEnabled = true

            // UI settings
            builtInZoomControls = true
            displayZoomControls = false
            loadWithOverviewMode = true
            useWideViewPort = true
            supportZoom = true

            // Media settings
            mediaPlaybackRequiresUserGesture = true

            // User agent
            if (settings.customUserAgent != null) {
                userAgentString = settings.customUserAgent
            } else {
                userAgentString = "TaiXu/1.0 (Android; Linux; WebView)"
            }
        }

        // Set clients
        webView.webViewClient = TaiXuWebViewClient()
        webView.webChromeClient = TaiXuWebChromeClient()

        // JavaScript interface for host communication
        if (settings.enableJavaScriptInterface) {
            webView.addJavascriptInterface(TaiXuJavaScriptInterface(), "TaiXu")
        }

        return webView
    }

    /**
     * Loads a URL with optional headers.
     */
    fun loadUrl(webView: WebView, url: String, headers: Map<String, String> = emptyMap()) {
        if (headers.isEmpty()) {
            webView.loadUrl(url)
        } else {
            webView.loadUrl(url, headers)
        }
    }

    /**
     * Handles file chooser callbacks for WebView.
     */
    fun onActivityResult(requestCode: Int, resultCode: Int, data: android.content.Intent?) {
        if (requestCode == BrowserSettings.FILE_CHOOSER_REQUEST_CODE) {
            fileChooserCallback?.let { callback ->
                val uris = if (resultCode == android.app.Activity.RESULT_OK) {
                    data?.clipData?.let { clipData ->
                        (0 until clipData.itemCount).map { clipData.getItemAt(it).uri }
                    } ?: data?.data?.let { arrayOf(it) } ?: emptyArray()
                } else {
                    emptyArray()
                }
                callback.onReceiveValue(uris)
                fileChooserCallback = null
            }
            fileChooserCallbackSingle?.let { callback ->
                val uri = if (resultCode == android.app.Activity.RESULT_OK) {
                    data?.data
                } else null
                callback.onReceiveValue(uri)
                fileChooserCallbackSingle = null
            }
        }
    }

    private class TaiXuWebViewClient : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, request: android.webkit.WebResourceRequest): Boolean {
            val url = request.url.toString()
            // Handle special TaiXu URLs
            if (url.startsWith("taixu://")) {
                // Handle custom scheme
                return true
            }
            return super.shouldOverrideUrlLoading(view, request)
        }

        override fun onReceivedError(
            view: WebView,
            request: android.webkit.WebResourceRequest?,
            error: android.webkit.WebResourceError
        ) {
            super.onReceivedError(view, request, error)
            // Could notify UI of error
        }

        override fun onReceivedSslError(
            view: WebView,
            handler: android.webkit.SslErrorHandler,
            error: net.http.SslError
        ) {
            // In production, don't proceed with SSL errors
            handler.cancel()
        }
    }

    private class TaiXuWebChromeClient : WebChromeClient() {
        override fun onShowFileChooser(
            webView: WebView,
            filePathCallback: ValueCallback<Array<Uri>>,
            fileChooserParams: FileChooserParams
        ): Boolean {
            fileChooserCallback = filePathCallback
            val intent = fileChooserParams.createIntent()
            try {
                (context as? android.app.Activity)?.startActivityForResult(
                    intent,
                    BrowserSettings.FILE_CHOOSER_REQUEST_CODE
                )
            } catch (e: android.content.ActivityNotFoundException) {
                fileChooserCallback = null
                return false
            }
            return true
        }

        override fun onShowFileChooser(
            webView: WebView,
            filePathCallback: ValueCallback<Uri>,
            fileChooserParams: FileChooserParams
        ): Boolean {
            fileChooserCallbackSingle = filePathCallback
            val intent = fileChooserParams.createIntent()
            try {
                (context as? android.app.Activity)?.startActivityForResult(
                    intent,
                    BrowserSettings.FILE_CHOOSER_REQUEST_CODE
                )
            } catch (e: android.content.ActivityNotFoundException) {
                fileChooserCallbackSingle = null
                return false
            }
            return true
        }

        override fun onProgressChanged(view: WebView, newProgress: Int) {
            super.onProgressChanged(view, newProgress)
            // Could notify progress
        }
    }

    private class TaiXuJavaScriptInterface {
        @android.webkit.JavascriptInterface
        fun log(message: String) {
            android.util.Log.d("TaiXuWebView", message)
        }

        @android.webkit.JavascriptInterface
        fun toast(message: String) {
            android.widget.Toast.makeText(context, message, android.widget.Toast.LENGTH_SHORT).show()
        }

        @android.webkit.JavascriptInterface
        fun openExternalUrl(url: String) {
            val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, Uri.parse(url))
            intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        }
    }
}

/**
 * Browser configuration settings.
 */
data class BrowserSettings(
    val javaScriptEnabled: Boolean = true,
    val domStorageEnabled: Boolean = true,
    val databaseEnabled: Boolean = true,
    val useCache: Boolean = true,
    val allowMixedContent: Boolean = false,
    val customUserAgent: String? = null,
    val enableJavaScriptInterface: Boolean = true
) {
    companion object {
        const val FILE_CHOOSER_REQUEST_CODE = 0x5458 // "TX"
    }
}

/**
 * Browser session for managing navigation history and state.
 */
class BrowserSession(private val webView: WebView) {
    fun canGoBack(): Boolean = webView.canGoBack()
    fun canGoForward(): Boolean = webView.canGoForward()
    fun goBack() { if (canGoBack()) webView.goBack() }
    fun goForward() { if (canGoForward()) webView.goForward() }
    fun reload() { webView.reload() }
    fun stopLoading() { webView.stopLoading() }
    fun getUrl(): String? = webView.url
    fun getTitle(): String? = webView.title
    fun clearHistory() { webView.clearHistory() }
}