package de.shakie.tubenext.tabs

import de.shakie.tubenext.browser.YouTubeNavigationPolicy
import java.net.URI
import java.net.URLDecoder

/** A tab checkpoint, kept separate from the live URL and canonical history. */
data class WatchProgress(val videoId: String, val positionSeconds: Long) {
    fun restoreUrl(url: String): String {
        if (positionSeconds < 0 || YouTubeNavigationPolicy.videoIdForUrl(url) != videoId) return url
        val uri = runCatching { URI(url) }.getOrNull() ?: return url
        val query = uri.rawQuery.orEmpty().split('&').filter { part ->
            part.isNotEmpty() && runCatching {
                URLDecoder.decode(part.substringBefore('='), "UTF-8")
            }.getOrDefault(part) !in TIME_PARAMETERS
        } + "t=${positionSeconds}s"
        // Preserve playlist/context parameters byte-for-byte; replace conflicting timestamps.
        val fragment = uri.rawFragment?.takeUnless {
            it.matches(Regex("(?:t=)?[0-9hms]+")) ||
                it.substringBefore('=') in TIME_PARAMETERS
        }
        return url.substringBefore('#').substringBefore('?') + "?" + query.joinToString("&") +
            (fragment?.let { "#$it" } ?: "")
    }

    companion object {
        private val TIME_PARAMETERS = setOf("t", "start", "time_continue")

        fun fromSample(url: String, position: Double, duration: Double, ended: Boolean): WatchProgress? {
            val videoId = YouTubeNavigationPolicy.videoIdForUrl(url) ?: return null
            if (!position.isFinite() || !duration.isFinite() || duration <= 0 ||
                position < 0 || position > duration || position >= Long.MAX_VALUE.toDouble()
            ) return null
            return WatchProgress(videoId, if (ended) 0 else position.toLong())
        }
    }
}
