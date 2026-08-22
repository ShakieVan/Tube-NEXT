package de.shakie.tubenext.browser

import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.Locale

/** Security boundary for top-level navigation that may remain inside Gecko. */
object NavigationHostPolicy {
    private val googleLoginFlowHosts = setOf(
        "accounts.google.com",
        "consent.google.com",
        // Google may show post-2FA account suggestions before returning to YouTube.
        "gds.google.com"
    )
    private val googleSorryHosts = setOf("google.com", "www.google.com")

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

    fun isInternalHttpNavigation(url: String): Boolean {
        val uri = runCatching { URI(url) }.getOrNull() ?: return false
        if (isInternalHttpNavigation(uri.scheme, uri.host)) return true
        return isGoogleSorryChallenge(uri)
    }

    private fun isGoogleSorryChallenge(uri: URI): Boolean {
        if (uri.scheme?.lowercase(Locale.ROOT) != "https") return false
        val normalizedHost = normalizeHost(uri.host) ?: return false
        if (normalizedHost !in googleSorryHosts) return false
        val path = uri.path.orEmpty()
        if (path != "/sorry" && !path.startsWith("/sorry/")) return false

        val encodedContinueUrl = uri.rawQuery.orEmpty()
            .split('&')
            .firstNotNullOfOrNull { parameter ->
                val key = parameter.substringBefore('=', missingDelimiterValue = "")
                val value = parameter.substringAfter('=', missingDelimiterValue = "")
                value.takeIf { key == "continue" }
            }
            // The reCAPTCHA form posts to /sorry/index without a query. Gecko's
            // navigation callback cannot expose its YouTube return URL from the body.
            ?: return true
        val continueUrl = decodeQueryValue(encodedContinueUrl) ?: return false
        val continueUri = runCatching { URI(continueUrl) }.getOrNull() ?: return false
        return isHttpOrHttpsScheme(continueUri.scheme) && isYouTubeHost(continueUri.host)
    }

    private fun decodeQueryValue(value: String): String? {
        return runCatching {
            URLDecoder.decode(value, StandardCharsets.UTF_8.name())
        }.getOrNull()
    }

    private fun normalizeHost(host: String?): String? {
        return host
            ?.takeIf { it.isNotBlank() }
            ?.lowercase(Locale.ROOT)
    }
}
