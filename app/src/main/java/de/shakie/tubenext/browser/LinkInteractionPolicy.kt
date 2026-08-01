package de.shakie.tubenext.browser

import java.net.URI

enum class LinkMenuAction {
    OPEN_CURRENT,
    OPEN_NEW,
    OPEN_EXTERNAL,
    COPY,
    SHARE
}

data class LinkMenuActionCallbacks(
    val openCurrent: (String) -> Unit,
    val openNew: (String) -> Unit,
    val openExternal: (String) -> Unit,
    val copy: (String) -> Unit,
    val share: (String) -> Unit
)

object LinkInteractionPolicy {
    const val OPEN_NEW_TAB_MESSAGE = "OPEN_NEW_TAB"
    const val SHOW_LINK_MENU_MESSAGE = "SHOW_LINK_MENU"

    fun isSupportedLinkMessage(type: String, url: String): Boolean {
        return type in setOf(OPEN_NEW_TAB_MESSAGE, SHOW_LINK_MENU_MESSAGE) &&
            isYouTubeHttpUrl(url)
    }

    fun isYouTubeHttpUrl(url: String): Boolean {
        val uri = runCatching { URI(url) }.getOrNull() ?: return false
        if (!uri.isAbsolute || uri.rawUserInfo != null) return false
        val scheme = uri.scheme?.lowercase()
        val port = uri.port
        if (port != -1 && !isStandardPort(scheme, port)) return false
        return NavigationHostPolicy.isHttpOrHttpsScheme(scheme) &&
            NavigationHostPolicy.isYouTubeHost(uri.host)
    }

    fun dispatch(
        action: LinkMenuAction,
        url: String,
        callbacks: LinkMenuActionCallbacks
    ): Boolean {
        if (!isYouTubeHttpUrl(url)) return false
        when (action) {
            LinkMenuAction.OPEN_CURRENT -> callbacks.openCurrent(url)
            LinkMenuAction.OPEN_NEW -> callbacks.openNew(url)
            LinkMenuAction.OPEN_EXTERNAL -> callbacks.openExternal(url)
            LinkMenuAction.COPY -> callbacks.copy(url)
            LinkMenuAction.SHARE -> callbacks.share(url)
        }
        return true
    }

    private fun isStandardPort(scheme: String?, port: Int): Boolean {
        return (scheme == "http" && port == 80) || (scheme == "https" && port == 443)
    }
}
