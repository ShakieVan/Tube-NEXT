package de.shakie.youtubenext

import android.app.PictureInPictureParams
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.ViewConfiguration
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.content.getSystemService
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.tabs.TabLayout
import de.shakie.youtubenext.browser.BrowserTab
import de.shakie.youtubenext.browser.LinkInterceptor
import de.shakie.youtubenext.browser.WebViewFactory
import de.shakie.youtubenext.browser.YouTubeWebChromeClient
import de.shakie.youtubenext.browser.YouTubeWebViewClient
import de.shakie.youtubenext.tabs.TabManager
import de.shakie.youtubenext.tabs.TabPersistence
import de.shakie.youtubenext.tabs.TabSession
import de.shakie.youtubenext.ui.TabOverviewAdapter
import de.shakie.youtubenext.ui.TabOverviewItem
import org.json.JSONObject

class MainActivity : AppCompatActivity() {
    private lateinit var toolbar: MaterialToolbar
    private lateinit var tabLayout: TabLayout
    private lateinit var urlTextView: TextView
    private lateinit var reloadButton: ImageButton
    private lateinit var tabSwitcherButton: FrameLayout
    private lateinit var tabCountBadge: TextView
    private lateinit var loadingOverlay: FrameLayout
    private lateinit var loadingProgress: ProgressBar
    private lateinit var loadingLabel: TextView
    private lateinit var webViewContainer: FrameLayout
    private lateinit var fullscreenContainer: FrameLayout
    private lateinit var tabManager: TabManager

    private val browserTabs = linkedMapOf<String, BrowserTab>()
    private var selectedTabId: String? = null
    private var tabSelectionUpdateInProgress = false
    private var landscapeVideoModeActive = false
    private var landscapeVideoScale = 1f
    private var landscapeVideoTranslationX = 0f
    private var landscapeVideoTranslationY = 0f

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        toolbar = findViewById(R.id.toolbar)
        tabLayout = findViewById(R.id.tabLayout)
        urlTextView = findViewById(R.id.urlText)
        reloadButton = findViewById(R.id.reloadButton)
        tabSwitcherButton = findViewById(R.id.tabSwitcherButton)
        tabCountBadge = findViewById(R.id.tabCountBadge)
        loadingOverlay = findViewById(R.id.loadingOverlay)
        loadingProgress = findViewById(R.id.loadingProgress)
        loadingLabel = findViewById(R.id.loadingLabel)
        webViewContainer = findViewById(R.id.webViewContainer)
        fullscreenContainer = findViewById(R.id.fullscreenContainer)
        tabManager = TabManager(TabPersistence(this))

        setupToolbar()
        setupTabs()
        setupBackNavigation()
        restoreOrCreateInitialTab()
        handleIncomingIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingIntent(intent)
    }

    override fun onPause() {
        super.onPause()
        tabManager.persist()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE) {
            enterLandscapeVideoModeIfNeeded()
        } else if (newConfig.orientation == Configuration.ORIENTATION_PORTRAIT) {
            currentTab()?.chromeClient?.exitFullscreenIfNeeded()
            disableLandscapeVideoMode()
        }
        updateBrowserChromeVisibility()
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !isInPictureInPictureMode) {
            val currentUrl = currentTab()?.url.orEmpty()
            if (currentUrl.contains("youtube.com/watch")) {
                enterPictureInPictureMode(PictureInPictureParams.Builder().build())
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        browserTabs.values.forEach { tab ->
            webViewContainer.removeView(tab.webView)
            tab.webView.stopLoading()
            tab.webView.destroy()
        }
        browserTabs.clear()
    }

    private fun setupToolbar() {
        toolbar.title = null
        toolbar.subtitle = null
        reloadButton.setOnClickListener {
            currentTab()?.webView?.reload()
        }
        tabSwitcherButton.setOnClickListener {
            showTabOverview()
        }
        urlTextView.setOnClickListener {
            currentTab()?.url?.takeIf { it.isNotBlank() }?.let(::copyToClipboard)
        }
        urlTextView.setOnLongClickListener {
            promptForUrlEdit()
            true
        }
    }

    private fun setupTabs() {
        tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                if (tabSelectionUpdateInProgress) return
                val tabId = tab.tag as? String ?: return
                selectTab(tabId)
            }

            override fun onTabUnselected(tab: TabLayout.Tab) = Unit

            override fun onTabReselected(tab: TabLayout.Tab) = Unit
        })
    }

    private fun setupBackNavigation() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val current = currentTab()
                if (current?.chromeClient?.isInCustomView == true) {
                    current.chromeClient.exitFullscreenIfNeeded()
                    return
                }
                if (landscapeVideoModeActive) {
                    disableLandscapeVideoMode()
                    return
                }
                if (current != null && navigateBackInTabHistory(current)) {
                    return
                }
                if (current?.webView?.canGoBack() == true) {
                    current.webView.goBack()
                } else {
                    finish()
                }
            }
        })
    }

    private fun restoreOrCreateInitialTab() {
        val restored = tabManager.restore()
        if (restored.isEmpty()) {
            createAndSelectTab(DEFAULT_URL)
            return
        }
        restored.forEach { session ->
            createBrowserTab(session)
        }
        syncTabLayout()
        val selectedId = tabManager.selectedTabId() ?: restored.first().id
        selectTab(selectedId, persistSelection = false)
    }

    private fun handleIncomingIntent(intent: Intent?) {
        val data = intent?.data ?: return
        if (intent.action != Intent.ACTION_VIEW) return
        if (!LinkInterceptor.isYouTubeUri(data)) return
        val url = data.toString()
        val current = currentTab()
        if (current == null) {
            createAndSelectTab(url)
            return
        }
        if (current.url.isBlank() || current.url == DEFAULT_URL) {
            loadInCurrentTab(url)
            return
        }

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.link_open_title)
            .setPositiveButton(R.string.link_open_current) { _, _ ->
                loadInCurrentTab(url)
            }
            .setNegativeButton(R.string.link_open_new) { _, _ ->
                createAndSelectTab(url)
            }
            .show()
    }

    private fun createAndSelectTab(url: String): BrowserTab {
        val normalizedUrl = WebViewFactory.normalizeStartUrl(url)
        val targetUrl = normalizeInternalYouTubeUrl(normalizedUrl)
        val session = tabManager.create(targetUrl, "")
        val browserTab = createBrowserTab(session)
        syncTabLayout()
        selectTab(browserTab.id, persistSelection = false)
        return browserTab
    }

    private fun createBrowserTab(session: TabSession): BrowserTab {
        val webView = WebViewFactory.create(this)
        webView.layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        webView.visibility = View.GONE

        webView.webViewClient = YouTubeWebViewClient(
            onOpenExternalUrl = ::openExternalUrl,
            normalizeInternalUrl = ::normalizeInternalYouTubeUrl,
            onBeforeMainFrameNavigation = { url ->
                applyBrowsingMode(session.id, url)
            },
            onMainPageStarted = { startedUrl ->
                val startedUri = runCatching { Uri.parse(startedUrl) }.getOrNull()
                val scheme = startedUri?.scheme?.lowercase().orEmpty()
                if (scheme == "http" || scheme == "https") {
                    onTabMainNavigationStarted(session.id)
                }
            },
            onMainUrlUpdated = { url ->
                applyBrowsingMode(session.id, url)
                updateTabState(session.id, newUrl = url)
            },
            onMainPageFinished = { url ->
                scheduleWatchViewportStabilization(session.id, url)
            },
            onMainTitleUpdated = { title ->
                updateTabState(session.id, newTitle = title)
            },
            onViewportDebug = { pageUrl, metrics ->
                Log.i("YTNEXT_VIEWPORT", "url=$pageUrl metrics=$metrics")
            },
            onLoadError = {
                completeTabLoading(session.id, browserTabs[session.id]?.pageLoadGeneration)
                Snackbar.make(webViewContainer, R.string.page_load_error, Snackbar.LENGTH_SHORT).show()
            }
        )
        val chromeClient = YouTubeWebChromeClient(
            activity = this,
            container = fullscreenContainer,
            onTitleChanged = { title ->
                updateTabState(session.id, newTitle = title)
            },
            onProgressChanged = { progress ->
                updateToolbarState()
                onTabProgress(session.id, progress)
            },
            onNewTabRequest = { targetUrl ->
                createAndSelectTab(targetUrl)
            },
            onPopupUrlRequest = { targetUrl ->
                browserTabs[session.id]?.webView?.loadUrl(normalizeInternalYouTubeUrl(targetUrl))
            },
            onFullscreenChanged = { isFullscreen ->
                if (isFullscreen) {
                    disableLandscapeVideoMode()
                    hideLoadingOverlay()
                }
                updateBrowserChromeVisibility()
            }
        )
        webView.webChromeClient = chromeClient

        val browserTab = BrowserTab(
            id = session.id,
            webView = webView,
            chromeClient = chromeClient,
            isDesktopMode = shouldUseDesktopMode(session.url),
            title = session.title,
            url = session.url
        )
        WebViewFactory.setDesktopMode(webView, browserTab.isDesktopMode)
        configureLongPressMenu(browserTab)
        browserTabs[session.id] = browserTab
        webViewContainer.addView(webView)
        if (session.url.isNotBlank()) {
            webView.loadUrl(session.url)
        }
        return browserTab
    }

    private fun updateTabState(tabId: String, newUrl: String? = null, newTitle: String? = null) {
        val tab = browserTabs[tabId] ?: return
        val updatedUrl = newUrl ?: tab.url
        val updatedTitle = newTitle ?: tab.title
        if (updatedUrl == tab.url && updatedTitle == tab.title) return

        recordTabHistory(tab, updatedUrl)
        tab.url = updatedUrl
        tab.title = updatedTitle
        tabManager.update(tabId, updatedUrl, updatedTitle)
        syncTabLayout()
        if (selectedTabId == tabId) {
            if (!updatedUrl.contains("/watch")) {
                disableLandscapeVideoMode()
            } else if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) {
                enterLandscapeVideoModeIfNeeded()
            }
            updateToolbarState()
            updateBrowserChromeVisibility()
        }
    }

    private fun selectTab(tabId: String, persistSelection: Boolean = true) {
        if (!browserTabs.containsKey(tabId)) return
        selectedTabId = tabId
        browserTabs.forEach { (id, tab) ->
            tab.webView.visibility = if (id == tabId) View.VISIBLE else View.GONE
        }
        updateToolbarState()
        if (persistSelection) {
            tabManager.select(tabId)
        }
        refreshLoadingOverlayForSelectedTab()
        if (!isCurrentTabWatchPage()) {
            disableLandscapeVideoMode()
        } else if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) {
            enterLandscapeVideoModeIfNeeded()
        }
        updateBrowserChromeVisibility()
        syncTabLayout()
    }

    private fun closeCurrentTab() {
        val tabId = selectedTabId ?: return
        closeTabById(tabId)
    }

    private fun duplicateCurrentTab() {
        val currentId = selectedTabId ?: return
        duplicateTabById(currentId)
    }

    private fun loadInCurrentTab(url: String) {
        val tab = currentTab() ?: createAndSelectTab(url)
        val normalizedUrl = WebViewFactory.normalizeStartUrl(url)
        val targetUrl = normalizeInternalYouTubeUrl(normalizedUrl)
        tab.webView.loadUrl(targetUrl)
    }

    private fun syncTabLayout() {
        val sessions = tabManager.all()
        tabCountBadge.text = sessions.size.coerceAtMost(99).toString()
        tabSelectionUpdateInProgress = true
        tabLayout.removeAllTabs()
        sessions.forEach { session ->
            val title = session.title.ifBlank {
                if (session.url.isBlank() || session.url == DEFAULT_URL) {
                    getString(R.string.default_tab_title)
                } else {
                    Uri.parse(session.url).host ?: getString(R.string.default_tab_title)
                }
            }
            tabLayout.addTab(
                tabLayout.newTab()
                    .setText(title.take(MAX_TAB_LABEL_LENGTH))
                    .setTag(session.id),
                false
            )
        }
        val activeId = selectedTabId ?: tabManager.selectedTabId()
        val index = sessions.indexOfFirst { it.id == activeId }
        if (index >= 0) {
            tabLayout.getTabAt(index)?.select()
        }
        tabSelectionUpdateInProgress = false
    }

    private fun currentTab(): BrowserTab? {
        return selectedTabId?.let { browserTabs[it] }
    }

    private fun navigateBackInTabHistory(tab: BrowserTab): Boolean {
        if (tab.historyIndex <= 0) return false
        tab.historyIndex -= 1
        tab.pendingHistoryNavigation = true
        tab.webView.loadUrl(tab.navigationHistory[tab.historyIndex])
        return true
    }

    private fun recordTabHistory(tab: BrowserTab, url: String) {
        val canonicalUrl = canonicalHistoryUrl(url)
        if (canonicalUrl.isBlank()) return

        if (tab.pendingHistoryNavigation) {
            tab.pendingHistoryNavigation = false
            if (tab.historyIndex !in tab.navigationHistory.indices) {
                tab.navigationHistory.clear()
                tab.navigationHistory.add(canonicalUrl)
                tab.historyIndex = 0
                return
            }
            tab.navigationHistory[tab.historyIndex] = canonicalUrl
            return
        }

        val currentUrl = tab.navigationHistory.getOrNull(tab.historyIndex)
        if (currentUrl == canonicalUrl) return

        if (tab.historyIndex < tab.navigationHistory.lastIndex) {
            tab.navigationHistory.subList(tab.historyIndex + 1, tab.navigationHistory.size).clear()
        }
        tab.navigationHistory.add(canonicalUrl)
        tab.historyIndex = tab.navigationHistory.lastIndex
    }

    private fun canonicalHistoryUrl(url: String): String {
        return normalizeInternalYouTubeUrl(url)
    }

    private fun updateToolbarState() {
        val current = currentTab()
        urlTextView.text = current?.url?.takeIf { it.isNotBlank() }?.let(::formatToolbarUrl).orEmpty()
    }

    private fun closeTabById(tabId: String, showMessage: Boolean = true) {
        val tab = browserTabs.remove(tabId) ?: return
        webViewContainer.removeView(tab.webView)
        tab.webView.stopLoading()
        tab.webView.destroy()

        val nextTabId = tabManager.close(tabId)
        if (browserTabs.isEmpty()) {
            createAndSelectTab(DEFAULT_URL)
            return
        }
        syncTabLayout()
        if (nextTabId != null) {
            selectTab(nextTabId, persistSelection = false)
        }
        if (showMessage) {
            Snackbar.make(webViewContainer, R.string.tab_closed_message, Snackbar.LENGTH_SHORT).show()
        }
    }

    private fun duplicateTabById(tabId: String) {
        val duplicatedSession = tabManager.duplicate(tabId) ?: return
        createBrowserTab(duplicatedSession)
        syncTabLayout()
        selectTab(duplicatedSession.id, persistSelection = false)
    }

    private fun showTabOverview() {
        val dialog = BottomSheetDialog(this)
        val content = layoutInflater.inflate(R.layout.dialog_tab_overview, null)
        val recyclerView =
            content.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.tabOverviewList)
        val title = content.findViewById<TextView>(R.id.tabOverviewTitle)
        val newTabButton = content.findViewById<ImageButton>(R.id.tabOverviewNewTab)
        val duplicateCurrentButton = content.findViewById<ImageButton>(R.id.tabOverviewDuplicateCurrent)
        val closeOthersButton = content.findViewById<ImageButton>(R.id.tabOverviewCloseOthers)
        val closeButton = content.findViewById<ImageButton>(R.id.tabOverviewClose)

        lateinit var adapter: TabOverviewAdapter
        adapter = TabOverviewAdapter(
            onTabClick = { tabId ->
                selectTab(tabId)
                refreshTabOverview(adapter, title)
            },
            onTabClose = { tabId ->
                closeTabById(tabId)
                refreshTabOverview(adapter, title)
            }
        )
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = adapter

        closeButton.setOnClickListener { dialog.dismiss() }
        newTabButton.setOnClickListener {
            createAndSelectTab(DEFAULT_URL)
            refreshTabOverview(adapter, title)
        }
        duplicateCurrentButton.setOnClickListener {
            currentTab()?.id?.let(::duplicateTabById)
            refreshTabOverview(adapter, title)
        }
        closeOthersButton.setOnClickListener {
            val keepId = selectedTabId ?: return@setOnClickListener
            tabManager.all()
                .map { it.id }
                .filter { it != keepId }
                .forEach { closeTabById(it, showMessage = false) }
            refreshTabOverview(adapter, title)
        }

        dialog.setContentView(content)
        refreshTabOverview(adapter, title)
        dialog.show()
    }

    private fun refreshTabOverview(adapter: TabOverviewAdapter, titleView: TextView) {
        val sessions = tabManager.all()
        titleView.text = getString(R.string.tab_switcher_title, sessions.size)
        val items = sessions.map { session ->
            val tab = browserTabs[session.id]
            val displayTitle = session.title.ifBlank {
                session.url.takeIf { it.isNotBlank() }?.let(::formatToolbarUrl)
                    ?: getString(R.string.default_tab_title)
            }
            TabOverviewItem(
                id = session.id,
                title = displayTitle,
                preview = tab?.let(::captureTabPreview),
                isActive = session.id == selectedTabId
            )
        }
        adapter.submitList(items)
    }

    private fun captureTabPreview(tab: BrowserTab): Bitmap? {
        val width = tab.webView.width.takeIf { it > 0 } ?: return null
        val height = tab.webView.height.takeIf { it > 0 } ?: return null
        val previewWidth = 192
        val previewHeight = 108
        val bitmap = Bitmap.createBitmap(previewWidth, previewHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val scale = previewWidth / width.toFloat()
        canvas.scale(scale, scale)
        tab.webView.draw(canvas)
        return bitmap
    }

    private fun formatToolbarUrl(url: String): String {
        val uri = Uri.parse(url)
        val host = uri.host.orEmpty().removePrefix("www.")
        val path = uri.encodedPath.orEmpty().ifBlank { "/" }
        return buildString {
            append(host)
            append(path)
            if (!uri.encodedQuery.isNullOrBlank()) {
                append("?")
                append(uri.encodedQuery)
            }
        }
    }

    private fun promptForUrlEdit() {
        val currentUrl = currentTab()?.url.orEmpty()
        val input = EditText(this).apply {
            hint = getString(R.string.url_edit_hint)
            setText(currentUrl)
            setSelection(text.length)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.url_edit_title)
            .setView(input)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.action_open) { _, _ ->
                val enteredUrl = input.text?.toString().orEmpty().trim()
                if (enteredUrl.isNotBlank()) {
                    loadInCurrentTab(enteredUrl)
                }
            }
            .show()
    }

    private fun configureLongPressMenu(tab: BrowserTab) {
        tab.webView.setOnLongClickListener {
            val result = tab.webView.hitTestResult ?: return@setOnLongClickListener false
            val target = result.extra ?: return@setOnLongClickListener false
            if (!target.startsWith("http://") && !target.startsWith("https://")) {
                return@setOnLongClickListener false
            }

            val actions = listOf(
                "Link im aktuellen Tab oeffnen" to { loadInCurrentTab(target) },
                "Link in neuem Tab oeffnen" to { createAndSelectTab(target) },
                "Im Browser oeffnen" to { openExternalUrl(Uri.parse(target)) },
                "Link teilen" to { shareLink(target) },
                "Link kopieren" to { copyToClipboard(target) }
            )
            MaterialAlertDialogBuilder(this)
                .setTitle(Uri.parse(target).host ?: target)
                .setItems(actions.map { it.first }.toTypedArray()) { _, which ->
                    actions[which].second.invoke()
                }
                .show()
            true
        }
    }

    private fun enterLandscapeVideoModeIfNeeded() {
        val tab = currentTab() ?: return
        if (!isCurrentTabWatchPage()) return
        if (tab.chromeClient.isInCustomView) return
        enableLandscapeVideoMode()
    }

    private fun enableLandscapeVideoMode() {
        val tab = currentTab() ?: return
        tab.webView.evaluateJavascript(
            """
            (function() {
              const player = document.querySelector('video');
              if (!player) return false;
              const styleId = 'yt_next_landscape_mode';
              const scriptId = 'yt_next_landscape_script';
              let style = document.getElementById(styleId);
              if (!style) {
                style = document.createElement('style');
                style.id = styleId;
                style.textContent = `
                  html, body {
                    margin: 0 !important;
                    padding: 0 !important;
                    width: 100vw !important;
                    height: 100vh !important;
                    overflow: hidden !important;
                    background: #000 !important;
                  }
                  ytd-app, ytd-page-manager, ytd-watch-flexy, #content, #primary {
                    width: 100vw !important;
                    max-width: none !important;
                  }
                  ytd-watch-flexy #below,
                  ytd-watch-flexy #secondary,
                  ytd-watch-flexy #comments,
                  ytd-watch-flexy #related,
                  ytd-watch-flexy #meta,
                  ytd-watch-flexy #chat,
                  ytd-watch-flexy #panels,
                  #masthead-container,
                  ytd-mini-guide-renderer,
                  tp-yt-app-drawer,
                  ytd-popup-container {
                    display: none !important;
                  }
                  #player-full-bleed-container,
                  #player-container-outer,
                  #player-container-inner,
                  #player {
                    position: fixed !important;
                    inset: 0 !important;
                    width: 100vw !important;
                    height: 100vh !important;
                    max-width: none !important;
                    max-height: none !important;
                    background: #000 !important;
                    z-index: 2147483646 !important;
                  }
                  .ytp-fullscreen-button {
                    opacity: 0.35 !important;
                  }
                  .ytp-settings-menu,
                  .ytp-panel-menu,
                  .ytp-popup {
                    max-height: 70vh !important;
                    overflow-y: auto !important;
                    -webkit-overflow-scrolling: touch !important;
                    touch-action: pan-y !important;
                  }
                `;
                document.documentElement.appendChild(style);
              }

              if (!document.getElementById(scriptId)) {
                const script = document.createElement('script');
                script.id = scriptId;
                script.type = 'text/javascript';
                script.text = `
                  (function() {
                    const isFullscreenControl = (target) => {
                      if (!target || !target.closest) return false;
                      if (target.closest('.ytp-fullscreen-button')) return true;
                      const button = target.closest('button');
                      if (!button) return false;
                      const candidates = [
                        button.getAttribute('aria-label') || '',
                        button.getAttribute('title') || '',
                        button.getAttribute('aria-keyshortcuts') || '',
                        button.className || ''
                      ].join(' ').toLowerCase();
                      return candidates.includes('fullscreen') ||
                        candidates.includes('full screen') ||
                        candidates.includes('vollbild') ||
                        candidates.includes('bildschirm');
                    };
                    const blockNativeFs = (event) => {
                      const target = event && event.target;
                      const isDoubleClick = event && event.type === 'dblclick';
                      const isShortcut = event && event.type === 'keydown' &&
                        ((event.key || '').toLowerCase() === 'f');
                      if (isFullscreenControl(target) || isDoubleClick || isShortcut) {
                        event.preventDefault();
                        event.stopPropagation();
                        event.stopImmediatePropagation();
                        return false;
                      }
                      return true;
                    };
                    const onFsChange = () => {
                      if (document.fullscreenElement && document.exitFullscreen) {
                        document.exitFullscreen().catch(() => {});
                      }
                    };
                    document.addEventListener('click', blockNativeFs, true);
                    document.addEventListener('dblclick', blockNativeFs, true);
                    document.addEventListener('keydown', blockNativeFs, true);
                    document.addEventListener('fullscreenchange', onFsChange, true);
                    window.__ytNextR2fHandlers = { blockNativeFs, onFsChange };
                  })();
                `;
                document.documentElement.appendChild(script);
              }
              window.scrollTo(0, 0);
              return true;
            })();
            """.trimIndent(),
            null
        )
        landscapeVideoModeActive = true
        enableSystemImmersiveMode()
        attachLandscapePinchToWebView(tab.webView)
        updateBrowserChromeVisibility()
    }

    private fun disableLandscapeVideoMode() {
        val wasActive = landscapeVideoModeActive
        val tab = currentTab()
        landscapeVideoModeActive = false
        landscapeVideoScale = 1f
        landscapeVideoTranslationX = 0f
        landscapeVideoTranslationY = 0f
        browserTabs.values.forEach { browserTab ->
            browserTab.webView.setOnTouchListener(null)
            browserTab.webView.scaleX = 1f
            browserTab.webView.scaleY = 1f
            browserTab.webView.translationX = 0f
            browserTab.webView.translationY = 0f
        }
        tab?.webView?.evaluateJavascript(
            """
            (function() {
              const styleNode = document.getElementById('yt_next_landscape_mode');
              if (styleNode) styleNode.remove();
              const scriptNode = document.getElementById('yt_next_landscape_script');
              if (scriptNode) scriptNode.remove();
              if (window.__ytNextR2fHandlers) {
                document.removeEventListener('click', window.__ytNextR2fHandlers.blockNativeFs, true);
                document.removeEventListener('dblclick', window.__ytNextR2fHandlers.blockNativeFs, true);
                document.removeEventListener('keydown', window.__ytNextR2fHandlers.blockNativeFs, true);
                document.removeEventListener('fullscreenchange', window.__ytNextR2fHandlers.onFsChange, true);
                window.__ytNextR2fHandlers = null;
              }
              document.documentElement.style.removeProperty('overflow');
              document.body.style.removeProperty('overflow');
              return true;
            })();
            """.trimIndent(),
            null
        )
        if (wasActive) {
            disableSystemImmersiveMode()
        }
        updateBrowserChromeVisibility()
    }

    private fun updateBrowserChromeVisibility() {
        val tab = currentTab()
        val isCustomFullscreen = tab?.chromeClient?.isInCustomView == true
        val isLandscapeWatch = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE &&
            isCurrentTabWatchPage()
        val hideChrome = isCustomFullscreen || landscapeVideoModeActive || isLandscapeWatch
        toolbar.visibility = if (hideChrome) View.GONE else View.VISIBLE
        tabLayout.visibility = View.GONE
    }

    private fun isCurrentTabWatchPage(): Boolean {
        val tab = currentTab() ?: return false
        val url = tab.webView.url ?: tab.url
        return isWatchYouTubeUrl(url)
    }

    private fun isWatchYouTubeUrl(url: String): Boolean {
        if (url.isBlank()) return false
        val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return false
        val host = uri.host?.lowercase().orEmpty()
        if (!host.contains("youtube.com") && host != "youtu.be") return false
        return host == "youtu.be" || uri.path.orEmpty().startsWith("/watch")
    }

    private fun scheduleWatchViewportStabilization(tabId: String, finishedUrl: String) {
        val tab = browserTabs[tabId] ?: return
        tab.watchStabilizationGeneration += 1
        val generation = tab.watchStabilizationGeneration
        val pageLoadGeneration = tab.pageLoadGeneration
        if (!isWatchYouTubeUrl(finishedUrl)) {
            tab.webView.postDelayed({
                val currentTab = browserTabs[tabId] ?: return@postDelayed
                if (currentTab.watchStabilizationGeneration != generation) return@postDelayed
                val currentUrl = currentTab.webView.url ?: currentTab.url
                if (isWatchYouTubeUrl(currentUrl)) return@postDelayed
                completeTabLoading(tabId, pageLoadGeneration)
            }, NON_WATCH_OVERLAY_HIDE_DELAY_MS)
            return
        }

        tab.webView.postDelayed({
            val currentTab = browserTabs[tabId] ?: return@postDelayed
            if (currentTab.watchStabilizationGeneration != generation) return@postDelayed
            val currentUrl = currentTab.webView.url ?: currentTab.url
            if (!isWatchYouTubeUrl(currentUrl)) return@postDelayed
            stabilizeYouTubeViewport(tabId, currentUrl)
            waitForWatchQuietAndComplete(tabId, generation, pageLoadGeneration, attempt = 0)
        }, WATCH_VIEWPORT_STABILIZE_DELAY_MS)
    }

    private fun waitForWatchQuietAndComplete(
        tabId: String,
        watchGeneration: Long,
        pageLoadGeneration: Long,
        attempt: Int
    ) {
        val tab = browserTabs[tabId] ?: return
        if (tab.watchStabilizationGeneration != watchGeneration) return
        val currentUrl = tab.webView.url ?: tab.url
        if (!isWatchYouTubeUrl(currentUrl)) {
            completeTabLoading(tabId, pageLoadGeneration)
            return
        }
        tab.webView.evaluateJavascript(
            """
            (function() {
              if (!window.__ytNextLayoutProbe) {
                window.__ytNextLayoutProbe = { lastMutationAt: Date.now() };
                const target = document.body || document.documentElement;
                if (target) {
                  const observer = new MutationObserver(function() {
                    window.__ytNextLayoutProbe.lastMutationAt = Date.now();
                  });
                  observer.observe(target, {
                    childList: true,
                    subtree: true,
                    attributes: true
                  });
                  window.__ytNextLayoutProbe.observer = observer;
                }
              }
              const probe = window.__ytNextLayoutProbe;
              const quietMs = Date.now() - (probe.lastMutationAt || Date.now());
              const player =
                document.querySelector('#movie_player') ||
                document.querySelector('.html5-video-player') ||
                document.querySelector('#player');
              const rect = player ? player.getBoundingClientRect() : null;
              const viewportWidth = Math.min(
                window.innerWidth || 0,
                document.documentElement ? document.documentElement.clientWidth : window.innerWidth || 0
              );
              return JSON.stringify({
                quietMs: quietMs,
                playerPresent: !!player,
                playerX: rect ? rect.x : null,
                playerRight: rect ? rect.right : null,
                viewportWidth: viewportWidth
              });
            })();
            """.trimIndent()
        ) { raw ->
            val current = browserTabs[tabId] ?: return@evaluateJavascript
            if (current.watchStabilizationGeneration != watchGeneration) return@evaluateJavascript
            val settled = isWatchLayoutSettled(raw)
            if (settled || attempt >= WATCH_QUIET_MAX_ATTEMPTS) {
                completeTabLoading(tabId, pageLoadGeneration)
                return@evaluateJavascript
            }
            current.webView.postDelayed({
                waitForWatchQuietAndComplete(tabId, watchGeneration, pageLoadGeneration, attempt + 1)
            }, WATCH_QUIET_RETRY_DELAY_MS)
        }
    }

    private fun isWatchLayoutSettled(raw: String?): Boolean {
        if (raw.isNullOrBlank() || raw == "null") return false
        val json = runCatching { JSONObject(raw) }.getOrNull() ?: return false
        val quietMs = json.optDouble("quietMs", 0.0)
        val playerPresent = json.optBoolean("playerPresent", false)
        val playerX = json.optDouble("playerX", Double.NaN)
        val playerRight = json.optDouble("playerRight", Double.NaN)
        val viewportWidth = json.optDouble("viewportWidth", Double.NaN)
        if (!playerPresent || playerX.isNaN() || playerRight.isNaN() || viewportWidth.isNaN()) {
            return false
        }
        val geometryStable = kotlin.math.abs(playerX) <= 2.0 && playerRight <= viewportWidth + 2.0
        return quietMs >= WATCH_QUIET_REQUIRED_MS && geometryStable
    }

    private fun onTabMainNavigationStarted(tabId: String) {
        val tab = browserTabs[tabId] ?: return
        val now = SystemClock.uptimeMillis()
        val chainInProgress = tab.loadingOverlayVisible && tab.loadingStartedAtMs > 0L
        if (!chainInProgress) {
            tab.pageLoadGeneration += 1
            tab.loadingStartedAtMs = now
        }
        val generation = tab.pageLoadGeneration
        tab.loadingLastSignalAtMs = now
        tab.loadingOverlayVisible = true
        tab.loadingProgress = 8
        if (selectedTabId == tabId) {
            showLoadingOverlay(tab.loadingProgress)
        }
        tab.webView.postDelayed({
            val currentTab = browserTabs[tabId] ?: return@postDelayed
            if (currentTab.pageLoadGeneration != generation) return@postDelayed
            if (!currentTab.loadingOverlayVisible) return@postDelayed
            if (currentTab.loadingStartedAtMs <= 0L) return@postDelayed
            val elapsed = SystemClock.uptimeMillis() - currentTab.loadingStartedAtMs
            if (elapsed < LOADING_OVERLAY_FAILSAFE_MS) return@postDelayed
            completeTabLoading(tabId, generation)
        }, LOADING_OVERLAY_FAILSAFE_MS)
        scheduleOverlayStallWatchdog(tabId, generation)
    }

    private fun onTabProgress(tabId: String, progress: Int) {
        val tab = browserTabs[tabId] ?: return
        tab.loadingLastSignalAtMs = SystemClock.uptimeMillis()
        if (progress in 1..99) {
            tab.loadingOverlayVisible = true
            tab.loadingProgress = progress.coerceAtLeast(8)
            if (selectedTabId == tabId) {
                showLoadingOverlay(tab.loadingProgress)
            }
            return
        }
        if (progress >= 100) {
            tab.loadingProgress = 100
            if (selectedTabId == tabId && tab.loadingOverlayVisible) {
                showLoadingOverlay(tab.loadingProgress)
            }
        }
    }

    private fun completeTabLoading(tabId: String, generation: Long?) {
        val tab = browserTabs[tabId] ?: return
        val targetGeneration = generation ?: tab.pageLoadGeneration
        if (tab.pageLoadGeneration != targetGeneration) return
        tab.loadingOverlayVisible = false
        tab.loadingStartedAtMs = 0L
        tab.loadingLastSignalAtMs = 0L
        if (selectedTabId == tabId) {
            hideLoadingOverlay()
        }
    }

    private fun scheduleOverlayStallWatchdog(tabId: String, generation: Long) {
        val tab = browserTabs[tabId] ?: return
        tab.webView.postDelayed({
            val currentTab = browserTabs[tabId] ?: return@postDelayed
            if (currentTab.pageLoadGeneration != generation) return@postDelayed
            if (!currentTab.loadingOverlayVisible) return@postDelayed
            val now = SystemClock.uptimeMillis()
            val lastSignal = currentTab.loadingLastSignalAtMs
            if (lastSignal > 0L && now - lastSignal >= LOADING_OVERLAY_STALL_TIMEOUT_MS) {
                completeTabLoading(tabId, generation)
                return@postDelayed
            }
            scheduleOverlayStallWatchdog(tabId, generation)
        }, LOADING_OVERLAY_WATCHDOG_INTERVAL_MS)
    }

    private fun refreshLoadingOverlayForSelectedTab() {
        val tab = currentTab()
        if (tab?.loadingOverlayVisible == true) {
            showLoadingOverlay(tab.loadingProgress.coerceAtLeast(8))
        } else {
            hideLoadingOverlay()
        }
    }

    private fun showLoadingOverlay(progress: Int) {
        if (currentTab()?.chromeClient?.isInCustomView == true) return
        loadingOverlay.bringToFront()
        loadingOverlay.visibility = View.VISIBLE
        loadingProgress.progress = progress.coerceIn(8, 100)
        loadingLabel.text = getString(R.string.loading_page)
    }

    private fun hideLoadingOverlay() {
        loadingOverlay.visibility = View.GONE
    }

    private fun applyBrowsingMode(tabId: String, url: String) {
        val tab = browserTabs[tabId] ?: return
        val useDesktopMode = shouldUseDesktopMode(url)
        if (tab.isDesktopMode == useDesktopMode) return
        tab.isDesktopMode = useDesktopMode
        WebViewFactory.setDesktopMode(tab.webView, useDesktopMode)
    }

    private fun stabilizeYouTubeViewport(tabId: String, url: String) {
        val tab = browserTabs[tabId] ?: return
        val parsed = Uri.parse(url)
        val host = parsed.host?.lowercase().orEmpty()
        if (!host.contains("youtube.com")) return
        val isWatch = parsed.path?.startsWith("/watch") == true
        if (!isWatch) return
        val shouldNormalizeWatchPlayer = isWatch &&
            !landscapeVideoModeActive &&
            resources.configuration.orientation != Configuration.ORIENTATION_LANDSCAPE
        tab.webView.evaluateJavascript(
            """
            (function() {
              if (document.documentElement) {
                document.documentElement.style.zoom = '1';
                document.documentElement.style.width = '100%';
              }
              if (document.body) {
                document.body.style.zoom = '1';
                document.body.style.width = '100%';
              }
              const isWatch = ${if (isWatch) "true" else "false"};
              const normalizeWatchPlayer = ${if (shouldNormalizeWatchPlayer) "true" else "false"};
                if (isWatch) {
                if (document.documentElement) {
                  document.documentElement.style.overflowX = 'hidden';
                }
                if (document.body) {
                  document.body.style.overflowX = 'hidden';
                  document.body.style.maxWidth = '100vw';
                }
                const app = document.querySelector('ytd-app');
                if (app) {
                  app.style.maxWidth = '100vw';
                  app.style.minWidth = '0';
                  app.style.overflowX = 'hidden';
                }
                const primary = document.querySelector('#primary');
                if (primary) {
                  primary.style.maxWidth = '100vw';
                  primary.style.minWidth = '0';
                }
                const player = document.querySelector('#player');
                if (player) {
                  player.style.maxWidth = '100vw';
                  player.style.marginLeft = 'auto';
                  player.style.marginRight = 'auto';
                }
                const flexy = document.querySelector('ytd-watch-flexy');
                if (flexy) {
                  flexy.style.maxWidth = '100vw';
                }
                if (normalizeWatchPlayer) {
                  const moviePlayer = document.querySelector('#movie_player');
                  if (moviePlayer) {
                    moviePlayer.style.setProperty('transform', 'none', 'important');
                    moviePlayer.style.setProperty('left', '0px', 'important');
                    moviePlayer.style.setProperty('right', 'auto', 'important');
                    moviePlayer.style.setProperty('margin-left', '0px', 'important');
                    moviePlayer.style.setProperty('margin-right', '0px', 'important');
                    moviePlayer.style.setProperty('width', '100vw', 'important');
                    moviePlayer.style.setProperty('max-width', '100vw', 'important');
                  }
                  const html5Player = document.querySelector('.html5-video-player');
                  if (html5Player) {
                    html5Player.style.setProperty('transform', 'none', 'important');
                    html5Player.style.setProperty('left', '0px', 'important');
                    html5Player.style.setProperty('right', 'auto', 'important');
                    html5Player.style.setProperty('margin-left', '0px', 'important');
                    html5Player.style.setProperty('margin-right', '0px', 'important');
                    html5Player.style.setProperty('width', '100vw', 'important');
                    html5Player.style.setProperty('max-width', '100vw', 'important');
                  }
                  const html5Container = document.querySelector('.html5-video-container');
                  if (html5Container) {
                    html5Container.style.setProperty('transform', 'none', 'important');
                    html5Container.style.setProperty('left', '0px', 'important');
                    html5Container.style.setProperty('right', 'auto', 'important');
                    html5Container.style.setProperty('margin-left', '0px', 'important');
                    html5Container.style.setProperty('margin-right', '0px', 'important');
                    html5Container.style.setProperty('width', '100vw', 'important');
                    html5Container.style.setProperty('max-width', '100vw', 'important');
                  }
                  const video = document.querySelector('video');
                  if (video) {
                    video.style.setProperty('transform', 'none', 'important');
                    video.style.setProperty('left', '0px', 'important');
                    video.style.setProperty('right', 'auto', 'important');
                    video.style.setProperty('margin-left', '0px', 'important');
                    video.style.setProperty('margin-right', '0px', 'important');
                    video.style.setProperty('width', '100vw', 'important');
                    video.style.setProperty('max-width', '100vw', 'important');
                    video.style.setProperty('object-fit', 'contain', 'important');
                  }
                }
              }
              window.scrollTo(0, 0);
              return true;
            })();
            """.trimIndent(),
            null
        )
        if (shouldNormalizeWatchPlayer) {
            tab.webView.postDelayed({
                tab.webView.evaluateJavascript(
                    """
                    (function() {
                      const enforce = function() {
                        const moviePlayer = document.querySelector('#movie_player');
                        const html5Player = document.querySelector('.html5-video-player');
                        const html5Container = document.querySelector('.html5-video-container');
                        const video = document.querySelector('video');
                        if (!moviePlayer) return false;
                        const force = function(node) {
                          if (!node) return;
                          node.style.setProperty('transform', 'none', 'important');
                          node.style.setProperty('left', '0px', 'important');
                          node.style.setProperty('right', 'auto', 'important');
                          node.style.setProperty('margin-left', '0px', 'important');
                          node.style.setProperty('margin-right', '0px', 'important');
                          node.style.setProperty('width', '100vw', 'important');
                          node.style.setProperty('max-width', '100vw', 'important');
                        };
                        force(moviePlayer);
                        force(html5Player);
                        force(html5Container);
                        if (video) {
                          force(video);
                          video.style.setProperty('object-fit', 'contain', 'important');
                        }
                        return true;
                      };
                      enforce();
                      window.requestAnimationFrame(function() {
                        enforce();
                        window.requestAnimationFrame(enforce);
                      });
                      window.setTimeout(enforce, 200);
                      window.setTimeout(enforce, 800);
                      return true;
                    })();
                    """.trimIndent(),
                    null
                )
            }, 120L)
        }
        if (isWatch && !landscapeVideoModeActive) {
            tab.webView.post {
                tab.webView.translationX = 0f
                tab.webView.translationY = 0f
                tab.webView.scaleX = 1f
                tab.webView.scaleY = 1f
            }
        }
        if (shouldNormalizeWatchPlayer) {
            tab.webView.postDelayed({
                tab.webView.evaluateJavascript(
                    """
                    (function() {
                      const moviePlayer = document.querySelector('#movie_player');
                      if (!moviePlayer) return 'no-player';
                      const rect = moviePlayer.getBoundingClientRect();
                      return JSON.stringify({ x: rect.x, y: rect.y, width: rect.width, height: rect.height });
                    })();
                    """.trimIndent()
                ) { rectJson ->
                    Log.i("YTNEXT_WATCH_FIX", "post-fix moviePlayerRect=$rectJson")
                }
            }, 950L)
        }
        if (shouldNormalizeWatchPlayer) {
            tab.webView.postDelayed({
                tab.webView.evaluateJavascript(
                    """
                    (function() {
                      const moviePlayer = document.querySelector('#movie_player');
                      const video = document.querySelector('video');
                      if (!moviePlayer) return false;
                      const rect = moviePlayer.getBoundingClientRect();
                      if (!rect || Math.abs(rect.x) < 1) return false;
                      const offset = -rect.x;
                      moviePlayer.style.setProperty('transform', 'translateX(' + offset + 'px)', 'important');
                      if (video) {
                        video.style.setProperty('transform', 'translateX(' + offset + 'px)', 'important');
                      }
                      return true;
                    })();
                    """.trimIndent(),
                    null
                )
            }, 1100L)
        }
    }

    private fun shouldUseDesktopMode(url: String): Boolean {
        if (url.isBlank()) return false
        val uri = Uri.parse(url)
        val host = uri.host?.lowercase().orEmpty()
        val path = uri.path.orEmpty()
        val isYouTubeHost = host.contains("youtube.com") || host == "youtu.be"
        if (!isYouTubeHost) return false
        return host == "youtu.be" || path.startsWith("/watch")
    }

    private fun normalizeYouTubeHostForMode(url: String, desktopMode: Boolean): String {
        val uri = Uri.parse(url)
        val host = uri.host?.lowercase() ?: return url
        val isYouTubeHost = host.contains("youtube.com")
        if (!isYouTubeHost) return url
        val targetHost = if (desktopMode) "www.youtube.com" else "m.youtube.com"
        if (host == targetHost) return url
        return uri.buildUpon().authority(targetHost).build().toString()
    }

    private fun normalizeInternalYouTubeUrl(url: String): String {
        if (url.isBlank()) return url
        val uri = Uri.parse(url)
        val host = uri.host?.lowercase().orEmpty()
        if (isYouTubeAuthenticationPath(uri)) {
            return url
        }
        val path = uri.path.orEmpty()
        val isWatch = path.startsWith("/watch")
        if (host == "youtu.be") {
            val videoId = uri.lastPathSegment.orEmpty()
            if (videoId.isNotBlank()) {
                return Uri.parse("https://www.youtube.com/watch")
                    .buildUpon()
                    .appendQueryParameter("v", videoId)
                    .build()
                    .toString()
            }
        }
        if (shouldUseDesktopMode(url) && host == "m.youtube.com") {
            return sanitizeYouTubeQuery(uri.buildUpon().authority("www.youtube.com").build()).toString()
        }
        if (!shouldUseDesktopMode(url) && host == "www.youtube.com") {
            return sanitizeYouTubeQuery(uri.buildUpon().authority("m.youtube.com").build()).toString()
        }
        if (host.contains("youtube.com")) {
            return sanitizeYouTubeQuery(uri).toString()
        }
        return url
    }

    private fun sanitizeYouTubeQuery(uri: Uri): Uri {
        return uri.buildUpon()
            .clearQuery()
            .apply {
                uri.queryParameterNames.forEach { key ->
                    if (key == "persist_app") return@forEach
                    if (key == "app") return@forEach
                    uri.getQueryParameters(key).forEach { value ->
                        appendQueryParameter(key, value)
                    }
                }
            }
            .build()
    }

    private fun isYouTubeAuthenticationPath(uri: Uri): Boolean {
        val host = uri.host?.lowercase().orEmpty()
        if (!host.contains("youtube.com")) return false
        val path = uri.path.orEmpty().lowercase()
        return path.startsWith("/signin") ||
            path.startsWith("/accounts") ||
            path.startsWith("/o/oauth")
    }

    private fun attachLandscapePinchToWebView(webView: WebView) {
        var downX = 0f
        var downY = 0f
        var startTranslationX = 0f
        var startTranslationY = 0f
        var isPanning = false
        var gestureCaptured = false
        var cancelSent = false
        val touchSlop = ViewConfiguration.get(this).scaledTouchSlop.toFloat()

        val detector = ScaleGestureDetector(
            this,
            object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScale(scaleDetector: ScaleGestureDetector): Boolean {
                    landscapeVideoScale = (landscapeVideoScale * scaleDetector.scaleFactor)
                        .coerceIn(1f, 3f)
                    val maxX = ((landscapeVideoScale - 1f) * webView.width) / 2f
                    val maxY = ((landscapeVideoScale - 1f) * webView.height) / 2f
                    landscapeVideoTranslationX = landscapeVideoTranslationX.coerceIn(-maxX, maxX)
                    landscapeVideoTranslationY = landscapeVideoTranslationY.coerceIn(-maxY, maxY)
                    webView.pivotX = webView.width / 2f
                    webView.pivotY = webView.height / 2f
                    webView.scaleX = landscapeVideoScale
                    webView.scaleY = landscapeVideoScale
                    webView.translationX = landscapeVideoTranslationX
                    webView.translationY = landscapeVideoTranslationY
                    return true
                }
            }
        )
        webView.setOnTouchListener { _, event ->
            if (!landscapeVideoModeActive) return@setOnTouchListener false
            detector.onTouchEvent(event)
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    startTranslationX = landscapeVideoTranslationX
                    startTranslationY = landscapeVideoTranslationY
                    isPanning = false
                    gestureCaptured = false
                    cancelSent = false
                    false
                }

                MotionEvent.ACTION_MOVE -> {
                    if (event.pointerCount > 1 || detector.isInProgress) {
                        if (!cancelSent) {
                            MotionEvent.obtain(event).also { cancelEvent ->
                                cancelEvent.action = MotionEvent.ACTION_CANCEL
                                webView.onTouchEvent(cancelEvent)
                                cancelEvent.recycle()
                            }
                            cancelSent = true
                        }
                        gestureCaptured = true
                        return@setOnTouchListener true
                    }
                    if (landscapeVideoScale <= 1.01f) {
                        return@setOnTouchListener false
                    }
                    val dx = event.rawX - downX
                    val dy = event.rawY - downY
                    if (!isPanning) {
                        if (kotlin.math.abs(dx) < touchSlop && kotlin.math.abs(dy) < touchSlop) {
                            return@setOnTouchListener false
                        }
                        isPanning = true
                    }
                    if (!cancelSent) {
                        MotionEvent.obtain(event).also { cancelEvent ->
                            cancelEvent.action = MotionEvent.ACTION_CANCEL
                            webView.onTouchEvent(cancelEvent)
                            cancelEvent.recycle()
                        }
                        cancelSent = true
                    }
                    gestureCaptured = true
                    val maxX = ((landscapeVideoScale - 1f) * webView.width) / 2f
                    val maxY = ((landscapeVideoScale - 1f) * webView.height) / 2f
                    landscapeVideoTranslationX = (startTranslationX + dx).coerceIn(-maxX, maxX)
                    landscapeVideoTranslationY = (startTranslationY + dy).coerceIn(-maxY, maxY)
                    webView.translationX = landscapeVideoTranslationX
                    webView.translationY = landscapeVideoTranslationY
                    true
                }

                MotionEvent.ACTION_POINTER_DOWN -> {
                    if (!cancelSent) {
                        MotionEvent.obtain(event).also { cancelEvent ->
                            cancelEvent.action = MotionEvent.ACTION_CANCEL
                            webView.onTouchEvent(cancelEvent)
                            cancelEvent.recycle()
                        }
                        cancelSent = true
                    }
                    gestureCaptured = true
                    true
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (landscapeVideoScale < 1.02f) {
                        landscapeVideoScale = 1f
                        landscapeVideoTranslationX = 0f
                        landscapeVideoTranslationY = 0f
                        webView.scaleX = 1f
                        webView.scaleY = 1f
                        webView.translationX = 0f
                        webView.translationY = 0f
                    }
                    val consumed = isPanning || gestureCaptured
                    isPanning = false
                    gestureCaptured = false
                    cancelSent = false
                    consumed
                }

                else -> gestureCaptured || event.pointerCount > 1
            }
        }
    }

    private fun enableSystemImmersiveMode() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.insetsController?.hide(
                android.view.WindowInsets.Type.statusBars() or
                    android.view.WindowInsets.Type.navigationBars()
            )
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility =
                View.SYSTEM_UI_FLAG_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        }
    }

    private fun disableSystemImmersiveMode() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.insetsController?.show(
                android.view.WindowInsets.Type.statusBars() or
                    android.view.WindowInsets.Type.navigationBars()
            )
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_VISIBLE
        }
    }

    private fun shareLink(url: String) {
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, url)
        }
        startActivity(Intent.createChooser(shareIntent, null))
    }

    private fun copyToClipboard(url: String) {
        val clipboard = getSystemService<ClipboardManager>() ?: return
        clipboard.setPrimaryClip(ClipData.newPlainText("url", url))
        Snackbar.make(webViewContainer, R.string.url_copied, Snackbar.LENGTH_SHORT).show()
    }

    private fun openExternalUrl(uri: Uri) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, uri))
        } catch (_: ActivityNotFoundException) {
            Snackbar.make(webViewContainer, uri.toString(), Snackbar.LENGTH_SHORT).show()
        }
    }

    companion object {
        private const val DEFAULT_URL = "https://www.youtube.com/"
        private const val MAX_TAB_LABEL_LENGTH = 24
        private const val WATCH_VIEWPORT_STABILIZE_DELAY_MS = 1000L
        private const val NON_WATCH_OVERLAY_HIDE_DELAY_MS = 120L
        private const val WATCH_QUIET_REQUIRED_MS = 650.0
        private const val WATCH_QUIET_RETRY_DELAY_MS = 180L
        private const val WATCH_QUIET_MAX_ATTEMPTS = 12
        private const val LOADING_OVERLAY_FAILSAFE_MS = 15000L
        private const val LOADING_OVERLAY_STALL_TIMEOUT_MS = 6500L
        private const val LOADING_OVERLAY_WATCHDOG_INTERVAL_MS = 900L
    }
}
