package de.shakie.youtubenext.audio

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.os.Handler
import android.os.Looper
import android.util.Log
import de.shakie.youtubenext.engine.EngineMediaControls
import de.shakie.youtubenext.engine.EnginePlaybackState

class AndroidBackgroundAudioCoordinator(
    private val context: Context
) : BackgroundAudioCoordinator, BackgroundAudioService.Controller {
    private var controls: EngineMediaControls? = null
    private var lastState: EnginePlaybackState? = null
    private var artwork: Bitmap? = null
    private var appInBackground = false
    private var controlsGeneration = 0
    private val handler = Handler(Looper.getMainLooper())

    fun setControls(controls: EngineMediaControls?) {
        this.controls = controls
        controlsGeneration += 1
        val generation = controlsGeneration
        BackgroundAudioService.controller = if (controls == null) null else this
        Log.i("YTNEXT_AUDIO", "controls=${controls != null}")
        if (controls == null) {
            if (appInBackground && lastState?.isPlaying == true) {
                handler.postDelayed({
                    if (controlsGeneration == generation && this.controls == null && appInBackground) {
                        Log.i("YTNEXT_AUDIO", "controls grace expired")
                        BackgroundAudioService.stop(context)
                    }
                }, CONTROLS_LOST_GRACE_MS)
            } else {
                BackgroundAudioService.stop(context)
            }
        } else if (appInBackground) {
            lastState?.let(::showNotification)
        }
    }

    fun setArtwork(bitmap: Bitmap?) {
        artwork = bitmap?.let(::createSoftArtwork)
        Log.i(
            "YTNEXT_AUDIO",
            "artwork=${artwork != null} source=${bitmap?.width ?: 0}x${bitmap?.height ?: 0}"
        )
        if (appInBackground) {
            lastState?.let(::showNotification)
        }
    }

    override fun onForegroundPlaybackState(state: EnginePlaybackState) {
        lastState = state
        Log.i("YTNEXT_AUDIO", "state playing=${state.isPlaying} title=${state.title}")
        if (appInBackground && controls != null) {
            showNotification(state)
        }
    }

    override fun onAppBackgrounded() {
        appInBackground = true
        Log.i("YTNEXT_AUDIO", "app backgrounded playing=${lastState?.isPlaying}")
        if (controls != null) {
            lastState?.let(::showNotification)
        }
    }

    override fun onAppForegrounded() {
        appInBackground = false
        Log.i("YTNEXT_AUDIO", "app foregrounded")
        BackgroundAudioService.stop(context)
    }

    override fun shutdown() {
        BackgroundAudioService.controller = null
        handler.removeCallbacksAndMessages(null)
        BackgroundAudioService.stop(context)
    }

    override fun play() {
        controls?.play()
    }

    override fun pause() {
        controls?.pause()
    }

    override fun stop() {
        controls?.stop()
    }

    override fun seekForward() {
        controls?.seekForward()
    }

    override fun seekBackward() {
        controls?.seekBackward()
    }

    private fun showNotification(state: EnginePlaybackState) {
        BackgroundAudioService.update(
            context,
            BackgroundAudioService.NotificationState(
                title = state.title.ifBlank { "YouTube" },
                text = state.url,
                isPlaying = state.isPlaying,
                artwork = artwork
            )
        )
    }

    private fun createSoftArtwork(source: Bitmap): Bitmap {
        val size = 512
        val scaled = Bitmap.createScaledBitmap(source, size / 8, size / 8, true)
        val artwork = Bitmap.createScaledBitmap(scaled, size, size, true)
        val canvas = Canvas(artwork)
        val dimPaint = Paint().apply {
            color = 0x66000000.toInt()
        }
        canvas.drawRect(0f, 0f, size.toFloat(), size.toFloat(), dimPaint)
        return artwork
    }

    private companion object {
        private const val CONTROLS_LOST_GRACE_MS = 5_000L
    }
}
