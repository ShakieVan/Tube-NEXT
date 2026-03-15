package de.shakie.youtubenext.browser

import android.webkit.WebView

data class BrowserTab(
    val id: String,
    val webView: WebView,
    val chromeClient: YouTubeWebChromeClient,
    var isDesktopMode: Boolean = false,
    var title: String = "",
    var url: String = "",
    val navigationHistory: MutableList<String> = mutableListOf(),
    var historyIndex: Int = -1,
    var pendingHistoryNavigation: Boolean = false
)
