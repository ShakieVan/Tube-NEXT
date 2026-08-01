package de.shakie.tubenext.browser

import java.util.Locale

/** Security boundary for top-level navigation that may remain inside Gecko. */
object NavigationHostPolicy {
    private val googleLoginFlowHosts = setOf(
        "accounts.google.com",
        "consent.google.com"
    )

    fun isHttpOrHttpsScheme(scheme: String?): Boolean {
        return when (scheme?.lowercase(Locale.ROOT)) {
            "http", "https" -> true
            else -> false
        }
    }

    fun isYouTubeHost(host: String?): Boolean {
        val normalizedHost = normalizeHost(host) ?: return false
        return normalizedHost == "youtu.be" ||
            normalizedHost == "youtube.com" ||
            normalizedHost.endsWith(".youtube.com")
    }

    fun isInternalHttpNavigation(scheme: String?, host: String?): Boolean {
        if (!isHttpOrHttpsScheme(scheme)) return false
        val normalizedHost = normalizeHost(host) ?: return false
        return isYouTubeHost(normalizedHost) || normalizedHost in googleLoginFlowHosts
    }

    private fun normalizeHost(host: String?): String? {
        return host
            ?.takeIf { it.isNotBlank() }
            ?.lowercase(Locale.ROOT)
    }
}
