package de.shakie.tubenext.engine.gecko

import android.app.Activity
import android.content.Context
import android.net.Uri
import android.util.Log
import android.view.ViewGroup
import de.shakie.tubenext.browser.LinkInterceptor
import de.shakie.tubenext.BuildConfig
import de.shakie.tubenext.engine.BrowserEngine
import de.shakie.tubenext.engine.EngineCallbacks
import de.shakie.tubenext.engine.EngineHomeFeedSettings
import de.shakie.tubenext.engine.EngineMediaControls
import de.shakie.tubenext.engine.EnginePlaybackState
import de.shakie.tubenext.engine.EngineTab
import de.shakie.tubenext.engine.EngineType
import org.mozilla.geckoview.AllowOrDeny
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSessionSettings
import org.mozilla.geckoview.GeckoView
import org.mozilla.geckoview.MediaSession
import org.mozilla.geckoview.WebExtension
import org.json.JSONObject
import java.util.WeakHashMap

class GeckoBrowserEngine(
    private val activity: Activity,
    private val shouldUseDesktopMode: (String) -> Boolean
) : BrowserEngine {

    override val type: EngineType = EngineType.GECKO
    private val runtime: GeckoRuntime = runtimeFor(activity.applicationContext)
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
        loadInitialUrl: Boolean,
        callbacks: EngineCallbacks
    ): EngineTab {
        var createdSession = false
        val retainedTab = retainedTabs[tabId] ?: run {
            val desktopMode = shouldUseDesktopMode(initialUrl)
            val settings = GeckoSessionSettings.Builder()
                .usePrivateMode(false)
                .userAgentMode(
                    if (desktopMode) {
                        GeckoSessionSettings.USER_AGENT_MODE_DESKTOP
                    } else {
                        GeckoSessionSettings.USER_AGENT_MODE_MOBILE
                    }
                )
                .suspendMediaWhenInactive(false)
                .build()
            RetainedGeckoTab(
                session = GeckoSession(settings),
                currentUrl = initialUrl,
                title = title,
                desktopMode = desktopMode
            ).also { retained ->
                createdSession = true
                retained.session.open(runtime)
                retainedTabs[tabId] = retained
            }
        }
        val shouldLoadInitialUrl = createdSession && loadInitialUrl && initialUrl.isNotBlank()
        val session = retainedTab.session
        val geckoView = GeckoView(activity).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setSession(session)
        }

        var activeMediaSession: MediaSession? = null
        var mediaTitle = retainedTab.title.ifBlank { title }
        var mediaArtist = ""
        var mediaPlaying = false
        var mediaPositionMs = 0L
        var mediaDurationMs: Long? = null
        session.settings.userAgentMode = if (retainedTab.desktopMode) {
            GeckoSessionSettings.USER_AGENT_MODE_DESKTOP
        } else {
            GeckoSessionSettings.USER_AGENT_MODE_MOBILE
        }
        val mediaControls = object : EngineMediaControls {
            override fun play() {
                activeMediaSession?.play()
            }

            override fun pause() {
                activeMediaSession?.pause()
            }

            override fun stop() {
                activeMediaSession?.stop()
            }

            override fun seekForward() {
                activeMediaSession?.seekForward()
            }

            override fun seekBackward() {
                activeMediaSession?.seekBackward()
            }
        }
        fun emitPlaybackState() {
            callbacks.onPlaybackStateChanged(
                tabId,
                EnginePlaybackState(
                    url = retainedTab.currentUrl,
                    title = mediaTitle.ifBlank { title.ifBlank { retainedTab.currentUrl } },
                    isPlaying = mediaPlaying,
                    positionMs = mediaPositionMs,
                    durationMs = mediaDurationMs,
                    isLive = mediaDurationMs == null
                )
            )
        }
        fun applyUserAgentForUrl(url: String, source: String): Boolean {
            if (!shouldApplyUserAgentForUrl(url)) return false
            val targetDesktopMode = shouldUseDesktopMode(url)
            if (retainedTab.desktopMode == targetDesktopMode) return false
            retainedTab.desktopMode = targetDesktopMode
            session.settings.userAgentMode = if (retainedTab.desktopMode) {
                GeckoSessionSettings.USER_AGENT_MODE_DESKTOP
            } else {
                GeckoSessionSettings.USER_AGENT_MODE_MOBILE
            }
            debugLog(
                "TUBENEXT_ENGINE",
                "tab=$tabId source=$source uaMode=${if (retainedTab.desktopMode) "DESKTOP" else "MOBILE"} url=$url"
            )
            return true
        }

        session.navigationDelegate = object : GeckoSession.NavigationDelegate {
            override fun onCanGoBack(session: GeckoSession, value: Boolean) {
                retainedTab.canGoBack = value
                callbacks.onHistoryAvailabilityChanged(
                    tabId,
                    retainedTab.canGoBack,
                    retainedTab.canGoForward
                )
            }

            override fun onCanGoForward(session: GeckoSession, value: Boolean) {
                retainedTab.canGoForward = value
                callbacks.onHistoryAvailabilityChanged(
                    tabId,
                    retainedTab.canGoBack,
                    retainedTab.canGoForward
                )
            }

            override fun onLocationChange(
                session: GeckoSession,
                url: String?,
                perms: MutableList<GeckoSession.PermissionDelegate.ContentPermission>,
                hasUserGesture: Boolean
            ) {
                val location = url.orEmpty()
                if (location.isBlank()) return
                retainedTab.currentUrl = location
                callbacks.onMainUrlUpdated(tabId, location)
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
                debugLog(
                    "TUBENEXT_NAV",
                    "tab=$tabId rejected-top-level-host scheme=$scheme " +
                        "host=${targetUri.host?.lowercase().orEmpty()}"
                )
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
                retainedTab.currentUrl = url
                callbacks.onMainNavigationStarted(tabId, url)
            }

            override fun onPageStop(session: GeckoSession, success: Boolean) {
                callbacks.onMainPageFinished(tabId, retainedTab.currentUrl)
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
                retainedTab.currentUrl = url
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
                    retainedTab.currentUrl = uri
                }
                val title = currentItem.title.orEmpty()
                if (title.isNotBlank()) {
                    retainedTab.title = title
                    callbacks.onMainTitleUpdated(tabId, title)
                }
            }
        }

        session.contentDelegate = object : GeckoSession.ContentDelegate {
            override fun onFullScreen(session: GeckoSession, fullScreen: Boolean) {
                callbacks.onFullscreenChanged(tabId, fullScreen)
            }

            override fun onCrash(session: GeckoSession) {
                Log.e(
                    "TUBENEXT_RENDER",
                    "Gecko content process crashed tab=$tabId url=${retainedTab.currentUrl}"
                )
                callbacks.onEngineProcessGone(tabId, "crash")
            }

            override fun onKill(session: GeckoSession) {
                Log.w(
                    "TUBENEXT_RENDER",
                    "Gecko content process killed tab=$tabId url=${retainedTab.currentUrl}"
                )
                callbacks.onEngineProcessGone(tabId, "kill")
            }

            override fun onPaintStatusReset(session: GeckoSession) {
                Log.i("TUBENEXT_RENDER", "Gecko paint status reset tab=$tabId url=${retainedTab.currentUrl}")
            }

            override fun onFirstComposite(session: GeckoSession) {
                Log.i("TUBENEXT_RENDER", "Gecko first composite tab=$tabId url=${retainedTab.currentUrl}")
            }

            override fun onFirstContentfulPaint(session: GeckoSession) {
                Log.i("TUBENEXT_RENDER", "Gecko first contentful paint tab=$tabId url=${retainedTab.currentUrl}")
            }
        }

        session.mediaSessionDelegate = object : MediaSession.Delegate {
            override fun onActivated(session: GeckoSession, mediaSession: MediaSession) {
                debugLog("TUBENEXT_AUDIO", "tab=$tabId media activated")
                activeMediaSession = mediaSession
                callbacks.onMediaControlsChanged(tabId, mediaControls)
                emitPlaybackState()
            }

            override fun onDeactivated(session: GeckoSession, mediaSession: MediaSession) {
                if (activeMediaSession == mediaSession) {
                    debugLog("TUBENEXT_AUDIO", "tab=$tabId media deactivated")
                    activeMediaSession = null
                    mediaPlaying = false
                    callbacks.onMediaControlsChanged(tabId, null)
                    emitPlaybackState()
                }
            }

            override fun onMetadata(
                session: GeckoSession,
                mediaSession: MediaSession,
                metadata: MediaSession.Metadata
            ) {
                mediaTitle = metadata.title.orEmpty()
                mediaArtist = metadata.artist.orEmpty()
                debugLog("TUBENEXT_AUDIO", "tab=$tabId metadata title=$mediaTitle artist=$mediaArtist")
                emitPlaybackState()
            }

            override fun onPlay(session: GeckoSession, mediaSession: MediaSession) {
                debugLog("TUBENEXT_AUDIO", "tab=$tabId media play")
                mediaPlaying = true
                emitPlaybackState()
            }

            override fun onPause(session: GeckoSession, mediaSession: MediaSession) {
                debugLog("TUBENEXT_AUDIO", "tab=$tabId media pause")
                mediaPlaying = false
                emitPlaybackState()
            }

            override fun onStop(session: GeckoSession, mediaSession: MediaSession) {
                debugLog("TUBENEXT_AUDIO", "tab=$tabId media stop")
                mediaPlaying = false
                emitPlaybackState()
            }

            override fun onPositionState(
                session: GeckoSession,
                mediaSession: MediaSession,
                positionState: MediaSession.PositionState
            ) {
                mediaPositionMs = (positionState.position * 1000).toLong().coerceAtLeast(0)
                mediaDurationMs = positionState.duration
                    .takeIf { !it.isNaN() && it > 0.0 }
                    ?.let { (it * 1000).toLong() }
                emitPlaybackState()
            }
        }

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
            url = retainedTab.currentUrl.ifBlank { initialUrl },
            canGoBackProvider = { retainedTab.canGoBack },
            canGoForwardProvider = { retainedTab.canGoForward },
            shouldUseDesktopMode = shouldUseDesktopMode,
            onDetach = {
                removeNavigationBridge(session)
                geckoView.releaseSession()
            },
            onDestroy = {
                removeNavigationBridge(session)
                retainedTabs.remove(tabId)
            },
            onHomeFeedSettingsChanged = { settings ->
                updateHomeFeedSettings(session, settings)
            }
        )
        if (shouldLoadInitialUrl) {
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
            .ensureBuiltIn(NAV_EXTENSION_LOCATION, NAV_EXTENSION_ID)
            .accept(
                { extension ->
                    if (extension == null) {
                        Log.w("TUBENEXT_NAV", "Navigation extension install returned null")
                        return@accept
                    }
                    navExtension = extension
                    debugLog("TUBENEXT_NAV", "Navigation extension ready: ${extension.id}")
                    bindAllNavigationBridges()
                },
                { throwable ->
                    navExtensionInstallRequested = false
                    Log.w(
                        "TUBENEXT_NAV",
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
        navBridgeBySession.remove(session)?.homeFeedPort?.disconnect()
    }

    private fun bindAllNavigationBridges() {
        navBridgeBySession.keys.toList().forEach(::bindNavigationBridge)
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
                    if (sender.session != session || !sender.isTopLevel()) {
                        return GeckoResult.fromValue(null)
                    }
                    val intent = parseNavigationIntent(message)
                    if (intent == null || intent.type != MODE_NAV_TYPE) {
                        if (intent?.type == OPEN_NEW_TAB_TYPE) {
                            val targetUri = runCatching { Uri.parse(intent.url) }.getOrNull()
                            if (targetUri == null || !LinkInterceptor.isYouTubeUri(targetUri)) {
                                Log.w("TUBENEXT_NAV", "Ignored invalid OPEN_NEW_TAB url=${intent.url}")
                                return GeckoResult.fromValue(null)
                            }
                            debugLog("TUBENEXT_NAV", "tab=${bridge.tabId} open-new-tab url=${intent.url}")
                            bridge.callbacks.onNewTabRequest(intent.url)
                        }
                        return GeckoResult.fromValue(null)
                    }
                    val targetUri = runCatching { Uri.parse(intent.url) }.getOrNull()
                    if (targetUri == null || !LinkInterceptor.isInternalFlowUri(targetUri)) {
                        Log.w("TUBENEXT_UA", "Ignored invalid MODE_NAV url=${intent.url}")
                        return GeckoResult.fromValue(null)
                    }
                    bridge.applyUserAgentForUrl(intent.url, "extension")
                    debugLog("TUBENEXT_NAV", "tab=${bridge.tabId} mode-nav url=${intent.url}")
                    bridge.callbacks.onMainNavigationStarted(bridge.tabId, intent.url)
                    session.loadUri(intent.url)
                    return GeckoResult.fromValue(null)
                }

                override fun onConnect(port: WebExtension.Port) {
                    if (port.sender.session != session || !port.sender.isTopLevel()) {
                        port.disconnect()
                        return
                    }
                    bridge.homeFeedPort?.disconnect()
                    bridge.homeFeedPort = port
                    port.setDelegate(object : WebExtension.PortDelegate {
                        override fun onPortMessage(message: Any, port: WebExtension.Port) {
                            when (parseMessageType(message)) {
                                HOME_FEED_READY_TYPE -> postHomeFeedSettings(bridge)
                            }
                        }

                        override fun onDisconnect(port: WebExtension.Port) {
                            if (bridge.homeFeedPort == port) {
                                bridge.homeFeedPort = null
                            }
                        }
                    })
                    postHomeFeedSettings(bridge)
                }
            },
            NAV_NATIVE_APP_ID
        )
    }

    private fun updateHomeFeedSettings(session: GeckoSession, settings: EngineHomeFeedSettings) {
        val bridge = navBridgeBySession[session] ?: return
        bridge.homeFeedSettings = settings
        postHomeFeedSettings(bridge)
    }

    private fun postHomeFeedSettings(bridge: NavBridge) {
        val port = bridge.homeFeedPort ?: return
        val settings = bridge.homeFeedSettings
        port.postMessage(
            JSONObject()
                .put("type", HOME_FEED_SETTINGS_TYPE)
                .put("showShorts", settings.showShorts)
                .put("showCommunityPosts", settings.showCommunityPosts)
                .put("showWatchHistory", settings.showWatchHistory)
                .put("hideWatchBranding", settings.hideWatchBranding)
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

    private fun parseMessageType(message: Any?): String? {
        return when (message) {
            is JSONObject -> message.optString("type").takeIf { it.isNotBlank() }
            is Map<*, *> -> message["type"] as? String
            else -> null
        }
    }

    private data class NavBridge(
        val tabId: String,
        val callbacks: EngineCallbacks,
        val applyUserAgentForUrl: (String, String) -> Boolean,
        var homeFeedSettings: EngineHomeFeedSettings = EngineHomeFeedSettings(),
        var homeFeedPort: WebExtension.Port? = null
    )

    private data class RetainedGeckoTab(
        val session: GeckoSession,
        var currentUrl: String,
        var title: String,
        var desktopMode: Boolean,
        var canGoBack: Boolean = false,
        var canGoForward: Boolean = false
    )

    private data class NavigationIntent(
        val type: String,
        val url: String
    )

    private companion object {
        @Volatile
        private var sharedRuntime: GeckoRuntime? = null
        private val retainedTabs = mutableMapOf<String, RetainedGeckoTab>()

        private const val NAV_EXTENSION_LOCATION =
            "resource://android/assets/web_extensions/tubenext_nav_switch/"
        private const val NAV_EXTENSION_ID = "tubenext-nav-switch@shakie.de"
        private const val NAV_NATIVE_APP_ID = "tubenext_nav_switch"
        private const val MODE_NAV_TYPE = "MODE_NAV"
        private const val OPEN_NEW_TAB_TYPE = "OPEN_NEW_TAB"
        private const val HOME_FEED_READY_TYPE = "HOME_FEED_READY"
        private const val HOME_FEED_SETTINGS_TYPE = "HOME_FEED_SETTINGS"
        fun debugLog(tag: String, message: String) {
            if (BuildConfig.DEBUG) {
                Log.i(tag, message)
            }
        }

        fun runtimeFor(context: Context): GeckoRuntime {
            sharedRuntime?.let { return it }
            return synchronized(this) {
                sharedRuntime ?: GeckoRuntime.create(context).also { sharedRuntime = it }
            }
        }
    }
}

private data class GeckoEngineTab(
    override val id: String,
    val session: GeckoSession,
    val geckoView: GeckoView,
    override var title: String,
    override var url: String,
    val canGoBackProvider: () -> Boolean,
    val canGoForwardProvider: () -> Boolean,
    val shouldUseDesktopMode: (String) -> Boolean,
    val onDetach: () -> Unit,
    val onDestroy: () -> Unit,
    val onHomeFeedSettingsChanged: (EngineHomeFeedSettings) -> Unit
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

    override fun canGoForward(): Boolean = canGoForwardProvider()

    override fun goForward() {
        session.goForward()
    }

    override fun stopLoading() {
        session.stop()
    }

    override fun detach() {
        onDetach()
    }

    override fun destroy() {
        onDestroy()
        geckoView.releaseSession()
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

    override fun setHomeFeedSettings(settings: EngineHomeFeedSettings) {
        onHomeFeedSettingsChanged(settings)
    }

    override fun onPause() {
        session.setFocused(false)
        session.setActive(false)
    }

    override fun onResume() {
        session.setActive(true)
        session.setFocused(true)
    }

    override fun recoverFromAudioRouteChange() {
        if (BuildConfig.DEBUG) {
            Log.i("TUBENEXT_AUDIO", "tab=$id recover from audio route change")
        }
        session.setFocused(false)
        session.setActive(false)
        geckoView.postDelayed({
            session.setActive(true)
            session.setFocused(true)
        }, 250L)
    }
}
