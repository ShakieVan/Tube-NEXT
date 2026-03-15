package de.shakie.youtubenext.browser

import android.annotation.SuppressLint
import android.graphics.Color
import android.os.Build
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebView
import androidx.core.net.toUri
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature
import java.util.WeakHashMap

object WebViewFactory {
    private const val DEFAULT_URL = "https://www.youtube.com/"
    private const val YOUTUBE_PREF_DARK = "f6=400"
    private val baseUserAgents = WeakHashMap<WebView, String>()

    @SuppressLint("SetJavaScriptEnabled")
    fun create(context: android.content.Context): WebView {
        val webView = WebView(context)
        val settings = webView.settings

        settings.javaScriptEnabled = true
        settings.javaScriptCanOpenWindowsAutomatically = true
        settings.setSupportMultipleWindows(true)
        settings.domStorageEnabled = true
        settings.loadsImagesAutomatically = true
        settings.mediaPlaybackRequiresUserGesture = true
        settings.cacheMode = WebSettings.LOAD_DEFAULT
        settings.textZoom = 100
        settings.useWideViewPort = false
        settings.loadWithOverviewMode = false
        settings.builtInZoomControls = false
        settings.displayZoomControls = false
        settings.setSupportZoom(false)
        settings.allowFileAccess = false
        settings.allowContentAccess = true
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        baseUserAgents[webView] = settings.userAgentString

        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(webView, true)
            applyYouTubeDarkThemePreference(this)
            flush()
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            settings.safeBrowsingEnabled = true
        }
        if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
            WebSettingsCompat.setAlgorithmicDarkeningAllowed(settings, false)
        }

        webView.setBackgroundColor(Color.BLACK)
        return webView
    }

    fun setDesktopMode(webView: WebView, enabled: Boolean) {
        val settings = webView.settings
        val baseUa = baseUserAgents[webView] ?: settings.userAgentString.also {
            baseUserAgents[webView] = it
        }
        val targetUa = if (enabled) desktopUserAgent(baseUa) else baseUa
        if (settings.userAgentString != targetUa) {
            settings.userAgentString = targetUa
        }
        settings.useWideViewPort = enabled
        settings.loadWithOverviewMode = false
    }

    fun normalizeStartUrl(rawUrl: String?): String {
        if (rawUrl.isNullOrBlank()) return DEFAULT_URL
        val uri = rawUrl.toUri()
        return if (uri.scheme.isNullOrBlank()) {
            "https://$rawUrl"
        } else {
            rawUrl
        }
    }

    private fun desktopUserAgent(existing: String): String {
        return existing
            .replace("Mobile", "", ignoreCase = true)
            .replace("Android", "X11; Linux x86_64", ignoreCase = true)
    }

    private fun applyYouTubeDarkThemePreference(cookieManager: CookieManager) {
        listOf("https://www.youtube.com", "https://m.youtube.com").forEach { url ->
            val mergedPref = mergeYouTubePref(cookieManager.getCookie(url))
            cookieManager.setCookie(
                url,
                "PREF=$mergedPref; Path=/; Domain=.youtube.com; Secure"
            )
        }
    }

    private fun mergeYouTubePref(cookieHeader: String?): String {
        val existingPref = cookieHeader
            ?.split(";")
            ?.map { it.trim() }
            ?.firstOrNull { it.startsWith("PREF=") }
            ?.removePrefix("PREF=")
            .orEmpty()

        val parts = existingPref
            .split("&")
            .filter { it.isNotBlank() && !it.startsWith("f6=") }
            .toMutableList()
        parts.add(YOUTUBE_PREF_DARK)
        return parts.joinToString("&")
    }
}
