package de.shakie.tubenext.tabs

import java.net.URI

class CanonicalTabHistory(initialUrl: String = "") {
    private val entries = mutableListOf<String>()
    private var currentIndex = -1
    private var pendingNavigation = false

    val isNavigationPending: Boolean
        get() = pendingNavigation

    init {
        record(initialUrl)
    }

    fun record(url: String) {
        val canonicalUrl = canonicalize(url)
        if (canonicalUrl.isBlank()) return

        if (pendingNavigation) {
            pendingNavigation = false
            if (currentIndex !in entries.indices) {
                entries.clear()
                entries.add(canonicalUrl)
                currentIndex = 0
                return
            }
            entries[currentIndex] = canonicalUrl
            return
        }

        if (entries.getOrNull(currentIndex) == canonicalUrl) return
        if (currentIndex < entries.lastIndex) {
            entries.subList(currentIndex + 1, entries.size).clear()
        }
        entries.add(canonicalUrl)
        currentIndex = entries.lastIndex
    }

    fun recordNavigationStart(url: String) {
        if (!pendingNavigation) record(url)
    }

    fun canGoBack(currentUrl: String): Boolean = targetIndex(-1, currentUrl) != null

    fun canGoForward(currentUrl: String): Boolean = targetIndex(1, currentUrl) != null

    fun backTarget(currentUrl: String): String? = navigationTarget(-1, currentUrl)

    fun forwardTarget(currentUrl: String): String? = navigationTarget(1, currentUrl)

    fun cancelPendingNavigation() {
        pendingNavigation = false
    }

    fun snapshot(): Snapshot = Snapshot(entries.toList(), currentIndex)

    fun restore(snapshot: Snapshot) {
        entries.clear()
        entries.addAll(snapshot.entries.filter { it.isNotBlank() })
        currentIndex = snapshot.currentIndex.coerceIn(-1, entries.lastIndex)
        pendingNavigation = false
    }

    private fun navigationTarget(direction: Int, currentUrl: String): String? {
        if (pendingNavigation) return null
        val targetIndex = targetIndex(direction, currentUrl) ?: return null
        currentIndex = targetIndex
        pendingNavigation = true
        return entries[targetIndex]
    }

    private fun targetIndex(direction: Int, currentUrl: String): Int? {
        if (pendingNavigation || direction !in setOf(-1, 1)) return null
        val currentCanonicalUrl = canonicalize(currentUrl)
        var candidate = currentIndex + direction
        while (candidate in entries.indices && entries[candidate] == currentCanonicalUrl) {
            candidate += direction
        }
        return candidate.takeIf { it in entries.indices }
    }

    data class Snapshot(val entries: List<String>, val currentIndex: Int)

    companion object {
        private val supportedYouTubeHosts = setOf(
            "youtube.com",
            "www.youtube.com",
            "m.youtube.com",
            "youtu.be"
        )

        fun canonicalize(url: String): String {
            val uri = runCatching { URI(url) }.getOrNull() ?: return ""
            val scheme = uri.scheme?.lowercase()
            val host = uri.host?.lowercase().orEmpty()
            if (scheme !in setOf("http", "https") || host !in supportedYouTubeHosts) return ""
            val path = uri.path.orEmpty()
            val isWatch = host == "youtu.be" || path.startsWith("/watch")
            if (!isWatch) {
                return url.substringBefore('#')
            }

            val videoId = if (host == "youtu.be") {
                path.trimStart('/').substringBefore('/')
            } else {
                uri.rawQuery.orEmpty()
                    .split('&')
                    .firstNotNullOfOrNull { parameter ->
                        val parts = parameter.split('=', limit = 2)
                        parts.getOrNull(1)?.takeIf { parts.firstOrNull() == "v" }
                    }
                    .orEmpty()
            }
            if (!videoId.matches(Regex("[A-Za-z0-9_-]+"))) return url.substringBefore('#')
            return "https://www.youtube.com/watch?v=$videoId"
        }
    }
}
