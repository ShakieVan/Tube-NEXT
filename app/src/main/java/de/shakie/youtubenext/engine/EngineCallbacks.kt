package de.shakie.youtubenext.engine

import android.net.Uri

data class EngineCallbacks(
    val onOpenExternalUrl: (Uri) -> Unit,
    val onMainNavigationStarted: (tabId: String, url: String) -> Unit,
    val onMainUrlUpdated: (tabId: String, url: String) -> Unit,
    val onMainTitleUpdated: (tabId: String, title: String) -> Unit,
    val onMainPageFinished: (tabId: String, url: String) -> Unit,
    val onProgressChanged: (tabId: String, progress: Int) -> Unit,
    val onNewTabRequest: (url: String) -> Unit,
    val onFullscreenChanged: (tabId: String, isFullscreen: Boolean) -> Unit,
    val onLoadError: (tabId: String) -> Unit
)
