package de.shakie.youtubenext.engine.gecko

import android.app.Activity
import android.net.Uri
import android.util.Log
import android.view.ViewGroup
import de.shakie.youtubenext.browser.LinkInterceptor
import de.shakie.youtubenext.engine.BrowserEngine
import de.shakie.youtubenext.engine.EngineCallbacks
import de.shakie.youtubenext.engine.EngineTab
import de.shakie.youtubenext.engine.EngineType
import org.mozilla.geckoview.AllowOrDeny
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSessionSettings
import org.mozilla.geckoview.GeckoView

class GeckoBrowserEngine(
    private val activity: Activity,
    private val normalizeInternalUrl: (String) -> String,
    private val shouldUseDesktopMode: (String) -> Boolean
) : BrowserEngine {

    override val type: EngineType = EngineType.GECKO
    private val runtime: GeckoRuntime = GeckoRuntime.create(activity)

    override fun createTab(
        tabId: String,
        initialUrl: String,
        title: String,
        callbacks: EngineCallbacks
    ): EngineTab {
        val settings = GeckoSessionSettings.Builder()
            .usePrivateMode(false)
            .userAgentMode(GeckoSessionSettings.USER_AGENT_MODE_DESKTOP)
            .build()
        val session = GeckoSession(settings)
        val geckoView = GeckoView(activity).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setSession(session)
        }

        var canGoBack = false
        var currentUrl = initialUrl
        var desktopMode = shouldUseDesktopMode(initialUrl)
        var reloadingForModeSwitch = false
        session.settings.userAgentMode = if (desktopMode) {
            GeckoSessionSettings.USER_AGENT_MODE_DESKTOP
        } else {
            GeckoSessionSettings.USER_AGENT_MODE_MOBILE
        }
        fun applyUserAgentForUrl(url: String, source: String): Boolean {
            val targetDesktopMode = shouldUseDesktopMode(url)
            if (desktopMode == targetDesktopMode) return false
            desktopMode = targetDesktopMode
            session.settings.userAgentMode = if (desktopMode) {
                GeckoSessionSettings.USER_AGENT_MODE_DESKTOP
            } else {
                GeckoSessionSettings.USER_AGENT_MODE_MOBILE
            }
            Log.i(
                "YTNEXT_ENGINE",
                "tab=$tabId source=$source uaMode=${if (desktopMode) "DESKTOP" else "MOBILE"} url=$url"
            )
            return true
        }

        session.navigationDelegate = object : GeckoSession.NavigationDelegate {
            override fun onCanGoBack(session: GeckoSession, value: Boolean) {
                canGoBack = value
            }

            override fun onLoadRequest(
                session: GeckoSession,
                request: GeckoSession.NavigationDelegate.LoadRequest
            ): GeckoResult<AllowOrDeny> {
                val targetUri = Uri.parse(request.uri)
                val scheme = targetUri.scheme?.lowercase().orEmpty()
                if (scheme != "http" && scheme != "https") {
                    return GeckoResult.fromValue(AllowOrDeny.ALLOW)
                }
                val normalized = normalizeInternalUrl(request.uri)
                applyUserAgentForUrl(normalized, "onLoadRequest")
                if (LinkInterceptor.isInternalFlowUri(targetUri)) {
                    if (normalized != request.uri) {
                        callbacks.onMainNavigationStarted(tabId, normalized)
                        session.loadUri(normalized)
                        return GeckoResult.fromValue(AllowOrDeny.DENY)
                    }
                    callbacks.onMainNavigationStarted(tabId, normalized)
                    return GeckoResult.fromValue(AllowOrDeny.ALLOW)
                }
                callbacks.onOpenExternalUrl(targetUri)
                return GeckoResult.fromValue(AllowOrDeny.DENY)
            }

            override fun onLoadError(
                session: GeckoSession,
                uri: String?,
                error: org.mozilla.geckoview.WebRequestError
            ): GeckoResult<String>? {
                callbacks.onLoadError(tabId)
                return null
            }
        }

        session.progressDelegate = object : GeckoSession.ProgressDelegate {
            override fun onPageStart(session: GeckoSession, url: String) {
                val normalized = normalizeInternalUrl(url)
                currentUrl = normalized
                applyUserAgentForUrl(normalized, "onPageStart")
                callbacks.onMainNavigationStarted(tabId, normalized)
                callbacks.onMainUrlUpdated(tabId, normalized)
            }

            override fun onPageStop(session: GeckoSession, success: Boolean) {
                reloadingForModeSwitch = false
                callbacks.onMainPageFinished(tabId, currentUrl)
            }

            override fun onProgressChange(session: GeckoSession, progress: Int) {
                callbacks.onProgressChanged(tabId, progress)
            }
        }

        session.historyDelegate = object : GeckoSession.HistoryDelegate {
            override fun onVisited(
                session: GeckoSession,
                url: String,
                lastVisitedURL: String?,
                flags: Int
            ): GeckoResult<Boolean> {
                val normalized = normalizeInternalUrl(url)
                currentUrl = normalized
                applyUserAgentForUrl(normalized, "onVisited")
                callbacks.onMainUrlUpdated(tabId, normalized)
                return GeckoResult.fromValue(true)
            }

            override fun onHistoryStateChange(
                session: GeckoSession,
                history: GeckoSession.HistoryDelegate.HistoryList
            ) {
                val index = history.currentIndex
                if (index !in 0 until history.size) return
                val currentItem = history[index]
                val uri = currentItem.uri.orEmpty()
                if (uri.isNotBlank()) {
                    val normalized = normalizeInternalUrl(uri)
                    currentUrl = normalized
                    val changed = applyUserAgentForUrl(normalized, "onHistoryStateChange")
                    if (changed && !reloadingForModeSwitch) {
                        reloadingForModeSwitch = true
                        callbacks.onMainNavigationStarted(tabId, normalized)
                        session.loadUri(normalized)
                        return
                    }
                    callbacks.onMainUrlUpdated(tabId, normalized)
                }
                val title = currentItem.title.orEmpty()
                if (title.isNotBlank()) {
                    callbacks.onMainTitleUpdated(tabId, title)
                }
            }
        }

        session.contentDelegate = object : GeckoSession.ContentDelegate {
            override fun onFullScreen(session: GeckoSession, fullScreen: Boolean) {
                callbacks.onFullscreenChanged(tabId, fullScreen)
            }
        }

        session.open(runtime)
        val tab = GeckoEngineTab(
            id = tabId,
            session = session,
            geckoView = geckoView,
            title = title,
            url = initialUrl,
            canGoBackProvider = { canGoBack }
        )
        if (initialUrl.isNotBlank()) {
            tab.loadUrl(initialUrl)
        }
        return tab
    }

    override fun shutdown() = Unit
}

private data class GeckoEngineTab(
    override val id: String,
    val session: GeckoSession,
    val geckoView: GeckoView,
    override var title: String,
    override var url: String,
    val canGoBackProvider: () -> Boolean
) : EngineTab {
    override val view: GeckoView = geckoView
    private var desktopMode: Boolean = true

    override fun loadUrl(url: String) {
        this.url = url
        session.loadUri(url)
    }

    override fun reload() {
        session.reload()
    }

    override fun canGoBack(): Boolean = canGoBackProvider()

    override fun goBack() {
        session.goBack()
    }

    override fun stopLoading() {
        session.stop()
    }

    override fun destroy() {
        session.close()
    }

    override fun setDesktopMode(enabled: Boolean) {
        if (desktopMode == enabled) return
        desktopMode = enabled
        session.settings.userAgentMode = if (enabled) {
            GeckoSessionSettings.USER_AGENT_MODE_DESKTOP
        } else {
            GeckoSessionSettings.USER_AGENT_MODE_MOBILE
        }
    }

    override fun isInCustomView(): Boolean = false

    override fun exitFullscreenIfNeeded() = Unit

    override fun evaluateJavascript(script: String, callback: ((String?) -> Unit)?) {
        val uri = "javascript:(function(){${script}})();"
        session.loadUri(uri)
        callback?.invoke(null)
    }

    override fun onPause() = Unit

    override fun onResume() = Unit
}
