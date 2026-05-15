package de.shakie.youtubenext

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
import android.util.Log
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.ViewConfiguration
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.content.getSystemService
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.core.view.WindowCompat
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.tabs.TabLayout
import de.shakie.youtubenext.browser.LinkInterceptor
import de.shakie.youtubenext.browser.YouTubeNavigationPolicy
import de.shakie.youtubenext.engine.BrowserEngine
import de.shakie.youtubenext.engine.EngineCallbacks
import de.shakie.youtubenext.engine.EngineType
import de.shakie.youtubenext.engine.gecko.GeckoBrowserEngine
import de.shakie.youtubenext.tabs.AppTab
import de.shakie.youtubenext.tabs.TabManager
import de.shakie.youtubenext.tabs.TabPersistence
import de.shakie.youtubenext.tabs.TabPreviewStore
import de.shakie.youtubenext.tabs.TabSession
import de.shakie.youtubenext.ui.TabOverviewAdapter
import de.shakie.youtubenext.ui.TabOverviewItem
import org.mozilla.geckoview.GeckoView

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
    private lateinit var browserEngine: BrowserEngine
    private lateinit var tabManager: TabManager
    private lateinit var tabPreviewStore: TabPreviewStore

    private val browserTabs = linkedMapOf<String, AppTab>()
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
        browserEngine = GeckoBrowserEngine(
            activity = this,
            shouldUseDesktopMode = ::shouldUseDesktopMode
        )
        tabManager = TabManager(TabPersistence(this))
        tabPreviewStore = TabPreviewStore(this)

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
            currentTab()?.engineTab?.exitFullscreenIfNeeded()
            disableLandscapeVideoMode()
        }
        updateBrowserChromeVisibility()
    }

    override fun onDestroy() {
        super.onDestroy()
        browserTabs.values.forEach { tab ->
            webViewContainer.removeView(tab.engineTab.view)
            tab.engineTab.stopLoading()
            tab.engineTab.destroy()
        }
        browserTabs.clear()
        browserEngine.shutdown()
    }

    private fun setupToolbar() {
        toolbar.title = null
        toolbar.subtitle = null
        reloadButton.setOnClickListener {
            currentTab()?.engineTab?.reload()
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
        toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_new_tab -> {
                    createAndSelectTab(DEFAULT_URL)
                    true
                }

                R.id.action_duplicate_tab -> {
                    duplicateCurrentTab()
                    true
                }

                R.id.action_close_tab -> {
                    closeCurrentTab()
                    true
                }

                R.id.action_reload -> {
                    currentTab()?.engineTab?.reload()
                    true
                }

                else -> false
            }
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
                if (current?.engineTab?.isInCustomView() == true) {
                    current.engineTab.exitFullscreenIfNeeded()
                    return
                }
                if (landscapeVideoModeActive) {
                    disableLandscapeVideoMode()
                    return
                }
                if (current != null && navigateBackInTabHistory(current)) {
                    return
                }
                if (current?.engineTab?.canGoBack() == true) {
                    current.engineTab.goBack()
                } else {
                    finish()
                }
            }
        })
    }

    private fun restoreOrCreateInitialTab() {
        val restored = tabManager.restore()
        tabPreviewStore.prune(restored.map { it.id }.toSet())
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

    private fun createAndSelectTab(url: String): AppTab {
        val normalizedUrl = normalizeStartUrl(url)
        val targetUrl = normalizedUrl
        val session = tabManager.create(targetUrl, "")
        val browserTab = createBrowserTab(session)
        syncTabLayout()
        selectTab(browserTab.id, persistSelection = false)
        return browserTab
    }

    private fun createBrowserTab(session: TabSession): AppTab {
        val engineTab = browserEngine.createTab(
            tabId = session.id,
            initialUrl = session.url,
            title = session.title,
            callbacks = EngineCallbacks(
                onOpenExternalUrl = ::openExternalUrl,
                onMainNavigationStarted = { tabId, url ->
                    onTabMainNavigationStarted(tabId)
                },
                onMainUrlUpdated = { tabId, url ->
                    updateTabState(tabId, newUrl = url)
                },
                onMainTitleUpdated = { tabId, title ->
                    updateTabState(tabId, newTitle = title)
                },
                onMainPageFinished = { tabId, url ->
                    scheduleWatchViewportStabilization(tabId, url)
                },
                onProgressChanged = { tabId, progress ->
                    updateToolbarState()
                    onTabProgress(tabId, progress)
                },
                onNewTabRequest = { targetUrl ->
                    createAndSelectTab(targetUrl)
                },
                onFullscreenChanged = { _, isFullscreen ->
                    if (isFullscreen) {
                        disableLandscapeVideoMode()
                        hideLoadingOverlay()
                    }
                    updateBrowserChromeVisibility()
                },
                onLoadError = { tabId ->
                    completeTabLoading(tabId, browserTabs[tabId]?.pageLoadGeneration)
                    Snackbar.make(webViewContainer, R.string.page_load_error, Snackbar.LENGTH_SHORT).show()
                }
            )
        )

        val browserTab = AppTab(
            id = session.id,
            engineTab = engineTab,
            isDesktopMode = shouldUseDesktopMode(session.url),
            title = session.title,
            url = session.url
        )
        browserTab.engineTab.setDesktopMode(browserTab.isDesktopMode)
        browserTab.engineTab.view.layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        browserTab.engineTab.view.visibility = View.GONE
        configureLongPressMenu(browserTab)
        browserTabs[session.id] = browserTab
        webViewContainer.addView(browserTab.engineTab.view)
        return browserTab
    }

    private fun updateTabState(tabId: String, newUrl: String? = null, newTitle: String? = null) {
        val tab = browserTabs[tabId] ?: return
        val updatedUrl = newUrl
            ?.takeIf(YouTubeNavigationPolicy::isUserVisibleUrl)
            ?: tab.url
        val updatedTitle = normalizeTabTitle(newTitle ?: tab.title)
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
        currentTab()
            ?.takeIf { it.id != tabId }
            ?.let(::captureAndStoreTabPreview)
        selectedTabId = tabId
        browserTabs.forEach { (id, tab) ->
            tab.engineTab.view.visibility = if (id == tabId) View.VISIBLE else View.GONE
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
        val normalizedUrl = normalizeStartUrl(url)
        val targetUrl = normalizedUrl
        tab.engineTab.loadUrl(targetUrl)
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
            }.let(::normalizeTabTitle)
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

    private fun currentTab(): AppTab? {
        return selectedTabId?.let { browserTabs[it] }
    }

    private fun navigateBackInTabHistory(tab: AppTab): Boolean {
        if (tab.historyIndex <= 0) return false
        val currentCanonicalUrl = canonicalHistoryUrl(tab.url)
        do {
            tab.historyIndex -= 1
        } while (
            tab.historyIndex > 0 &&
            tab.navigationHistory.getOrNull(tab.historyIndex) == currentCanonicalUrl
        )
        tab.pendingHistoryNavigation = true
        tab.engineTab.loadUrl(tab.navigationHistory[tab.historyIndex])
        return true
    }

    private fun recordTabHistory(tab: AppTab, url: String) {
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
        if (!YouTubeNavigationPolicy.isUserVisibleUrl(url)) return ""
        val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return url
        if (!YouTubeNavigationPolicy.isWatchUrl(url)) {
            return uri.buildUpon()
                .fragment(null)
                .build()
                .toString()
        }

        val videoId = if (uri.host?.lowercase() == "youtu.be") {
            uri.pathSegments.firstOrNull()
        } else {
            uri.getQueryParameter("v")
        }
        if (videoId.isNullOrBlank()) return url
        return Uri.Builder()
            .scheme("https")
            .authority("www.youtube.com")
            .path("watch")
            .appendQueryParameter("v", videoId)
            .build()
            .toString()
    }

    private fun updateToolbarState() {
        val current = currentTab()
        urlTextView.text = current?.url?.takeIf { it.isNotBlank() }?.let(::formatToolbarUrl).orEmpty()
    }

    private fun closeTabById(tabId: String, showMessage: Boolean = true) {
        val tab = browserTabs.remove(tabId) ?: return
        webViewContainer.removeView(tab.engineTab.view)
        tab.engineTab.stopLoading()
        tab.engineTab.destroy()
        tabPreviewStore.delete(tabId)

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
            }.let(::normalizeTabTitle)
            val previewBitmap = tabPreviewStore.load(session.id)
                ?: tab?.takeIf { it.engineTab.view.visibility == View.VISIBLE }
                    ?.let(::captureTabPreviewFallback)
            TabOverviewItem(
                id = session.id,
                title = displayTitle,
                preview = previewBitmap,
                isActive = session.id == selectedTabId
            )
        }
        adapter.submitList(items)
        browserTabs.values.forEach { tab ->
            captureAndStoreTabPreview(tab) { bitmap ->
                adapter.updatePreview(tab.id, bitmap)
            }
        }
    }

    private fun captureAndStoreTabPreview(tab: AppTab, onPreview: ((Bitmap) -> Unit)? = null) {
        val contentView = tab.engineTab.view
        if (contentView.width <= 0 || contentView.height <= 0) return
        if (contentView is GeckoView) {
            if (contentView.visibility != View.VISIBLE) return
            contentView.capturePixels().accept(
                { captured ->
                    if (captured == null) return@accept
                    val preview = createTabPreviewBitmap(captured)
                    tabPreviewStore.save(tab.id, preview)
                    runOnUiThread {
                        onPreview?.invoke(preview)
                    }
                },
                { /* Hidden or failed Gecko captures should not overwrite saved previews. */ }
            )
            return
        }
        captureTabPreviewFallback(tab)?.let { preview ->
            onPreview?.invoke(preview)
        }
    }

    private fun captureTabPreviewFallback(tab: AppTab): Bitmap? {
        val contentView = tab.engineTab.view
        val width = contentView.width.takeIf { it > 0 } ?: return null
        val height = contentView.height.takeIf { it > 0 } ?: return null
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        contentView.draw(canvas)
        val preview = createTabPreviewBitmap(bitmap)
        tabPreviewStore.save(tab.id, preview)
        return preview
    }

    private fun createTabPreviewBitmap(source: Bitmap): Bitmap {
        val previewWidth = 192
        val previewHeight = 120
        val bitmap = Bitmap.createBitmap(previewWidth, previewHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val targetRatio = previewWidth / previewHeight.toFloat()
        val sourceRatio = source.width / source.height.toFloat()
        val cropWidth: Int
        val cropHeight: Int
        val cropX: Int
        val cropY: Int
        val preferredTopOffset = (source.height * TAB_PREVIEW_TOP_OFFSET_RATIO).toInt()

        if (sourceRatio > targetRatio) {
            cropHeight = source.height
            cropWidth = (cropHeight * targetRatio).toInt().coerceAtMost(source.width)
            cropX = ((source.width - cropWidth) / 2).coerceAtLeast(0)
            cropY = preferredTopOffset.coerceIn(0, (source.height - cropHeight).coerceAtLeast(0))
        } else {
            cropWidth = source.width
            cropHeight = (cropWidth / targetRatio).toInt().coerceAtMost(source.height)
            cropX = 0
            cropY = preferredTopOffset.coerceIn(0, (source.height - cropHeight).coerceAtLeast(0))
        }

        val sourceRect = android.graphics.Rect(cropX, cropY, cropX + cropWidth, cropY + cropHeight)
        val targetRect = android.graphics.Rect(0, 0, previewWidth, previewHeight)
        canvas.drawBitmap(source, sourceRect, targetRect, null)
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

    private fun configureLongPressMenu(tab: AppTab) {
        // Link long-press is handled inside the Gecko WebExtension because GeckoView
        // does not expose WebView-like hit test metadata.
        tab.engineTab.view.setOnLongClickListener { false }
    }

    private fun enterLandscapeVideoModeIfNeeded() {
        val tab = currentTab() ?: return
        if (!isCurrentTabWatchPage()) return
        if (tab.engineTab.isInCustomView()) return
        enableLandscapeVideoMode()
    }

    private fun enableLandscapeVideoMode() {
        val tab = currentTab() ?: return
        if (supportsLegacyWatchTweaks()) {
            tab.engineTab.evaluateJavascript(
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
        }
        landscapeVideoModeActive = true
        enableSystemImmersiveMode()
        attachLandscapePinchToEngineView(tab.engineTab.view)
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
            browserTab.engineTab.view.setOnTouchListener(null)
            browserTab.engineTab.view.scaleX = 1f
            browserTab.engineTab.view.scaleY = 1f
            browserTab.engineTab.view.translationX = 0f
            browserTab.engineTab.view.translationY = 0f
        }
        if (supportsLegacyWatchTweaks()) {
            tab?.engineTab?.evaluateJavascript(
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
        }
        if (wasActive) {
            disableSystemImmersiveMode()
        }
        updateBrowserChromeVisibility()
    }

    private fun updateBrowserChromeVisibility() {
        val tab = currentTab()
        val isCustomFullscreen = tab?.engineTab?.isInCustomView() == true
        val isLandscapeWatch = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE &&
            isCurrentTabWatchPage()
        val hideChrome = isCustomFullscreen || landscapeVideoModeActive || isLandscapeWatch
        toolbar.visibility = if (hideChrome) View.GONE else View.VISIBLE
        tabLayout.visibility = View.GONE
    }

    private fun isCurrentTabWatchPage(): Boolean {
        val tab = currentTab() ?: return false
        val url = tab.url
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
            completeTabLoading(tabId, pageLoadGeneration)
            return
        }

        tab.engineTab.view.postDelayed({
            val currentTab = browserTabs[tabId] ?: return@postDelayed
            if (currentTab.watchStabilizationGeneration != generation) return@postDelayed
            val currentUrl = currentTab.url
            if (!isWatchYouTubeUrl(currentUrl)) return@postDelayed
            stabilizeYouTubeViewport(tabId, currentUrl)
            completeTabLoading(tabId, pageLoadGeneration)
        }, WATCH_VIEWPORT_STABILIZE_DELAY_MS)
    }

    private fun onTabMainNavigationStarted(tabId: String) {
        val tab = browserTabs[tabId] ?: return
        tab.pageLoadGeneration += 1
        tab.loadingOverlayVisible = true
        tab.loadingProgress = 8
        if (selectedTabId == tabId) {
            showLoadingOverlay(tab.loadingProgress)
        }
    }

    private fun onTabProgress(tabId: String, progress: Int) {
        val tab = browserTabs[tabId] ?: return
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
            if (!supportsLegacyWatchTweaks() || !isWatchYouTubeUrl(tab.url)) {
                completeTabLoading(tabId, tab.pageLoadGeneration)
            }
        }
    }

    private fun normalizeTabTitle(rawTitle: String): String {
        val cleaned = rawTitle.replace(LEADING_COUNT_PREFIX_REGEX, "").trim()
        return if (cleaned.isBlank()) rawTitle.trim() else cleaned
    }

    private fun completeTabLoading(tabId: String, generation: Long?) {
        val tab = browserTabs[tabId] ?: return
        val targetGeneration = generation ?: tab.pageLoadGeneration
        if (tab.pageLoadGeneration != targetGeneration) return
        tab.loadingOverlayVisible = false
        if (selectedTabId == tabId) {
            loadingOverlay.postDelayed({
                val currentTab = browserTabs[tabId] ?: return@postDelayed
                if (currentTab.pageLoadGeneration != targetGeneration) return@postDelayed
                if (currentTab.loadingOverlayVisible) return@postDelayed
                hideLoadingOverlay()
                captureAndStoreTabPreview(currentTab)
            }, OVERLAY_HIDE_DELAY_MS)
        }
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
        if (currentTab()?.engineTab?.isInCustomView() == true) return
        loadingOverlay.bringToFront()
        loadingOverlay.visibility = View.VISIBLE
        loadingProgress.progress = progress.coerceIn(8, 100)
        loadingLabel.text = getString(R.string.loading_page)
    }

    private fun hideLoadingOverlay() {
        loadingOverlay.visibility = View.GONE
    }

    private fun stabilizeYouTubeViewport(tabId: String, url: String) {
        if (!supportsLegacyWatchTweaks()) return
        val tab = browserTabs[tabId] ?: return
        val parsed = Uri.parse(url)
        val host = parsed.host?.lowercase().orEmpty()
        if (!host.contains("youtube.com")) return
        val isWatch = parsed.path?.startsWith("/watch") == true
        if (!isWatch) return
        val shouldNormalizeWatchPlayer = isWatch &&
            !landscapeVideoModeActive &&
            resources.configuration.orientation != Configuration.ORIENTATION_LANDSCAPE
        tab.engineTab.evaluateJavascript(
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
            tab.engineTab.view.postDelayed({
                tab.engineTab.evaluateJavascript(
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
            tab.engineTab.view.post {
                tab.engineTab.view.translationX = 0f
                tab.engineTab.view.translationY = 0f
                tab.engineTab.view.scaleX = 1f
                tab.engineTab.view.scaleY = 1f
            }
        }
        if (shouldNormalizeWatchPlayer) {
            tab.engineTab.view.postDelayed({
                tab.engineTab.evaluateJavascript(
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
            tab.engineTab.view.postDelayed({
                tab.engineTab.evaluateJavascript(
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
        return YouTubeNavigationPolicy.shouldUseDesktopMode(url)
    }

    private fun normalizeStartUrl(rawUrl: String?): String {
        if (rawUrl.isNullOrBlank()) return DEFAULT_URL
        val uri = Uri.parse(rawUrl)
        return if (uri.scheme.isNullOrBlank()) {
            "https://$rawUrl"
        } else {
            rawUrl
        }
    }

    private fun attachLandscapePinchToEngineView(contentView: View) {
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
                    val maxX = ((landscapeVideoScale - 1f) * contentView.width) / 2f
                    val maxY = ((landscapeVideoScale - 1f) * contentView.height) / 2f
                    landscapeVideoTranslationX = landscapeVideoTranslationX.coerceIn(-maxX, maxX)
                    landscapeVideoTranslationY = landscapeVideoTranslationY.coerceIn(-maxY, maxY)
                    contentView.pivotX = contentView.width / 2f
                    contentView.pivotY = contentView.height / 2f
                    contentView.scaleX = landscapeVideoScale
                    contentView.scaleY = landscapeVideoScale
                    contentView.translationX = landscapeVideoTranslationX
                    contentView.translationY = landscapeVideoTranslationY
                    return true
                }
            }
        )
        contentView.setOnTouchListener { _, event ->
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
                                contentView.onTouchEvent(cancelEvent)
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
                            contentView.onTouchEvent(cancelEvent)
                            cancelEvent.recycle()
                        }
                        cancelSent = true
                    }
                    gestureCaptured = true
                    val maxX = ((landscapeVideoScale - 1f) * contentView.width) / 2f
                    val maxY = ((landscapeVideoScale - 1f) * contentView.height) / 2f
                    landscapeVideoTranslationX = (startTranslationX + dx).coerceIn(-maxX, maxX)
                    landscapeVideoTranslationY = (startTranslationY + dy).coerceIn(-maxY, maxY)
                    contentView.translationX = landscapeVideoTranslationX
                    contentView.translationY = landscapeVideoTranslationY
                    true
                }

                MotionEvent.ACTION_POINTER_DOWN -> {
                    if (!cancelSent) {
                        MotionEvent.obtain(event).also { cancelEvent ->
                            cancelEvent.action = MotionEvent.ACTION_CANCEL
                            contentView.onTouchEvent(cancelEvent)
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
                        contentView.scaleX = 1f
                        contentView.scaleY = 1f
                        contentView.translationX = 0f
                        contentView.translationY = 0f
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
        WindowCompat.setDecorFitsSystemWindows(window, false)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.insetsController?.systemBarsBehavior =
                android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
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
        WindowCompat.setDecorFitsSystemWindows(window, true)
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
        private const val OVERLAY_HIDE_DELAY_MS = 2000L
        private const val TAB_PREVIEW_TOP_OFFSET_RATIO = 0.12f
        private val LEADING_COUNT_PREFIX_REGEX = Regex("^\\(\\d+\\)\\s*")
    }

    private fun supportsLegacyWatchTweaks(): Boolean {
        return browserEngine.type == EngineType.WEBVIEW
    }
}
