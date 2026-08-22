package de.shakie.tubenext.browser

import android.net.Uri

object LinkInterceptor {
    fun isYouTubeUri(uri: Uri?): Boolean {
        if (uri == null) return false
        return isYouTubeHttpNavigation(uri.scheme, uri.host)
    }

    fun isHttpOrHttps(uri: Uri?): Boolean {
        return NavigationHostPolicy.isHttpOrHttpsScheme(uri?.scheme)
    }

    fun isInternalFlowUri(uri: Uri?): Boolean {
        if (uri == null) return false
        return NavigationHostPolicy.isInternalHttpNavigation(uri.toString())
    }

    internal fun isYouTubeHttpNavigation(scheme: String?, host: String?): Boolean {
        return NavigationHostPolicy.isHttpOrHttpsScheme(scheme) &&
            NavigationHostPolicy.isYouTubeHost(host)
    }

    internal fun isInternalHttpNavigation(scheme: String?, host: String?): Boolean {
        return NavigationHostPolicy.isInternalHttpNavigation(scheme, host)
    }

    internal fun isInternalHttpNavigation(url: String): Boolean {
        return NavigationHostPolicy.isInternalHttpNavigation(url)
    }
}
