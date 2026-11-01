package de.shakie.tubenext.engine

import android.view.View

interface EngineTab {
    val id: String
    val view: View
    var title: String
    var url: String

    fun loadUrl(url: String)
    fun reload()
    fun canGoBack(): Boolean
    fun goBack()
    fun canGoForward(): Boolean
    fun goForward()
    fun stopLoading()
    fun detach()
    fun destroy()
    fun setDesktopMode(enabled: Boolean)
    fun isInCustomView(): Boolean
    fun exitFullscreenIfNeeded()
    fun evaluateJavascript(script: String, callback: ((String?) -> Unit)? = null)
    fun setHomeFeedSettings(settings: EngineHomeFeedSettings)
    fun setVideoTransform(
        scale: Float,
        translationXFraction: Float,
        translationYFraction: Float
    )
    /** Calls back exactly once with the live player position, or null if the page has none. */
    fun requestWatchPosition(callback: (EngineWatchProgress?) -> Unit)
    fun onPause()
    fun onResume()
    fun recoverFromAudioRouteChange()
}
