package de.shakie.youtubenext.tabs

import de.shakie.youtubenext.engine.EngineTab

data class AppTab(
    val id: String,
    val engineTab: EngineTab,
    var isDesktopMode: Boolean = false,
    var title: String = "",
    var url: String = "",
    val navigationHistory: MutableList<String> = mutableListOf(),
    var historyIndex: Int = -1,
    var pendingHistoryNavigation: Boolean = false,
    var watchStabilizationGeneration: Long = 0L,
    var pageLoadGeneration: Long = 0L,
    var loadingOverlayVisible: Boolean = false,
    var loadingProgress: Int = 0
) {
}
