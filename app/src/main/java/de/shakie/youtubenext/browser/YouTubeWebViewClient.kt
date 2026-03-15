package de.shakie.youtubenext.browser

import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONObject

class YouTubeWebViewClient(
    private val onOpenExternalUrl: (Uri) -> Unit,
    private val normalizeInternalUrl: (String) -> String,
    private val onBeforeMainFrameNavigation: (String) -> Unit,
    private val onMainPageStarted: (String) -> Unit,
    private val onMainUrlUpdated: (String) -> Unit,
    private val onMainPageFinished: (String) -> Unit,
    private val onMainTitleUpdated: (String) -> Unit,
    private val onViewportDebug: (String, String) -> Unit,
    private val onLoadError: () -> Unit
) : WebViewClient() {

    override fun shouldOverrideUrlLoading(
        view: WebView?,
        request: WebResourceRequest?
    ): Boolean {
        val target = request?.url ?: return false
        if (!request.isForMainFrame) return false
        val rawUrl = target.toString()
        resolveInternalAppRedirect(rawUrl)?.let { resolvedUrl ->
            onBeforeMainFrameNavigation(resolvedUrl)
            view?.loadUrl(resolvedUrl)
            return true
        }
        val normalizedUrl = normalizeInternalUrl(rawUrl)
        onBeforeMainFrameNavigation(normalizedUrl)
        if (LinkInterceptor.isInternalFlowUri(target)) {
            if (normalizedUrl != rawUrl) {
                view?.loadUrl(normalizedUrl)
                return true
            }
            return false
        }
        onOpenExternalUrl(target)
        return true
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
        val uri = url?.let(Uri::parse) ?: return false
        val rawUrl = uri.toString()
        resolveInternalAppRedirect(rawUrl)?.let { resolvedUrl ->
            onBeforeMainFrameNavigation(resolvedUrl)
            view?.loadUrl(resolvedUrl)
            return true
        }
        val normalizedUrl = normalizeInternalUrl(rawUrl)
        onBeforeMainFrameNavigation(normalizedUrl)
        if (LinkInterceptor.isInternalFlowUri(uri)) {
            if (normalizedUrl != rawUrl) {
                view?.loadUrl(normalizedUrl)
                return true
            }
            return false
        }
        onOpenExternalUrl(uri)
        return true
    }

    override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
        super.onPageStarted(view, url, favicon)
        if (!url.isNullOrBlank()) {
            onMainPageStarted(url)
            onMainUrlUpdated(url)
        }
    }

    override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
        super.doUpdateVisitedHistory(view, url, isReload)
        if (!url.isNullOrBlank()) {
            val normalizedUrl = normalizeInternalUrl(url)
            if (normalizedUrl != url) {
                view?.post {
                    view.evaluateJavascript(
                        "window.location.replace(${JSONObject.quote(normalizedUrl)});",
                        null
                    )
                }
                return
            }
            onMainUrlUpdated(url)
            val title = view?.title.orEmpty()
            if (title.isNotBlank()) {
                onMainTitleUpdated(title)
            }
        }
    }

    override fun onPageFinished(view: WebView?, url: String?) {
        super.onPageFinished(view, url)
        val currentUrl = url ?: return
        view?.scrollTo(0, 0)
        onMainUrlUpdated(currentUrl)
        onMainPageFinished(currentUrl)
        val title = view?.title.orEmpty()
        if (title.isNotBlank()) {
            onMainTitleUpdated(title)
        }
        view?.evaluateJavascript(
            """
            (function() {
              const vv = window.visualViewport;
              const player = document.querySelector('#player');
              const moviePlayer = document.querySelector('#movie_player');
              const video = document.querySelector('video');
              const playerRect = player ? player.getBoundingClientRect() : null;
              const moviePlayerRect = moviePlayer ? moviePlayer.getBoundingClientRect() : null;
              const videoRect = video ? video.getBoundingClientRect() : null;
              return JSON.stringify({
                href: location.href,
                innerWidth: window.innerWidth,
                innerHeight: window.innerHeight,
                outerWidth: window.outerWidth,
                outerHeight: window.outerHeight,
                documentClientWidth: document.documentElement ? document.documentElement.clientWidth : null,
                devicePixelRatio: window.devicePixelRatio,
                visualViewportWidth: vv ? vv.width : null,
                visualViewportHeight: vv ? vv.height : null,
                visualViewportScale: vv ? vv.scale : null,
                documentZoom: document.documentElement ? (document.documentElement.style.zoom || null) : null,
                bodyZoom: document.body ? (document.body.style.zoom || null) : null,
                scrollX: window.scrollX,
                playerRect: playerRect ? {
                  x: playerRect.x, y: playerRect.y, width: playerRect.width, height: playerRect.height
                } : null,
                moviePlayerRect: moviePlayerRect ? {
                  x: moviePlayerRect.x, y: moviePlayerRect.y, width: moviePlayerRect.width, height: moviePlayerRect.height
                } : null,
                videoRect: videoRect ? {
                  x: videoRect.x, y: videoRect.y, width: videoRect.width, height: videoRect.height
                } : null,
                screenWidth: window.screen ? window.screen.width : null,
                screenHeight: window.screen ? window.screen.height : null
              });
            })();
            """.trimIndent()
        ) { metrics ->
            onViewportDebug(currentUrl, metrics ?: "null")
        }
    }

    override fun onReceivedError(
        view: WebView?,
        request: WebResourceRequest?,
        error: WebResourceError?
    ) {
        super.onReceivedError(view, request, error)
        if (request?.isForMainFrame == true) {
            onLoadError()
        }
    }

    private fun resolveInternalAppRedirect(rawUrl: String): String? {
        val uri = runCatching { Uri.parse(rawUrl) }.getOrNull() ?: return null
        when (uri.scheme?.lowercase()) {
            "intent" -> {
                val intent = runCatching {
                    Intent.parseUri(rawUrl, Intent.URI_INTENT_SCHEME)
                }.getOrNull() ?: return null
                val fallbackUrl = intent.getStringExtra("browser_fallback_url")
                if (!fallbackUrl.isNullOrBlank()) {
                    return normalizeInternalUrl(fallbackUrl)
                }
                val dataUrl = intent.dataString
                if (!dataUrl.isNullOrBlank()) {
                    val dataUri = Uri.parse(dataUrl)
                    if (LinkInterceptor.isInternalFlowUri(dataUri)) {
                        return normalizeInternalUrl(dataUrl)
                    }
                }
                val packageName = intent.`package`.orEmpty()
                if (packageName == "com.google.android.youtube" || packageName == "com.android.chrome") {
                    return "https://www.youtube.com/"
                }
            }

            "youtube", "vnd.youtube" -> {
                val videoId = uri.getQueryParameter("v")
                    ?: uri.lastPathSegment
                    ?: return "https://www.youtube.com/"
                return "https://www.youtube.com/watch?v=$videoId"
            }
        }
        return null
    }
}
