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
import org.mozilla.geckoview.WebExtension
import java.util.WeakHashMap
import org.json.JSONObject

class GeckoBrowserEngine(
    private val activity: Activity,
    private val shouldUseDesktopMode: (String) -> Boolean
) : BrowserEngine {

    override val type: EngineType = EngineType.GECKO
    private val runtime: GeckoRuntime = GeckoRuntime.create(activity)
    private var navExtensionInstallRequested = false
    private var navExtension: WebExtension? = null
    private val navBridgeBySession = WeakHashMap<GeckoSession, NavBridge>()

    init {
        ensureNavigationExtensionInstalled()
    }

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
        session.settings.userAgentMode = if (desktopMode) {
            GeckoSessionSettings.USER_AGENT_MODE_DESKTOP
        } else {
            GeckoSessionSettings.USER_AGENT_MODE_MOBILE
        }
        fun applyUserAgentForUrl(url: String, source: String): Boolean {
            if (!shouldApplyUserAgentForUrl(url)) return false
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
                applyUserAgentForUrl(request.uri, "onLoadRequest")
                if (LinkInterceptor.isInternalFlowUri(targetUri)) {
                    callbacks.onMainNavigationStarted(tabId, request.uri)
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
                currentUrl = url
                callbacks.onMainNavigationStarted(tabId, url)
            }

            override fun onPageStop(session: GeckoSession, success: Boolean) {
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
                currentUrl = url
                callbacks.onMainUrlUpdated(tabId, url)
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
                    currentUrl = uri
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
        registerNavigationBridge(
            session = session,
            tabId = tabId,
            callbacks = callbacks,
            applyUserAgentForUrl = { url, source ->
                applyUserAgentForUrl(url, source)
            }
        )
        val tab = GeckoEngineTab(
            id = tabId,
            session = session,
            geckoView = geckoView,
            title = title,
            url = initialUrl,
            canGoBackProvider = { canGoBack },
            shouldUseDesktopMode = shouldUseDesktopMode,
            onDestroy = { removeNavigationBridge(session) }
        )
        if (initialUrl.isNotBlank()) {
            tab.loadUrl(initialUrl)
        }
        return tab
    }

    override fun shutdown() {
        navBridgeBySession.clear()
    }

    private fun shouldApplyUserAgentForUrl(url: String): Boolean {
        if (url.isBlank()) return false
        val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return false
        val scheme = uri.scheme?.lowercase().orEmpty()
        if (scheme != "http" && scheme != "https") return false
        val host = uri.host?.lowercase().orEmpty()
        return host == "youtube.com" ||
            host == "www.youtube.com" ||
            host == "m.youtube.com" ||
            host == "youtu.be"
    }

    private fun ensureNavigationExtensionInstalled() {
        if (navExtensionInstallRequested) return
        navExtensionInstallRequested = true
        runtime.webExtensionController
            .installBuiltIn(NAV_EXTENSION_LOCATION)
            .accept(
                { extension ->
                    if (extension == null) {
                        Log.w("YTNEXT_NAV", "Navigation extension install returned null")
                        return@accept
                    }
                    navExtension = extension
                    Log.i("YTNEXT_NAV", "Navigation extension installed: ${extension.id}")
                    bindAllNavigationBridges()
                },
                { throwable ->
                    navExtensionInstallRequested = false
                    Log.w(
                        "YTNEXT_NAV",
                        "Navigation extension install failed: ${throwable?.message}"
                    )
                }
            )
    }

    private fun registerNavigationBridge(
        session: GeckoSession,
        tabId: String,
        callbacks: EngineCallbacks,
        applyUserAgentForUrl: (String, String) -> Boolean
    ) {
        navBridgeBySession[session] = NavBridge(tabId, callbacks, applyUserAgentForUrl)
        ensureNavigationExtensionInstalled()
        bindNavigationBridge(session)
    }

    private fun removeNavigationBridge(session: GeckoSession) {
        navBridgeBySession.remove(session)
    }

    private fun bindAllNavigationBridges() {
        navBridgeBySession.keys.toList().forEach { session ->
            bindNavigationBridge(session)
        }
    }

    private fun bindNavigationBridge(session: GeckoSession) {
        val extension = navExtension ?: return
        val bridge = navBridgeBySession[session] ?: return
        session.webExtensionController.setMessageDelegate(
            extension,
            object : WebExtension.MessageDelegate {
                override fun onMessage(
                    nativeApp: String,
                    message: Any,
                    sender: WebExtension.MessageSender
                ): GeckoResult<Any>? {
                    if (sender.session != session || sender.isTopLevel().not()) {
                        return GeckoResult.fromValue(null)
                    }
                    val intent = parseNavigationIntent(message)
                    if (intent == null || intent.type != MODE_NAV_TYPE) {
                        return GeckoResult.fromValue(null)
                    }
                    val targetUri = runCatching { Uri.parse(intent.url) }.getOrNull()
                    if (targetUri == null || !LinkInterceptor.isInternalFlowUri(targetUri)) {
                        Log.w("YTNEXT_UA", "Ignored invalid MODE_NAV url=${intent.url}")
                        return GeckoResult.fromValue(null)
                    }
                    bridge.applyUserAgentForUrl(intent.url, "extension")
                    Log.i("YTNEXT_NAV", "tab=${bridge.tabId} mode-nav url=${intent.url}")
                    bridge.callbacks.onMainNavigationStarted(bridge.tabId, intent.url)
                    session.loadUri(intent.url)
                    return GeckoResult.fromValue(null)
                }
            },
            NAV_NATIVE_APP_ID
        )
    }

    private fun parseNavigationIntent(message: Any?): NavigationIntent? {
        return when (message) {
            is JSONObject -> {
                val type = message.optString("type")
                val url = message.optString("url")
                if (type.isBlank() || url.isBlank()) null else NavigationIntent(type, url)
            }
            is Map<*, *> -> {
                val type = message["type"] as? String
                val url = message["url"] as? String
                if (type.isNullOrBlank() || url.isNullOrBlank()) null else NavigationIntent(type, url)
            }
            else -> null
        }
    }

    private data class NavBridge(
        val tabId: String,
        val callbacks: EngineCallbacks,
        val applyUserAgentForUrl: (String, String) -> Boolean
    )

    private data class NavigationIntent(
        val type: String,
        val url: String
    )

    private companion object {
        private const val NAV_EXTENSION_LOCATION =
            "resource://android/assets/web_extensions/ytnext_nav_switch/"
        private const val NAV_NATIVE_APP_ID = "ytnext_nav_switch"
        private const val MODE_NAV_TYPE = "MODE_NAV"
    }
}

private data class GeckoEngineTab(
    override val id: String,
    val session: GeckoSession,
    val geckoView: GeckoView,
    override var title: String,
    override var url: String,
    val canGoBackProvider: () -> Boolean,
    val shouldUseDesktopMode: (String) -> Boolean,
    val onDestroy: () -> Unit
) : EngineTab {
    override val view: GeckoView = geckoView
    private var desktopMode: Boolean = true

    override fun loadUrl(url: String) {
        this.url = url
        setDesktopMode(shouldUseDesktopMode(url))
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
        onDestroy()
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
        callback?.invoke(null)
    }

    override fun onPause() = Unit

    override fun onResume() = Unit
}
