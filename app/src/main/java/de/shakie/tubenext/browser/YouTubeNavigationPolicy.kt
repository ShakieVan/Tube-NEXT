package de.shakie.tubenext.browser

import java.net.URI

object YouTubeNavigationPolicy {
    enum class RenderMode {
        MOBILE,
        DESKTOP_WATCH,
        DESKTOP_DOWNLOADS
    }

    fun renderModeForUrl(url: String): RenderMode {
        return when {
            isWatchUrl(url) -> RenderMode.DESKTOP_WATCH
            isDownloadsUrl(url) -> RenderMode.DESKTOP_DOWNLOADS
            else -> RenderMode.MOBILE
        }
    }

    fun shouldUseDesktopMode(url: String): Boolean {
        return renderModeForUrl(url) != RenderMode.MOBILE
    }

    fun isWatchUrl(url: String): Boolean {
        val uri = parseHttpUri(url) ?: return false
        val host = uri.host.lowercase()
        if (!isSupportedYouTubeHost(host)) return false
        return host == "youtu.be" || uri.path.orEmpty().startsWith("/watch")
    }

    // YouTube's own downloads list stays empty on the mobile site.
    fun isDownloadsUrl(url: String): Boolean {
        val uri = parseHttpUri(url) ?: return false
        if (!isSupportedYouTubeHost(uri.host.lowercase())) return false
        val path = uri.path.orEmpty()
        return path == "/feed/downloads" || path.startsWith("/feed/downloads/")
    }

    fun isUserVisibleUrl(url: String): Boolean {
        val uri = parseHttpUri(url) ?: return false
        return isSupportedYouTubeHost(uri.host.lowercase())
    }

    fun isSupportedYouTubeUrl(url: String): Boolean {
        val uri = parseHttpUri(url) ?: return false
        return isSupportedYouTubeHost(uri.host.lowercase())
    }

    fun videoIdForUrl(url: String): String? {
        val uri = parseHttpUri(url) ?: return null
        val host = uri.host.lowercase()
        if (!isSupportedYouTubeHost(host)) return null
        if (host == "youtu.be") {
            return uri.path.orEmpty()
                .trim('/')
                .substringBefore('/')
                .takeIf(::isValidVideoId)
        }
        if (!uri.path.orEmpty().startsWith("/watch")) return null
        return uri.rawQuery.orEmpty()
            .split('&')
            .firstNotNullOfOrNull { part ->
                val key = part.substringBefore('=', missingDelimiterValue = "")
                val value = part.substringAfter('=', missingDelimiterValue = "")
                value.takeIf { key == "v" && isValidVideoId(it) }
            }
    }

    fun urlsReferToSameVideo(firstUrl: String, secondUrl: String): Boolean {
        val firstVideoId = videoIdForUrl(firstUrl) ?: return false
        return firstVideoId == videoIdForUrl(secondUrl)
    }

    fun privacyShareUrlForUrl(url: String): String? {
        val videoId = videoIdForUrl(url) ?: return null
        return "https://www.youtube.com/watch?v=$videoId"
    }

    fun privacyShareUrlAtPosition(url: String, positionSeconds: Long): String? {
        val shareUrl = privacyShareUrlForUrl(url) ?: return null
        if (positionSeconds <= 0) return shareUrl
        val hours = positionSeconds / 3600
        val minutes = positionSeconds / 60 % 60
        val seconds = positionSeconds % 60
        val time = when {
            hours > 0 -> "${hours}h${minutes}m${seconds}s"
            minutes > 0 -> "${minutes}m${seconds}s"
            else -> "${seconds}s"
        }
        return "$shareUrl&t=$time"
    }

    private fun parseHttpUri(url: String): URI? {
        if (url.isBlank()) return null
        val uri = runCatching { URI(url) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase().orEmpty()
        if ((scheme != "http" && scheme != "https") || uri.host.isNullOrBlank()) return null
        return uri
    }

    private fun isSupportedYouTubeHost(host: String): Boolean {
        return host == "youtube.com" ||
            host == "www.youtube.com" ||
            host == "m.youtube.com" ||
            host == "youtu.be"
    }

    private fun isValidVideoId(value: String): Boolean {
        return value.isNotBlank() && value.all { character ->
            character.isLetterOrDigit() || character == '-' || character == '_'
        }
    }
}
