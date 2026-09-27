package de.shakie.tubenext.tabs

import de.shakie.tubenext.browser.YouTubeNavigationPolicy

data class TabSession(
    val id: String,
    val url: String,
    val title: String,
    val watchProgress: WatchProgress? = null
) {
    fun restoreUrl(): String = watchProgress?.restoreUrl(url) ?: url

    fun withPage(url: String, title: String): TabSession = copy(
        url = url,
        title = title,
        watchProgress = watchProgress?.takeIf {
            YouTubeNavigationPolicy.videoIdForUrl(url) == it.videoId
        }
    )

    fun withWatchProgress(progress: WatchProgress): TabSession =
        if (progress.positionSeconds >= 0 &&
            YouTubeNavigationPolicy.videoIdForUrl(url) == progress.videoId
        ) copy(watchProgress = progress) else this
}
