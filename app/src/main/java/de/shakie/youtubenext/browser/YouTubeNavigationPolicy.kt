package de.shakie.youtubenext.browser

import android.net.Uri

object YouTubeNavigationPolicy {
    enum class RenderMode {
        MOBILE,
        DESKTOP_WATCH
    }

    fun renderModeForUrl(url: String): RenderMode {
        return if (isWatchUrl(url)) RenderMode.DESKTOP_WATCH else RenderMode.MOBILE
    }

    fun shouldUseDesktopMode(url: String): Boolean {
        return renderModeForUrl(url) == RenderMode.DESKTOP_WATCH
    }

    fun isWatchUrl(url: String): Boolean {
        val uri = parseHttpUri(url) ?: return false
        val host = uri.host?.lowercase().orEmpty()
        if (!isSupportedYouTubeHost(host)) return false
        return host == "youtu.be" || uri.path.orEmpty().startsWith("/watch")
    }

    fun isUserVisibleUrl(url: String): Boolean {
        val uri = parseHttpUri(url) ?: return false
        return !isTransientInternalUrl(uri)
    }

    fun isSupportedYouTubeUrl(url: String): Boolean {
        val uri = parseHttpUri(url) ?: return false
        return isSupportedYouTubeHost(uri.host?.lowercase().orEmpty())
    }

    private fun parseHttpUri(url: String): Uri? {
        if (url.isBlank()) return null
        val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase().orEmpty()
        if (scheme != "http" && scheme != "https") return null
        return uri
    }

    private fun isSupportedYouTubeHost(host: String): Boolean {
        return host == "youtube.com" ||
            host == "www.youtube.com" ||
            host == "m.youtube.com" ||
            host == "youtu.be"
    }

    private fun isTransientInternalUrl(uri: Uri): Boolean {
        val host = uri.host?.lowercase().orEmpty()
        val path = uri.path.orEmpty()
        return host == "accounts.youtube.com" &&
            path.startsWith("/RotateCookiesPage", ignoreCase = true)
    }
}
