package de.shakie.youtubenext.browser

import android.net.Uri

object LinkInterceptor {
    private val supportedHosts = setOf(
        "youtube.com",
        "www.youtube.com",
        "m.youtube.com",
        "youtu.be"
    )
    private val allowedGoogleFlowHosts = setOf(
        "accounts.google.com",
        "consent.google.com",
        "consent.youtube.com",
        "myaccount.google.com"
    )

    fun isYouTubeUri(uri: Uri?): Boolean {
        if (uri == null) return false
        val host = uri.host?.lowercase() ?: return false
        return host in supportedHosts || host.endsWith(".youtube.com")
    }

    fun isHttpOrHttps(uri: Uri?): Boolean {
        val scheme = uri?.scheme?.lowercase() ?: return false
        return scheme == "http" || scheme == "https"
    }

    fun isInternalFlowUri(uri: Uri?): Boolean {
        if (uri == null) return false
        if (!isHttpOrHttps(uri)) return false
        val host = uri.host?.lowercase() ?: return false
        if (isYouTubeUri(uri)) return true
        if (host.startsWith("accounts.google.")) return true
        if (host.startsWith("consent.google.")) return true
        if (host.startsWith("myaccount.google.")) return true
        if (host in allowedGoogleFlowHosts) return true
        return host.endsWith(".google.com") || host.endsWith(".google.de")
    }
}
