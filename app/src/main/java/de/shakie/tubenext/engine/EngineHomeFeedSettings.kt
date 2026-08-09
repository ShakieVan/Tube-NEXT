package de.shakie.tubenext.engine

data class EngineHomeFeedSettings(
    val showShorts: Boolean = true,
    val showCommunityPosts: Boolean = true,
    val showWatchHistory: Boolean = true,
    val hideWatchBranding: Boolean = false,
    val showWatchDislikes: Boolean = false
)
