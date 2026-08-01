package de.shakie.tubenext

import android.Manifest
import android.app.ActivityManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ActivityNotFoundException
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.ViewConfiguration
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.getSystemService
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.core.view.WindowCompat
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.tabs.TabLayout
import de.shakie.tubenext.audio.AndroidBackgroundAudioCoordinator
import de.shakie.tubenext.browser.LinkInterceptor
import de.shakie.tubenext.browser.YouTubeNavigationPolicy
import de.shakie.tubenext.engine.BrowserEngine
import de.shakie.tubenext.engine.EngineCallbacks
import de.shakie.tubenext.engine.EngineHomeFeedSettings
import de.shakie.tubenext.engine.EngineTab
import de.shakie.tubenext.engine.EngineType
import de.shakie.tubenext.engine.gecko.GeckoBrowserEngine
import de.shakie.tubenext.tabs.AppTab
import de.shakie.tubenext.tabs.TabManager
import de.shakie.tubenext.tabs.TabPersistence
import de.shakie.tubenext.tabs.TabPreviewStore
import de.shakie.tubenext.tabs.TabSession
import de.shakie.tubenext.update.GitHubReleaseClient
import de.shakie.tubenext.update.UpdateAsset
import de.shakie.tubenext.update.UpdateCheckResult
import de.shakie.tubenext.update.UpdateCheckStatus
import de.shakie.tubenext.update.UpdateDownloader
import de.shakie.tubenext.update.UpdateInstallHelper
import de.shakie.tubenext.update.UpdateNotifier
import de.shakie.tubenext.update.UpdatePreferences
import de.shakie.tubenext.update.UpdateRelease
import de.shakie.tubenext.update.VersionNames
import de.shakie.tubenext.ui.TabOverviewAdapter
import de.shakie.tubenext.ui.TabOverviewItem
import org.mozilla.geckoview.GeckoView
import java.io.File

class MainActivity : AppCompatActivity() {
    private lateinit var toolbar: MaterialToolbar
    private lateinit var tabLayout: TabLayout
    private lateinit var urlTextView: TextView
    private lateinit var reloadButton: ImageButton
    private lateinit var tabSwitcherButton: FrameLayout
    private lateinit var homeFeedMenuButton: ImageButton
    private lateinit var tabCountBadge: TextView
    private lateinit var loadingOverlay: FrameLayout
    private lateinit var loadingProgress: ProgressBar
    private lateinit var loadingLabel: TextView
    private lateinit var webViewContainer: FrameLayout
    private lateinit var fullscreenContainer: FrameLayout
    private lateinit var browserEngine: BrowserEngine
    private lateinit var tabManager: TabManager
    private lateinit var tabPreviewStore: TabPreviewStore
    private lateinit var backgroundAudioCoordinator: AndroidBackgroundAudioCoordinator
    private lateinit var preferences: SharedPreferences
    private lateinit var updatePreferences: UpdatePreferences

    private val browserTabs = linkedMapOf<String, AppTab>()
    private var selectedTabId: String? = null
    private var tabSelectionUpdateInProgress = false
    private var updateCheckInProgress = false
    private var latestUpdateResult: UpdateCheckResult? = null
    private var updateDownloadHandle: UpdateDownloader.DownloadHandle? = null
    private var settingsDialog: androidx.appcompat.app.AlertDialog? = null
    private var batteryOptimizationDialogVisible = false
    private var pendingUpdateNotificationRelease: UpdateRelease? = null
    private var updatePermissionDialogVisible = false
    private var landscapeVideoModeActive = false
    private var landscapeVideoScale = 1f
    private var landscapeVideoTranslationX = 0f
    private var landscapeVideoTranslationY = 0f
    private var geckoRenderHealthGeneration = 0L
    private var lastGeckoSurfaceRecoveryAtMs = 0L
    private val loggedProcessExitKeys = mutableSetOf<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        toolbar = findViewById(R.id.toolbar)
        tabLayout = findViewById(R.id.tabLayout)
        urlTextView = findViewById(R.id.urlText)
        reloadButton = findViewById(R.id.reloadButton)
        tabSwitcherButton = findViewById(R.id.tabSwitcherButton)
        homeFeedMenuButton = findViewById(R.id.homeFeedMenuButton)
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
        backgroundAudioCoordinator = AndroidBackgroundAudioCoordinator(applicationContext)
        preferences = getSharedPreferences(PREFERENCES_NAME, MODE_PRIVATE)
        updatePreferences = UpdatePreferences(this)

        setupToolbar()
        setupTabs()
        setupBackNavigation()
        restoreOrCreateInitialTab()
        handleIncomingIntent(intent)
        if (!maybeShowPostInstallPermissionReminder()) {
            maybeShowBatteryOptimizationHint()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingIntent(intent)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != UPDATE_NOTIFICATION_PERMISSION_REQUEST_CODE) return
        val release = pendingUpdateNotificationRelease ?: return
        pendingUpdateNotificationRelease = null
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            UpdateNotifier.showUpdateAvailable(this, release)
        } else {
            Snackbar.make(webViewContainer, R.string.update_notification_permission_denied, Snackbar.LENGTH_LONG)
                .setAction(R.string.update_action_open_manager) {
                    showUpdateManager(UpdateCheckResult(UpdateCheckStatus.UPDATE_AVAILABLE, release))
                }
                .show()
        }
    }

    override fun onPause() {
        geckoRenderHealthGeneration += 1
        currentTab()?.engineTab?.onPause()
        super.onPause()
        tabManager.persist()
    }

    override fun onResume() {
        super.onResume()
        currentTab()?.let { tab ->
            ensureTabAwake(tab.id, "activity-resume")
        }?.engineTab?.let { engineTab ->
            engineTab.onResume()
            if (::backgroundAudioCoordinator.isInitialized &&
                backgroundAudioCoordinator.consumeForegroundRecoveryPending()
            ) {
                engineTab.recoverFromAudioRouteChange()
            }
        }
        logRecentProcessExitInfo("activity-resume")
        scheduleGeckoRenderHealthCheck("activity-resume")
    }

    override fun onStart() {
        super.onStart()
        if (::backgroundAudioCoordinator.isInitialized) {
            backgroundAudioCoordinator.onAppForegrounded()
        }
        checkForUpdates(showDialog = false, force = false)
    }

    override fun onStop() {
        if (::backgroundAudioCoordinator.isInitialized) {
            prepareBackgroundAudioArtwork()
            backgroundAudioCoordinator.onAppBackgrounded()
        }
        super.onStop()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (!::browserEngine.isInitialized || !::tabManager.isInitialized) return
        Log.i("TUBENEXT_MEMORY", "onTrimMemory level=$level tabs=${browserTabs.size}")
        if (level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) {
            prepareHiddenUiMemoryTrim("trim-memory-$level")
        }
        if (level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND) {
            hibernateExpendableBackgroundTabs("trim-memory-$level")
        }
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
        updateDownloadHandle?.cancel()
        val keepBrowserSessions = isChangingConfigurations
        browserTabs.values.forEach { tab ->
            webViewContainer.removeView(tab.engineTab.view)
            if (keepBrowserSessions) {
                tab.engineTab.detach()
            } else {
                tab.engineTab.stopLoading()
                tab.engineTab.destroy()
            }
        }
        browserTabs.clear()
        if (!keepBrowserSessions && ::backgroundAudioCoordinator.isInitialized) {
            backgroundAudioCoordinator.shutdown()
        }
        if (!keepBrowserSessions) {
            browserEngine.shutdown()
        }
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
        homeFeedMenuButton.setOnClickListener {
            showSettingsPage()
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

                R.id.action_updates -> {
                    showUpdateManager()
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
        val selectedId = tabManager.selectedTabId() ?: restored.first().id
        restored.forEach { session ->
            createBrowserTab(session, loadInitialUrl = session.id == selectedId)
        }
        syncTabLayout()
        selectTab(selectedId, persistSelection = false)
    }

    private fun handleIncomingIntent(intent: Intent?) {
        if (intent?.getBooleanExtra(EXTRA_SHOW_UPDATE_MANAGER, false) == true) {
            showUpdateManager()
        }
        val data = intent?.data ?: return
        if (intent.action != Intent.ACTION_VIEW) return
        if (!LinkInterceptor.isYouTubeUri(data)) return
        val url = data.toString()
        val current = currentTab()
        if (current == null) {
            createAndSelectTab(url)
            return
        }
        createAndSelectTab(url)
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

    private fun createBrowserTab(session: TabSession, loadInitialUrl: Boolean = true): AppTab {
        val engineTab = browserEngine.createTab(
            tabId = session.id,
            initialUrl = session.url,
            title = session.title,
            loadInitialUrl = loadInitialUrl,
            callbacks = createEngineCallbacks()
        )

        val browserTab = AppTab(
            id = session.id,
            engineTab = engineTab,
            isDesktopMode = shouldUseDesktopMode(session.url),
            title = session.title,
            url = session.url,
            hasLoadedInitialUrl = loadInitialUrl,
            lastActivatedAtMs = SystemClock.uptimeMillis()
        )
        browserTab.engineTab.setDesktopMode(browserTab.isDesktopMode)
        browserTab.engineTab.setHomeFeedSettings(currentHomeFeedSettings())
        browserTab.engineTab.view.layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        browserTab.engineTab.view.visibility = View.GONE
        if (!loadInitialUrl) {
            browserTab.engineTab.onPause()
        }
        configureLongPressMenu(browserTab)
        browserTabs[session.id] = browserTab
        return browserTab
    }

    private fun createEngineCallbacks(): EngineCallbacks {
        return EngineCallbacks(
            onOpenExternalUrl = ::openExternalUrl,
            onMainNavigationStarted = { tabId, _ ->
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
            },
            onPlaybackStateChanged = { tabId, state ->
                backgroundAudioCoordinator.onForegroundPlaybackState(tabId, state)
            },
            onMediaControlsChanged = { tabId, controls ->
                backgroundAudioCoordinator.onMediaControlsChanged(tabId, controls)
            },
            onEngineProcessGone = { tabId, reason ->
                recoverEngineTabAfterProcessExit(tabId, reason)
            }
        )
    }

    private fun prepareHiddenUiMemoryTrim(reason: String) {
        currentTab()?.takeIf { !it.isHibernated }?.let(::captureAndStoreTabPreview)
        browserTabs.values
            .filter { it.id != selectedTabId && !it.isHibernated }
            .forEach { tab ->
                runCatching {
                    tab.engineTab.onPause()
                }.onFailure { error ->
                    Log.w("TUBENEXT_MEMORY", "pause background tab failed tab=${tab.id} reason=$reason", error)
                }
            }
    }

    private fun hibernateExpendableBackgroundTabs(reason: String) {
        val liveTabs = browserTabs.values.filterNot { it.isHibernated }
        if (liveTabs.size <= TARGET_LIVE_GECKO_TABS_AFTER_TRIM) return

        var liveCount = liveTabs.size
        var hibernatedCount = 0
        liveTabs
            .filter(::canHibernateForMemoryPressure)
            .sortedBy { it.lastActivatedAtMs }
            .forEach { tab ->
                if (liveCount <= TARGET_LIVE_GECKO_TABS_AFTER_TRIM) return@forEach
                if (hibernateTabForMemoryPressure(tab, reason)) {
                    liveCount -= 1
                    hibernatedCount += 1
                }
            }

        if (hibernatedCount > 0) {
            Log.w(
                "TUBENEXT_MEMORY",
                "hibernated Gecko tabs count=$hibernatedCount reason=$reason liveRemaining=$liveCount"
            )
        }
    }

    private fun canHibernateForMemoryPressure(tab: AppTab): Boolean {
        if (tab.id == selectedTabId) return false
        if (tab.isHibernated) return false
        if (tab.engineTab.isInCustomView()) return false
        if (::backgroundAudioCoordinator.isInitialized &&
            backgroundAudioCoordinator.isPlaybackActiveForTab(tab.id)
        ) {
            return false
        }
        if (isContextPreservingYouTubePage(tab.url)) return false
        return isWatchYouTubeUrl(tab.url)
    }

    private fun isContextPreservingYouTubePage(url: String): Boolean {
        return YouTubeNavigationPolicy.isSupportedYouTubeUrl(url) && !isWatchYouTubeUrl(url)
    }

    private fun hibernateTabForMemoryPressure(tab: AppTab, reason: String): Boolean {
        val oldEngineTab = tab.engineTab
        if (oldEngineTab is HibernatedEngineTab) return false

        Log.w("TUBENEXT_MEMORY", "hibernate Gecko tab tab=${tab.id} reason=$reason url=${tab.url}")
        if (::backgroundAudioCoordinator.isInitialized) {
            backgroundAudioCoordinator.onTabSuspended(tab.id)
        }
        (oldEngineTab.view.parent as? ViewGroup)?.removeView(oldEngineTab.view)
        runCatching {
            oldEngineTab.stopLoading()
        }.onFailure { error ->
            Log.w("TUBENEXT_MEMORY", "stop hibernating tab failed tab=${tab.id}", error)
        }
        runCatching {
            oldEngineTab.destroy()
        }.onFailure { error ->
            Log.w("TUBENEXT_MEMORY", "destroy hibernating tab failed tab=${tab.id}", error)
        }

        tab.engineTab = HibernatedEngineTab(this, tab.id, tab.title, tab.url).apply {
            view.layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            view.visibility = View.GONE
        }
        tab.isHibernated = true
        tab.hibernatedReason = reason
        tab.hasLoadedInitialUrl = false
        tab.loadingOverlayVisible = false
        tab.loadingProgress = 0
        tab.watchStabilizationGeneration += 1
        return true
    }

    private fun ensureTabAwake(tabId: String, reason: String): AppTab? {
        val tab = browserTabs[tabId] ?: return null
        if (!tab.isHibernated) return tab

        val url = tab.url.ifBlank { DEFAULT_URL }
        val title = tab.title
        Log.i(
            "TUBENEXT_MEMORY",
            "wake hibernated Gecko tab tab=$tabId reason=$reason hibernatedReason=${tab.hibernatedReason} url=$url"
        )
        (tab.engineTab.view.parent as? ViewGroup)?.removeView(tab.engineTab.view)

        val engineTab = browserEngine.createTab(
            tabId = tab.id,
            initialUrl = url,
            title = title,
            loadInitialUrl = true,
            callbacks = createEngineCallbacks()
        )
        tab.engineTab = engineTab
        tab.isDesktopMode = shouldUseDesktopMode(url)
        tab.isHibernated = false
        tab.hibernatedReason = ""
        tab.hasLoadedInitialUrl = true
        tab.loadingOverlayVisible = true
        tab.loadingProgress = 8
        tab.pageLoadGeneration += 1
        tab.watchStabilizationGeneration += 1
        engineTab.setDesktopMode(tab.isDesktopMode)
        engineTab.setHomeFeedSettings(currentHomeFeedSettings())
        engineTab.view.layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        engineTab.view.visibility = View.GONE
        configureLongPressMenu(tab)
        return tab
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

    private fun selectTab(
        tabId: String,
        persistSelection: Boolean = true,
        capturePreviousPreview: Boolean = true
    ) {
        if (!browserTabs.containsKey(tabId)) return
        val previousTab = currentTab()?.takeIf { it.id != tabId }
        if (capturePreviousPreview) {
            previousTab?.let(::captureAndStoreTabPreview)
        }
        previousTab?.engineTab?.onPause()
        ensureTabAwake(tabId, "tab-selected") ?: return
        selectedTabId = tabId
        attachOnlySelectedTabView(tabId)
        currentTab()?.let { tab ->
            tab.lastActivatedAtMs = SystemClock.uptimeMillis()
            tab.engineTab.onResume()
            if (!tab.hasLoadedInitialUrl) {
                tab.hasLoadedInitialUrl = true
                tab.engineTab.loadUrl(tab.url.ifBlank { DEFAULT_URL })
            }
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
        scheduleGeckoRenderHealthCheck("tab-selected")
    }

    private fun attachOnlySelectedTabView(tabId: String) {
        browserTabs.forEach { (id, tab) ->
            val view = tab.engineTab.view
            if (id == tabId) {
                val parent = view.parent as? ViewGroup
                if (parent != webViewContainer) {
                    parent?.removeView(view)
                    webViewContainer.addView(view)
                }
                view.visibility = View.VISIBLE
                view.requestLayout()
            } else {
                if (view.parent == webViewContainer) {
                    webViewContainer.removeView(view)
                }
                view.visibility = View.GONE
            }
        }
    }

    private fun recoverEngineTabAfterProcessExit(tabId: String, reason: String) {
        webViewContainer.post {
            val oldTab = browserTabs[tabId] ?: return@post
            val wasSelected = selectedTabId == tabId
            val url = oldTab.url.ifBlank { DEFAULT_URL }
            val title = oldTab.title
            val history = oldTab.navigationHistory.toList()
            val historyIndex = oldTab.historyIndex
            Log.w("TUBENEXT_RENDER", "recreate Gecko tab tab=$tabId reason=$reason selected=$wasSelected url=$url")

            geckoRenderHealthGeneration += 1
            browserTabs.remove(tabId)
            (oldTab.engineTab.view.parent as? ViewGroup)?.removeView(oldTab.engineTab.view)
            runCatching {
                oldTab.engineTab.stopLoading()
            }.onFailure { error ->
                Log.w("TUBENEXT_RENDER", "stop crashed Gecko tab failed tab=$tabId", error)
            }
            runCatching {
                oldTab.engineTab.destroy()
            }.onFailure { error ->
                Log.w("TUBENEXT_RENDER", "destroy crashed Gecko tab failed tab=$tabId", error)
            }

            val replacement = createBrowserTab(
                TabSession(id = tabId, url = url, title = title),
                loadInitialUrl = wasSelected
            )
            replacement.navigationHistory.clear()
            replacement.navigationHistory.addAll(history)
            replacement.historyIndex = historyIndex
            replacement.title = title
            replacement.url = url
            tabManager.update(tabId, url, title)

            if (wasSelected) {
                selectedTabId = tabId
                attachOnlySelectedTabView(tabId)
                replacement.engineTab.onResume()
                updateToolbarState()
                refreshLoadingOverlayForSelectedTab()
                syncTabLayout()
                scheduleGeckoRenderHealthCheck("engine-$reason-recovered", delayMs = 1_500L)
            } else {
                syncTabLayout()
            }
        }
    }

    private fun scheduleGeckoRenderHealthCheck(
        reason: String,
        delayMs: Long = GECKO_RENDER_HEALTH_CHECK_DELAY_MS
    ) {
        if (browserEngine.type != EngineType.GECKO) return
        val tabId = selectedTabId ?: return
        val generation = ++geckoRenderHealthGeneration
        webViewContainer.postDelayed({
            checkGeckoRenderHealth(tabId, generation, reason, attempt = 1)
        }, delayMs)
    }

    private fun scheduleGeckoRenderHealthRetry(
        tabId: String,
        generation: Long,
        reason: String,
        attempt: Int
    ) {
        webViewContainer.postDelayed({
            checkGeckoRenderHealth(tabId, generation, reason, attempt)
        }, GECKO_RENDER_HEALTH_RECHECK_DELAY_MS)
    }

    private fun checkGeckoRenderHealth(
        tabId: String,
        generation: Long,
        reason: String,
        attempt: Int
    ) {
        val tab = healthCheckedGeckoTab(tabId, generation) ?: return
        val geckoView = tab.engineTab.view as? GeckoView ?: return
        if (tab.loadingOverlayVisible) {
            if (attempt < GECKO_RENDER_HEALTH_MAX_ATTEMPTS) {
                scheduleGeckoRenderHealthRetry(tabId, generation, reason, attempt + 1)
            }
            return
        }
        if (geckoView.width <= 0 || geckoView.height <= 0 || geckoView.parent != webViewContainer) {
            handleGeckoRenderHealthFailure(
                tabId = tabId,
                generation = generation,
                reason = "$reason:no-visible-frame",
                attempt = attempt,
                error = null
            )
            return
        }

        runCatching {
            geckoView.capturePixels().accept(
                { captured ->
                    runOnUiThread {
                        handleGeckoRenderHealthCapture(tabId, generation, reason, attempt, captured)
                    }
                },
                { error ->
                    runOnUiThread {
                        handleGeckoRenderHealthFailure(tabId, generation, reason, attempt, error)
                    }
                }
            )
        }.onFailure { error ->
            handleGeckoRenderHealthFailure(tabId, generation, reason, attempt, error)
        }
    }

    private fun handleGeckoRenderHealthCapture(
        tabId: String,
        generation: Long,
        reason: String,
        attempt: Int,
        captured: Bitmap?
    ) {
        healthCheckedGeckoTab(tabId, generation) ?: return
        if (captured == null) {
            handleGeckoRenderHealthFailure(tabId, generation, "$reason:null-capture", attempt, null)
            return
        }
        val isBlank = runCatching {
            isEffectivelyBlankPreview(captured)
        }.getOrDefault(false)
        captured.recycle()
        if (!isBlank) return

        if (attempt < GECKO_RENDER_HEALTH_MAX_ATTEMPTS) {
            Log.w("TUBENEXT_RENDER", "blank Gecko capture tab=$tabId attempt=$attempt reason=$reason")
            scheduleGeckoRenderHealthRetry(tabId, generation, "$reason:blank", attempt + 1)
            return
        }
        recoverVisibleGeckoSurface(tabId, "$reason:blank-capture")
    }

    private fun handleGeckoRenderHealthFailure(
        tabId: String,
        generation: Long,
        reason: String,
        attempt: Int,
        error: Throwable?
    ) {
        healthCheckedGeckoTab(tabId, generation) ?: return
        if (attempt < GECKO_RENDER_HEALTH_MAX_ATTEMPTS) {
            Log.w("TUBENEXT_RENDER", "Gecko capture failed tab=$tabId attempt=$attempt reason=$reason", error)
            scheduleGeckoRenderHealthRetry(tabId, generation, "$reason:capture-failed", attempt + 1)
            return
        }
        logRecentProcessExitInfo("render-health-failure:$reason")
        recoverVisibleGeckoSurface(tabId, "$reason:capture-failed")
    }

    private fun logRecentProcessExitInfo(reason: String) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        val activityManager = getSystemService<ActivityManager>() ?: return
        val exits = runCatching {
            activityManager.getHistoricalProcessExitReasons(packageName, 0, RECENT_EXIT_INFO_LIMIT)
        }.getOrElse { error ->
            Log.w("TUBENEXT_EXIT", "read process exit info failed reason=$reason", error)
            return
        }
        exits.forEach { exit ->
            val processName = exit.processName.orEmpty()
            if (!processName.startsWith(packageName)) return@forEach
            val key = "${exit.timestamp}:${exit.pid}:$processName:${exit.reason}:${exit.status}"
            if (!loggedProcessExitKeys.add(key)) return@forEach
            Log.w(
                "TUBENEXT_EXIT",
                "recent process exit trigger=$reason process=$processName pid=${exit.pid} " +
                    "reason=${exit.reason} status=${exit.status} " +
                    "importance=${exit.importance} pss=${exit.pss} rss=${exit.rss} " +
                    "description=${exit.description.orEmpty()}"
            )
        }
    }

    private fun recoverVisibleGeckoSurface(tabId: String, reason: String) {
        val tab = healthCheckedGeckoTab(tabId, geckoRenderHealthGeneration) ?: return
        val view = tab.engineTab.view as? GeckoView ?: return
        val now = SystemClock.uptimeMillis()
        if (now - lastGeckoSurfaceRecoveryAtMs < GECKO_SURFACE_RECOVERY_THROTTLE_MS) {
            Log.w("TUBENEXT_RENDER", "skip throttled Gecko surface recovery tab=$tabId reason=$reason")
            return
        }
        lastGeckoSurfaceRecoveryAtMs = now
        Log.w("TUBENEXT_RENDER", "recover Gecko surface tab=$tabId reason=$reason url=${tab.url}")

        (view.parent as? ViewGroup)?.removeView(view)
        view.visibility = View.VISIBLE
        webViewContainer.post {
            val currentTab = healthCheckedGeckoTab(tabId, geckoRenderHealthGeneration) ?: return@post
            val currentView = currentTab.engineTab.view
            if (currentView.parent != webViewContainer) {
                (currentView.parent as? ViewGroup)?.removeView(currentView)
                webViewContainer.addView(currentView)
            }
            currentView.visibility = View.VISIBLE
            currentView.requestLayout()
            currentTab.engineTab.onResume()
            scheduleGeckoRenderHealthCheck("post-recovery")
        }
    }

    private fun healthCheckedGeckoTab(tabId: String, generation: Long): AppTab? {
        if (generation != geckoRenderHealthGeneration) return null
        if (selectedTabId != tabId) return null
        if (isFinishing || isDestroyed) return null
        return browserTabs[tabId]?.takeIf { tab ->
            tab.engineTab.view is GeckoView &&
                tab.engineTab.view.visibility == View.VISIBLE &&
                tab.hasLoadedInitialUrl
        }
    }

    private fun closeCurrentTab() {
        val tabId = selectedTabId ?: return
        closeTabById(tabId)
    }

    private fun duplicateCurrentTab() {
        val currentId = selectedTabId ?: return
        duplicateTabById(currentId)
    }

    private fun showSettingsPage() {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(8), dp(24), dp(4))
        }
        container.addView(settingsSectionTitle(R.string.settings_section_home_feed))
        container.addView(settingsCheckBox(KEY_SHOW_SHORTS, R.string.home_feed_show_shorts, true))
        container.addView(
            settingsCheckBox(
                KEY_SHOW_COMMUNITY_POSTS,
                R.string.home_feed_show_community,
                true
            )
        )
        container.addView(
            settingsCheckBox(
                KEY_SHOW_WATCH_HISTORY,
                R.string.home_feed_show_history,
                true
            )
        )
        container.addView(settingsDivider())
        container.addView(settingsSectionTitle(R.string.settings_section_watch_page))
        container.addView(
            settingsCheckBox(
                KEY_HIDE_WATCH_BRANDING,
                R.string.watch_page_hide_branding,
                false
            )
        )
        container.addView(settingsDivider())
        container.addView(settingsSectionTitle(R.string.settings_section_updates))
        container.addView(updateStatusTextView())
        container.addView(updateButton(R.string.settings_open_update_manager).apply {
            setOnClickListener {
                showUpdateManager()
            }
        })
        container.addView(updateButton(R.string.update_open_install_settings).apply {
            setOnClickListener {
                openInstallSettings()
            }
        })
        container.addView(settingsDivider())
        container.addView(settingsSectionTitle(R.string.settings_section_background_playback))
        container.addView(batteryOptimizationStatusTextView())
        container.addView(batteryOptimizationHintCheckBox())
        container.addView(updateButton(R.string.battery_optimization_open_settings).apply {
            setOnClickListener {
                showBatteryOptimizationSettingsGuide()
            }
        })

        val scrollView = ScrollView(this).apply {
            addView(container)
        }
        settingsDialog?.dismiss()
        settingsDialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.settings_title)
            .setView(scrollView)
            .setPositiveButton(android.R.string.ok, null)
            .create()
        settingsDialog?.setOnDismissListener {
            settingsDialog = null
        }
        settingsDialog?.show()
    }

    private fun settingsCheckBox(
        preferenceKey: String,
        labelResId: Int,
        defaultValue: Boolean
    ): CheckBox {
        return CheckBox(this).apply {
            text = getString(labelResId)
            isChecked = preferences.getBoolean(preferenceKey, defaultValue)
            setOnCheckedChangeListener { _, checked ->
                setHomeFeedPreference(preferenceKey, checked)
            }
        }
    }

    private fun updateStatusTextView(): TextView {
        val permissionStatus = if (UpdateInstallHelper.canRequestPackageInstalls(this)) {
            getString(R.string.settings_install_permission_active)
        } else {
            getString(R.string.settings_install_permission_inactive)
        }
        return updateTextView().apply {
            text = permissionStatus
        }
    }

    private fun batteryOptimizationStatusTextView(): TextView {
        val status = if (isIgnoringBatteryOptimizations()) {
            getString(R.string.battery_optimization_status_unrestricted)
        } else {
            getString(R.string.battery_optimization_status_optimized)
        }
        return updateTextView().apply {
            text = status
        }
    }

    private fun batteryOptimizationHintCheckBox(): CheckBox {
        return CheckBox(this).apply {
            text = getString(R.string.battery_optimization_hint_enabled)
            isChecked = !preferences.getBoolean(KEY_BATTERY_OPTIMIZATION_HINT_DISABLED, false)
            setOnCheckedChangeListener { _, checked ->
                preferences.edit()
                    .putBoolean(KEY_BATTERY_OPTIMIZATION_HINT_DISABLED, !checked)
                    .remove(KEY_BATTERY_OPTIMIZATION_HINT_VERSION_CODE)
                    .apply()
            }
        }
    }

    private fun settingsSectionTitle(labelResId: Int): TextView {
        return TextView(this).apply {
            text = getString(labelResId)
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, dp(12), 0, dp(4))
        }
    }

    private fun settingsDivider(): View {
        return View(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(1)
            ).apply {
                topMargin = dp(12)
                bottomMargin = dp(4)
            }
            setBackgroundColor(Color.argb(80, 255, 255, 255))
        }
    }

    private fun currentHomeFeedSettings(): EngineHomeFeedSettings {
        return EngineHomeFeedSettings(
            showShorts = preferences.getBoolean(KEY_SHOW_SHORTS, true),
            showCommunityPosts = preferences.getBoolean(KEY_SHOW_COMMUNITY_POSTS, true),
            showWatchHistory = preferences.getBoolean(KEY_SHOW_WATCH_HISTORY, true),
            hideWatchBranding = preferences.getBoolean(KEY_HIDE_WATCH_BRANDING, false)
        )
    }

    private fun setHomeFeedPreference(key: String, enabled: Boolean) {
        preferences.edit().putBoolean(key, enabled).apply()
        applyHomeFeedSettingsToTabs()
    }

    private fun applyHomeFeedSettingsToTabs() {
        val settings = currentHomeFeedSettings()
        browserTabs.values.forEach { tab ->
            tab.engineTab.setHomeFeedSettings(settings)
        }
    }

    private fun loadInCurrentTab(url: String) {
        val tab = currentTab()?.let { ensureTabAwake(it.id, "load-current-tab") }
            ?: createAndSelectTab(url)
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
        val activeTab = ensureTabAwake(tab.id, "history-back") ?: return false
        if (activeTab.historyIndex <= 0) return false
        val currentCanonicalUrl = canonicalHistoryUrl(activeTab.url)
        do {
            activeTab.historyIndex -= 1
        } while (
            activeTab.historyIndex > 0 &&
            activeTab.navigationHistory.getOrNull(activeTab.historyIndex) == currentCanonicalUrl
        )
        activeTab.pendingHistoryNavigation = true
        activeTab.engineTab.loadUrl(activeTab.navigationHistory[activeTab.historyIndex])
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
        backgroundAudioCoordinator.onTabClosing(tabId)
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
        val recyclerView = content.findViewById<RecyclerView>(R.id.tabOverviewList)
        val title = content.findViewById<TextView>(R.id.tabOverviewTitle)
        val newTabButton = content.findViewById<ImageButton>(R.id.tabOverviewNewTab)
        val duplicateCurrentButton = content.findViewById<ImageButton>(R.id.tabOverviewDuplicateCurrent)
        val closeOthersButton = content.findViewById<ImageButton>(R.id.tabOverviewCloseOthers)
        val closeButton = content.findViewById<ImageButton>(R.id.tabOverviewClose)

        lateinit var adapter: TabOverviewAdapter
        adapter = TabOverviewAdapter(
            onTabClick = { tabId ->
                selectTab(tabId, capturePreviousPreview = false)
                dialog.dismiss()
            },
            onTabClose = { tabId ->
                closeTabById(tabId)
                refreshTabOverview(adapter, title)
            }
        )
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = adapter
        attachTabOverviewDrag(recyclerView, adapter)

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
        currentTab()?.let(::captureAndStoreTabPreview)
        refreshTabOverview(adapter, title)
        dialog.show()
        dialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
            ?.setBackgroundColor(Color.TRANSPARENT)
    }

    private fun attachTabOverviewDrag(
        recyclerView: RecyclerView,
        adapter: TabOverviewAdapter
    ) {
        var orderChanged = false
        val touchHelper = ItemTouchHelper(
            object : ItemTouchHelper.SimpleCallback(ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0) {
                override fun isLongPressDragEnabled(): Boolean = true

                override fun isItemViewSwipeEnabled(): Boolean = false

                override fun onMove(
                    recyclerView: RecyclerView,
                    viewHolder: RecyclerView.ViewHolder,
                    target: RecyclerView.ViewHolder
                ): Boolean {
                    val from = viewHolder.bindingAdapterPosition
                    val to = target.bindingAdapterPosition
                    if (from == RecyclerView.NO_POSITION || to == RecyclerView.NO_POSITION) {
                        return false
                    }
                    val moved = adapter.moveItem(from, to) && tabManager.move(from, to)
                    orderChanged = orderChanged || moved
                    return moved
                }

                override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) = Unit

                override fun clearView(
                    recyclerView: RecyclerView,
                    viewHolder: RecyclerView.ViewHolder
                ) {
                    super.clearView(recyclerView, viewHolder)
                    if (!orderChanged) return
                    orderChanged = false
                    syncTabLayout()
                }
            }
        )
        touchHelper.attachToRecyclerView(recyclerView)
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
    }

    private fun captureAndStoreTabPreview(tab: AppTab, onPreview: ((Bitmap) -> Unit)? = null) {
        runCatching {
            val contentView = tab.engineTab.view
            if (contentView.width <= 0 || contentView.height <= 0) return
            if (contentView is GeckoView) {
                if (contentView.visibility != View.VISIBLE) return
                contentView.capturePixels().accept(
                    { captured ->
                        if (captured == null) return@accept
                        val preview = createTabPreviewBitmap(captured)
                        if (isEffectivelyBlankPreview(preview)) return@accept
                        tabPreviewStore.save(tab.id, preview)
                        runOnUiThread {
                            onPreview?.invoke(preview)
                        }
                    },
                    { error ->
                        Log.w("TUBENEXT_STATE", "tab preview capture failed tab=${tab.id}", error)
                    }
                )
                return
            }
            captureTabPreviewFallback(tab)?.let { preview ->
                if (isEffectivelyBlankPreview(preview)) return
                onPreview?.invoke(preview)
            }
        }.onFailure { error ->
            Log.w("TUBENEXT_STATE", "tab preview capture skipped tab=${tab.id}", error)
        }
    }

    private fun prepareBackgroundAudioArtwork() {
        val tab = currentTab() ?: return
        tabPreviewStore.load(tab.id)?.let { savedPreview ->
            backgroundAudioCoordinator.setArtwork(savedPreview)
        }
        captureAndStoreTabPreview(tab) { bitmap ->
            backgroundAudioCoordinator.setArtwork(bitmap)
        }
    }

    private fun captureTabPreviewFallback(tab: AppTab): Bitmap? {
        return runCatching {
            val contentView = tab.engineTab.view
            val width = contentView.width.takeIf { it > 0 } ?: return null
            val height = contentView.height.takeIf { it > 0 } ?: return null
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            contentView.draw(canvas)
            val preview = createTabPreviewBitmap(bitmap)
            if (isEffectivelyBlankPreview(preview)) return null
            tabPreviewStore.save(tab.id, preview)
            preview
        }.onFailure { error ->
            Log.w("TUBENEXT_STATE", "fallback preview capture failed tab=${tab.id}", error)
        }.getOrNull()
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

    private fun isEffectivelyBlankPreview(bitmap: Bitmap): Boolean {
        val stepX = (bitmap.width / 16).coerceAtLeast(1)
        val stepY = (bitmap.height / 10).coerceAtLeast(1)
        var samples = 0
        var darkSamples = 0
        var lightSamples = 0
        var transparentSamples = 0
        var minLuminance = 255
        var maxLuminance = 0

        var y = 0
        while (y < bitmap.height) {
            var x = 0
            while (x < bitmap.width) {
                val pixel = bitmap.getPixel(x, y)
                val alpha = Color.alpha(pixel)
                val luminance = (
                    Color.red(pixel) * 0.299f +
                        Color.green(pixel) * 0.587f +
                        Color.blue(pixel) * 0.114f
                    ).toInt()
                samples += 1
                if (alpha < 8) transparentSamples += 1
                if (alpha >= 8) {
                    if (luminance <= 6) darkSamples += 1
                    if (luminance >= 248) lightSamples += 1
                    minLuminance = minOf(minLuminance, luminance)
                    maxLuminance = maxOf(maxLuminance, luminance)
                }
                x += stepX
            }
            y += stepY
        }

        if (samples == 0) return true
        val darkOrTransparentSamples = darkSamples + transparentSamples
        val lightOrTransparentSamples = lightSamples + transparentSamples
        val nearlySingleTone = transparentSamples == 0 && maxLuminance - minLuminance <= 4
        return darkOrTransparentSamples >= samples * 98 / 100 ||
            lightOrTransparentSamples >= samples * 98 / 100 ||
            nearlySingleTone
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
                captureAndStoreTabPreview(currentTab) { bitmap ->
                    if (selectedTabId == currentTab.id && ::backgroundAudioCoordinator.isInitialized) {
                        backgroundAudioCoordinator.setArtwork(bitmap)
                    }
                }
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
                    if (BuildConfig.DEBUG) {
                        Log.i("TUBENEXT_WATCH_FIX", "post-fix moviePlayerRect=$rectJson")
                    }
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

    private fun showUpdateManager(initialResult: UpdateCheckResult? = latestUpdateResult) {
        var activeResult = initialResult
        var activeRelease = activeResult?.release ?: latestUpdateResult?.release
        var isDownloading = false

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(8), dp(24), dp(4))
        }
        val statusText = updateTextView()
        val currentText = updateTextView()
        val latestText = updateTextView()
        val assetText = updateTextView()
        val downloadStatusText = updateTextView()
        val notificationsCheck = CheckBox(this).apply {
            text = getString(R.string.update_notifications_enabled)
            isChecked = updatePreferences.notificationsEnabled
            setOnCheckedChangeListener { _, checked ->
                updatePreferences.notificationsEnabled = checked
            }
        }
        val postInstallReminderCheck = CheckBox(this).apply {
            text = getString(R.string.update_post_install_reminder_enabled)
            isChecked = !updatePreferences.postInstallReminderPermanentlyHidden
            setOnCheckedChangeListener { _, checked ->
                updatePreferences.postInstallReminderPermanentlyHidden = !checked
                if (checked) {
                    updatePreferences.postInstallReminderDismissedVersionName = null
                }
            }
        }
        val checkButton = updateButton(R.string.menu_reload)
        val releaseNotesButton = updateButton(R.string.update_open_release_notes)
        val downloadButton = updateButton(R.string.update_download)
        val cancelDownloadButton = updateButton(R.string.update_cancel_download)
        val installButton = updateButton(R.string.update_install)
        val deleteButton = updateButton(R.string.update_delete_file)
        val settingsButton = updateButton(R.string.update_open_install_settings)

        listOf(
            statusText,
            currentText,
            latestText,
            assetText,
            downloadStatusText,
            notificationsCheck,
            postInstallReminderCheck,
            checkButton,
            releaseNotesButton,
            downloadButton,
            cancelDownloadButton,
            installButton,
            deleteButton,
            settingsButton
        ).forEach(container::addView)

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.update_manager_title)
            .setView(container)
            .setPositiveButton(android.R.string.ok, null)
            .create()

        fun downloadedFile(): File? = activeRelease?.let(updatePreferences::downloadedApkFor)

        fun render() {
            val release = activeRelease
            currentText.text = getString(R.string.update_current_version, BuildConfig.VERSION_NAME)
            latestText.visibility = if (release == null) View.GONE else View.VISIBLE
            latestText.text = release?.let {
                getString(R.string.update_latest_version, it.versionName)
            }.orEmpty()
            assetText.visibility = if (release?.compatibleAsset == null) View.GONE else View.VISIBLE
            assetText.text = release?.compatibleAsset?.let { asset ->
                "${asset.name} (${formatBytes(asset.sizeBytes)})"
            }.orEmpty()

            statusText.text = when (activeResult?.status) {
                UpdateCheckStatus.UPDATE_AVAILABLE -> getString(R.string.update_available)
                UpdateCheckStatus.UP_TO_DATE -> getString(R.string.update_up_to_date)
                UpdateCheckStatus.NO_COMPATIBLE_ASSET -> getString(R.string.update_no_compatible_asset)
                UpdateCheckStatus.CHECK_FAILED -> getString(
                    R.string.update_check_failed,
                    activeResult?.message.orEmpty()
                )
                null -> getString(R.string.update_checking)
            }

            val file = downloadedFile()
            downloadStatusText.visibility = if (isDownloading || file != null) View.VISIBLE else View.GONE
            if (file != null && !isDownloading) {
                downloadStatusText.text = getString(
                    R.string.update_downloaded_status,
                    formatBytes(file.length())
                )
            }

            val updateAvailable = activeResult?.status == UpdateCheckStatus.UPDATE_AVAILABLE
            releaseNotesButton.visibility = if (release?.htmlUrl.isNullOrBlank()) View.GONE else View.VISIBLE
            downloadButton.visibility = if (updateAvailable && file == null && !isDownloading) {
                View.VISIBLE
            } else {
                View.GONE
            }
            installButton.visibility = if (file != null && !isDownloading) View.VISIBLE else View.GONE
            deleteButton.visibility = if (file != null && !isDownloading) View.VISIBLE else View.GONE
            cancelDownloadButton.visibility = if (isDownloading) View.VISIBLE else View.GONE
            checkButton.isEnabled = !updateCheckInProgress && !isDownloading
            settingsButton.visibility = View.VISIBLE
        }

        fun runDialogCheck() {
            activeResult = null
            activeRelease = null
            render()
            requestUpdateCheck(force = true) { result ->
                activeResult = result
                activeRelease = result.release
                render()
            }
        }

        fun startDownload(release: UpdateRelease, asset: UpdateAsset) {
            isDownloading = true
            downloadStatusText.visibility = View.VISIBLE
            downloadStatusText.text = getString(R.string.update_downloading_status, 0)
            render()
            updateDownloadHandle = UpdateDownloader(this, updatePreferences).download(
                release = release,
                asset = asset,
                listener = object : UpdateDownloader.Listener {
                    override fun onProgress(downloadedBytes: Long, totalBytes: Long) {
                        val percent = if (totalBytes > 0L) {
                            ((downloadedBytes * 100L) / totalBytes).coerceIn(0L, 100L).toInt()
                        } else {
                            0
                        }
                        downloadStatusText.text = getString(R.string.update_downloading_status, percent)
                    }

                    override fun onCompleted(file: File) {
                        isDownloading = false
                        updateDownloadHandle = null
                        downloadStatusText.text = getString(R.string.update_download_completed)
                        Snackbar.make(webViewContainer, R.string.update_download_completed, Snackbar.LENGTH_SHORT)
                            .show()
                        render()
                    }

                    override fun onCancelled() {
                        isDownloading = false
                        updateDownloadHandle = null
                        downloadStatusText.text = getString(R.string.update_download_cancelled)
                        render()
                    }

                    override fun onError(message: String) {
                        isDownloading = false
                        updateDownloadHandle = null
                        downloadStatusText.text = getString(R.string.update_download_failed, message)
                        render()
                    }
                }
            )
        }

        checkButton.setOnClickListener {
            runDialogCheck()
        }
        releaseNotesButton.setOnClickListener {
            activeRelease?.htmlUrl?.takeIf { it.isNotBlank() }?.let { url ->
                settingsDialog?.dismiss()
                openExternalUrl(Uri.parse(url))
                dialog.dismiss()
            }
        }
        downloadButton.setOnClickListener {
            val release = activeRelease ?: return@setOnClickListener
            val asset = release.compatibleAsset ?: return@setOnClickListener
            if (UpdateInstallHelper.canRequestPackageInstalls(this)) {
                startDownload(release, asset)
            } else {
                showInstallPermissionBeforeDownloadDialog {
                    startDownload(release, asset)
                }
            }
        }
        installButton.setOnClickListener {
            val release = activeRelease ?: return@setOnClickListener
            val file = downloadedFile() ?: return@setOnClickListener
            installDownloadedUpdate(release, file)
        }
        cancelDownloadButton.setOnClickListener {
            updateDownloadHandle?.cancel()
        }
        deleteButton.setOnClickListener {
            activeRelease?.let { release ->
                UpdateDownloader(this, updatePreferences).deleteDownloadedApk(release)
                render()
            }
        }
        settingsButton.setOnClickListener {
            openInstallSettings()
        }
        dialog.setOnShowListener {
            render()
            if (activeResult == null && !updateCheckInProgress) {
                runDialogCheck()
            }
        }
        dialog.setOnDismissListener {
            updateDownloadHandle?.cancel()
            updateDownloadHandle = null
        }
        dialog.show()
    }

    private fun checkForUpdates(showDialog: Boolean, force: Boolean) {
        if (!force && !isUpdateCheckDue()) return
        requestUpdateCheck(force = force) { result ->
            if (showDialog) {
                showUpdateManager(result)
            }
        }
    }

    private fun requestUpdateCheck(
        force: Boolean,
        callback: ((UpdateCheckResult) -> Unit)? = null
    ) {
        if (updateCheckInProgress) return
        if (!force && !isUpdateCheckDue()) return
        updateCheckInProgress = true
        Thread {
            val result = GitHubReleaseClient().checkLatestRelease()
            runOnUiThread {
                updateCheckInProgress = false
                updatePreferences.lastCheckAtMillis = System.currentTimeMillis()
                latestUpdateResult = result
                if (result.status == UpdateCheckStatus.UPDATE_AVAILABLE) {
                    result.release?.let { release ->
                        handleAvailableUpdate(release)
                    }
                }
                callback?.invoke(result)
            }
        }.start()
    }

    private fun handleAvailableUpdate(release: UpdateRelease) {
        if (!updatePreferences.notificationsEnabled) return
        if (updatePreferences.ignoredReleaseTag == release.tagName) return
        if (UpdateNotifier.canPostNotifications(this)) {
            UpdateNotifier.showUpdateAvailable(this, release)
            return
        }
        showUpdateNotificationPermissionDialog(release)
    }

    private fun showUpdateNotificationPermissionDialog(release: UpdateRelease) {
        if (updatePermissionDialogVisible) return
        updatePermissionDialogVisible = true
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.update_notification_permission_title)
            .setMessage(
                getString(
                    R.string.update_notification_permission_message,
                    release.versionName
                )
            )
            .setPositiveButton(R.string.update_notification_permission_allow) { _, _ ->
                pendingUpdateNotificationRelease = release
                requestUpdateNotificationPermission()
            }
            .setNeutralButton(R.string.update_action_open_manager) { _, _ ->
                showUpdateManager(UpdateCheckResult(UpdateCheckStatus.UPDATE_AVAILABLE, release))
            }
            .setNegativeButton(android.R.string.cancel, null)
            .setOnDismissListener {
                updatePermissionDialogVisible = false
            }
            .show()
    }

    private fun requestUpdateNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        ActivityCompat.requestPermissions(
            this,
            arrayOf(Manifest.permission.POST_NOTIFICATIONS),
            UPDATE_NOTIFICATION_PERMISSION_REQUEST_CODE
        )
    }

    private fun showInstallPermissionBeforeDownloadDialog(onDownloadAnyway: () -> Unit) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.update_install_permission_needed_title)
            .setMessage(R.string.update_install_permission_needed_before_download)
            .setPositiveButton(R.string.update_open_install_settings) { _, _ ->
                openInstallSettings()
            }
            .setNeutralButton(R.string.update_download_anyway) { _, _ ->
                onDownloadAnyway()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun installDownloadedUpdate(release: UpdateRelease, apkFile: File) {
        if (!UpdateInstallHelper.canRequestPackageInstalls(this)) {
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.update_install_permission_needed_title)
                .setMessage(R.string.update_install_permission_needed_before_install)
                .setPositiveButton(R.string.update_open_install_settings) { _, _ ->
                    openInstallSettings()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
            return
        }
        try {
            updatePreferences.markInstallAttempt(release)
            startActivity(UpdateInstallHelper.installIntent(this, apkFile))
        } catch (_: ActivityNotFoundException) {
            Snackbar.make(webViewContainer, apkFile.absolutePath, Snackbar.LENGTH_LONG).show()
        }
    }

    private fun maybeShowPostInstallPermissionReminder(): Boolean {
        if (!UpdateInstallHelper.canRequestPackageInstalls(this)) return false
        if (updatePreferences.postInstallReminderPermanentlyHidden) return false
        val attemptedVersion = updatePreferences.lastInstallAttemptVersionName ?: return false
        val currentVersion = VersionNames.normalize(BuildConfig.VERSION_NAME)
        if (VersionNames.compare(currentVersion, attemptedVersion) < 0) return false
        if (updatePreferences.postInstallReminderDismissedVersionName == currentVersion) return false

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.update_post_install_reminder_title)
            .setMessage(R.string.update_post_install_reminder_text)
            .setPositiveButton(R.string.update_open_install_settings) { _, _ ->
                openInstallSettings()
            }
            .setNeutralButton(R.string.update_hide_forever) { _, _ ->
                updatePreferences.postInstallReminderPermanentlyHidden = true
            }
            .setNegativeButton(R.string.update_hide_once) { _, _ ->
                updatePreferences.postInstallReminderDismissedVersionName = currentVersion
            }
            .show()
        return true
    }

    private fun openInstallSettings() {
        try {
            startActivity(UpdateInstallHelper.installSettingsIntent(this))
        } catch (_: ActivityNotFoundException) {
            startActivity(Intent(android.provider.Settings.ACTION_SETTINGS))
        }
    }

    private fun isUpdateCheckDue(): Boolean {
        return System.currentTimeMillis() - updatePreferences.lastCheckAtMillis >=
            UPDATE_CHECK_INTERVAL_MS
    }

    private fun maybeShowBatteryOptimizationHint() {
        if (batteryOptimizationDialogVisible) return
        if (preferences.getBoolean(KEY_BATTERY_OPTIMIZATION_HINT_DISABLED, false)) return
        if (isIgnoringBatteryOptimizations()) return
        batteryOptimizationDialogVisible = true
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.battery_optimization_hint_title)
            .setMessage(R.string.battery_optimization_hint_message)
            .setPositiveButton(R.string.battery_optimization_open_settings) { _, _ ->
                showBatteryOptimizationSettingsGuide()
            }
            .setNegativeButton(R.string.battery_optimization_later, null)
            .setNeutralButton(R.string.battery_optimization_never_show_again) { _, _ ->
                preferences.edit()
                    .putBoolean(KEY_BATTERY_OPTIMIZATION_HINT_DISABLED, true)
                    .apply()
            }
            .setOnDismissListener {
                batteryOptimizationDialogVisible = false
            }
            .show()
    }

    private fun isIgnoringBatteryOptimizations(): Boolean {
        val powerManager = getSystemService<PowerManager>() ?: return false
        return powerManager.isIgnoringBatteryOptimizations(packageName)
    }

    private fun showBatteryOptimizationSettingsGuide() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.battery_optimization_settings_guide_title)
            .setMessage(R.string.battery_optimization_settings_guide_message)
            .setPositiveButton(R.string.battery_optimization_open_settings) { _, _ ->
                openBatteryOptimizationSettings()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun openBatteryOptimizationSettings() {
        try {
            startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.fromParts("package", packageName, null)
                }
            )
        } catch (_: ActivityNotFoundException) {
            try {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            } catch (_: ActivityNotFoundException) {
                startActivity(Intent(Settings.ACTION_SETTINGS))
            }
        }
    }

    private fun updateTextView(): TextView {
        return TextView(this).apply {
            setPadding(0, dp(6), 0, dp(6))
            textSize = 15f
        }
    }

    private fun updateButton(labelResId: Int): Button {
        return Button(this).apply {
            text = getString(labelResId)
            isAllCaps = false
        }
    }

    private fun formatBytes(bytes: Long): String {
        if (bytes <= 0L) return getString(R.string.update_unknown_size)
        val mib = bytes / (1024.0 * 1024.0)
        return String.format("%.1f MB", mib)
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }

    private class HibernatedEngineTab(
        context: Context,
        override val id: String,
        override var title: String,
        override var url: String
    ) : EngineTab {
        override val view: View = FrameLayout(context)

        override fun loadUrl(url: String) {
            this.url = url
        }

        override fun reload() = Unit

        override fun canGoBack(): Boolean = false

        override fun goBack() = Unit

        override fun stopLoading() = Unit

        override fun detach() = Unit

        override fun destroy() = Unit

        override fun setDesktopMode(enabled: Boolean) = Unit

        override fun isInCustomView(): Boolean = false

        override fun exitFullscreenIfNeeded() = Unit

        override fun evaluateJavascript(script: String, callback: ((String?) -> Unit)?) {
            callback?.invoke(null)
        }

        override fun setHomeFeedSettings(settings: EngineHomeFeedSettings) = Unit

        override fun onPause() = Unit

        override fun onResume() = Unit

        override fun recoverFromAudioRouteChange() = Unit
    }

    companion object {
        const val EXTRA_SHOW_UPDATE_MANAGER = "de.shakie.tubenext.SHOW_UPDATE_MANAGER"
        private const val UPDATE_NOTIFICATION_PERMISSION_REQUEST_CODE = 73
        private const val DEFAULT_URL = "https://www.youtube.com/"
        private const val MAX_TAB_LABEL_LENGTH = 24
        private const val WATCH_VIEWPORT_STABILIZE_DELAY_MS = 1000L
        private const val OVERLAY_HIDE_DELAY_MS = 2000L
        private const val GECKO_RENDER_HEALTH_CHECK_DELAY_MS = 900L
        private const val GECKO_RENDER_HEALTH_RECHECK_DELAY_MS = 650L
        private const val GECKO_RENDER_HEALTH_MAX_ATTEMPTS = 2
        private const val GECKO_SURFACE_RECOVERY_THROTTLE_MS = 12_000L
        private const val RECENT_EXIT_INFO_LIMIT = 16
        private const val TARGET_LIVE_GECKO_TABS_AFTER_TRIM = 2
        private const val UPDATE_CHECK_INTERVAL_MS = 24L * 60L * 60L * 1000L
        private const val TAB_PREVIEW_TOP_OFFSET_RATIO = 0.12f
        private const val PREFERENCES_NAME = "tube_next_preferences"
        private const val KEY_SHOW_SHORTS = "home_feed_show_shorts"
        private const val KEY_SHOW_COMMUNITY_POSTS = "home_feed_show_community_posts"
        private const val KEY_SHOW_WATCH_HISTORY = "home_feed_show_watch_history"
        private const val KEY_HIDE_WATCH_BRANDING = "watch_page_hide_branding"
        private const val KEY_BATTERY_OPTIMIZATION_HINT_VERSION_CODE =
            "battery_optimization_hint_version_code"
        private const val KEY_BATTERY_OPTIMIZATION_HINT_DISABLED =
            "battery_optimization_hint_disabled"
        private const val MENU_SHOW_SHORTS = 20_001
        private const val MENU_SHOW_COMMUNITY = 20_002
        private const val MENU_SHOW_HISTORY = 20_003
        private const val MENU_HIDE_WATCH_BRANDING = 20_004
        private const val MENU_SECTION_HOME_FEED = 20_101
        private const val MENU_SECTION_WATCH_PAGE = 20_102
        private val LEADING_COUNT_PREFIX_REGEX = Regex("^\\(\\d+\\)\\s*")
    }

    private fun supportsLegacyWatchTweaks(): Boolean {
        return browserEngine.type == EngineType.WEBVIEW
    }
}
