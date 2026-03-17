package de.shakie.youtubenext.engine.webview

import android.app.Activity
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.FrameLayout
import de.shakie.youtubenext.browser.WebViewFactory
import de.shakie.youtubenext.browser.YouTubeWebChromeClient
import de.shakie.youtubenext.browser.YouTubeWebViewClient
import de.shakie.youtubenext.engine.BrowserEngine
import de.shakie.youtubenext.engine.EngineCallbacks
import de.shakie.youtubenext.engine.EngineTab
import de.shakie.youtubenext.engine.EngineType

class WebViewBrowserEngine(
    private val activity: Activity,
    private val fullscreenContainer: FrameLayout,
    private val normalizeInternalUrl: (String) -> String
) : BrowserEngine {

    override val type: EngineType = EngineType.WEBVIEW

    override fun createTab(
        tabId: String,
        initialUrl: String,
        title: String,
        callbacks: EngineCallbacks
    ): EngineTab {
        val webView = WebViewFactory.create(activity).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        val webViewClient = YouTubeWebViewClient(
            onOpenExternalUrl = callbacks.onOpenExternalUrl,
            normalizeInternalUrl = normalizeInternalUrl,
            onBeforeMainFrameNavigation = { nextUrl ->
                callbacks.onMainUrlUpdated(tabId, nextUrl)
            },
            onMainUrlUpdated = { nextUrl ->
                callbacks.onMainUrlUpdated(tabId, nextUrl)
            },
            onMainPageFinished = { pageUrl ->
                callbacks.onMainPageFinished(tabId, pageUrl)
            },
            onMainTitleUpdated = { nextTitle ->
                callbacks.onMainTitleUpdated(tabId, nextTitle)
            },
            onViewportDebug = { _, _ ->
                // No-op on engine level. MainActivity can still attach focused debug hooks later.
            },
            onLoadError = {
                callbacks.onLoadError(tabId)
            }
        )

        val chromeClient = YouTubeWebChromeClient(
            activity = activity,
            container = fullscreenContainer,
            onTitleChanged = { nextTitle ->
                callbacks.onMainTitleUpdated(tabId, nextTitle)
            },
            onProgressChanged = { progress ->
                callbacks.onProgressChanged(tabId, progress)
            },
            onNewTabRequest = { targetUrl ->
                callbacks.onMainUrlUpdated(tabId, normalizeInternalUrl(targetUrl))
            },
            onPopupUrlRequest = { targetUrl ->
                webView.loadUrl(normalizeInternalUrl(targetUrl))
            },
            onFullscreenChanged = {
                // Fullscreen visibility is handled by existing chrome client behavior.
            }
        )

        webView.webViewClient = webViewClient
        webView.webChromeClient = chromeClient

        val tab = WebViewEngineTab(
            id = tabId,
            webView = webView,
            title = title,
            url = initialUrl
        )
        if (initialUrl.isNotBlank()) {
            tab.loadUrl(initialUrl)
        }
        return tab
    }

    override fun shutdown() = Unit
}

private data class WebViewEngineTab(
    override val id: String,
    val webView: WebView,
    override var title: String,
    override var url: String
) : EngineTab {
    override val view: WebView = webView

    override fun loadUrl(url: String) {
        this.url = url
        webView.loadUrl(url)
    }

    override fun reload() {
        webView.reload()
    }

    override fun canGoBack(): Boolean = webView.canGoBack()

    override fun goBack() {
        webView.goBack()
    }

    override fun stopLoading() {
        webView.stopLoading()
    }

    override fun destroy() {
        webView.destroy()
    }

    override fun evaluateJavascript(script: String, callback: ((String?) -> Unit)?) {
        webView.evaluateJavascript(script) { result ->
            callback?.invoke(result)
        }
    }

    override fun onPause() {
        webView.onPause()
    }

    override fun onResume() {
        webView.onResume()
    }
}
