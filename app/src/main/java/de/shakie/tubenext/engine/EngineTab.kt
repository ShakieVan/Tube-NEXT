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
    fun stopLoading()
    fun detach()
    fun destroy()
    fun setDesktopMode(enabled: Boolean)
    fun isInCustomView(): Boolean
    fun exitFullscreenIfNeeded()
    fun evaluateJavascript(script: String, callback: ((String?) -> Unit)? = null)
    fun onPause()
    fun onResume()
}
