package de.shakie.youtubenext.browser

import android.app.Activity
import android.content.pm.ActivityInfo
import android.os.Build
import android.os.Message
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout

class YouTubeWebChromeClient(
    private val activity: Activity,
    private val container: FrameLayout,
    private val onTitleChanged: (String) -> Unit,
    private val onProgressChanged: (Int) -> Unit,
    private val onNewTabRequest: (String) -> Unit,
    private val onPopupUrlRequest: (String) -> Unit,
    private val onFullscreenChanged: (Boolean) -> Unit
) : WebChromeClient() {

    private var customView: View? = null
    private var customViewCallback: CustomViewCallback? = null
    private var fullscreenScale = 1f
    private var scaleDetector: ScaleGestureDetector? = null
    private var fullscreenTouchListener: View.OnTouchListener? = null
    val isInCustomView: Boolean
        get() = customView != null

    override fun onReceivedTitle(view: WebView?, title: String?) {
        super.onReceivedTitle(view, title)
        if (!title.isNullOrBlank()) {
            onTitleChanged(title)
        }
    }

    override fun onProgressChanged(view: WebView?, newProgress: Int) {
        super.onProgressChanged(view, newProgress)
        onProgressChanged.invoke(newProgress)
    }

    override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
        if (customView != null) {
            callback?.onCustomViewHidden()
            return
        }
        val fullscreenView = view ?: return
        fullscreenScale = 1f
        scaleDetector = ScaleGestureDetector(
            activity,
            object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScale(scaleGestureDetector: ScaleGestureDetector): Boolean {
                    fullscreenScale = (fullscreenScale * scaleGestureDetector.scaleFactor)
                        .coerceIn(1f, 2.5f)
                    val target = customView ?: return false
                    target.pivotX = target.width / 2f
                    target.pivotY = target.height / 2f
                    target.scaleX = fullscreenScale
                    target.scaleY = fullscreenScale
                    return true
                }
            }
        )
        fullscreenTouchListener = View.OnTouchListener { _, event ->
            val detector = scaleDetector ?: return@OnTouchListener false
            val multiTouch = event.pointerCount > 1
            if (multiTouch) {
                detector.onTouchEvent(event)
            }
            if ((event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) &&
                fullscreenScale < 1.02f
            ) {
                val target = customView
                target?.scaleX = 1f
                target?.scaleY = 1f
                fullscreenScale = 1f
            }
            multiTouch
        }
        customView = fullscreenView
        customViewCallback = callback
        container.visibility = View.VISIBLE
        container.setOnTouchListener(fullscreenTouchListener)
        container.addView(
            fullscreenView,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        onFullscreenChanged(true)
        activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        activity.window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            activity.window.insetsController?.hide(
                android.view.WindowInsets.Type.statusBars() or
                    android.view.WindowInsets.Type.navigationBars()
            )
        } else {
            activity.window.decorView.systemUiVisibility =
                View.SYSTEM_UI_FLAG_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        }
    }

    override fun onHideCustomView() {
        val fullscreenView = customView ?: return
        container.setOnTouchListener(null)
        scaleDetector = null
        fullscreenTouchListener = null
        container.removeView(fullscreenView)
        container.visibility = View.GONE
        customView = null
        customViewCallback?.onCustomViewHidden()
        customViewCallback = null
        onFullscreenChanged(false)
        activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        activity.window.clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            activity.window.insetsController?.show(
                android.view.WindowInsets.Type.statusBars() or
                    android.view.WindowInsets.Type.navigationBars()
            )
        } else {
            activity.window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_VISIBLE
        }
    }

    fun exitFullscreenIfNeeded() {
        if (customView != null) {
            onHideCustomView()
        }
    }

    override fun onCreateWindow(
        view: WebView?,
        isDialog: Boolean,
        isUserGesture: Boolean,
        resultMsg: Message?
    ): Boolean {
        val sourceView = view ?: return false
        val transport = resultMsg?.obj as? WebView.WebViewTransport ?: return false
        val temporaryView = WebView(sourceView.context)
        temporaryView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: android.webkit.WebResourceRequest?
            ): Boolean {
                val url = request?.url?.toString().orEmpty()
                if (url.isNotBlank()) {
                    onPopupUrlRequest(url)
                }
                temporaryView.destroy()
                return true
            }

            @Suppress("OVERRIDE_DEPRECATION")
            override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                if (!url.isNullOrBlank()) {
                    onPopupUrlRequest(url)
                }
                temporaryView.destroy()
                return true
            }
        }
        transport.webView = temporaryView
        resultMsg.sendToTarget()
        return true
    }
}
