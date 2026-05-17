package de.shakie.tubenext.audio

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import de.shakie.tubenext.engine.EngineMediaControls
import de.shakie.tubenext.engine.EnginePlaybackState

class AndroidBackgroundAudioCoordinator(
    private val context: Context
) : BackgroundAudioCoordinator, BackgroundAudioService.Controller {
    private val audioManager = context.getSystemService(AudioManager::class.java)
    private val audioFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                .build()
        )
        .setAcceptsDelayedFocusGain(false)
        .setWillPauseWhenDucked(true)
        .setOnAudioFocusChangeListener(::onAudioFocusChanged, Handler(Looper.getMainLooper()))
        .build()
    private var controls: EngineMediaControls? = null
    private var lastState: EnginePlaybackState? = null
    private var artwork: Bitmap? = null
    private var appInBackground = false
    private var hasAudioFocus = false
    private var artworkVersion = 0
    private var controlsGeneration = 0
    private var lastNotification: NotificationSnapshot? = null
    private val handler = Handler(Looper.getMainLooper())
    private val foregroundServiceStopRunnable = Runnable {
        if (!appInBackground) {
            BackgroundAudioService.stop(context)
        }
    }

    fun setControls(controls: EngineMediaControls?) {
        this.controls = controls
        controlsGeneration += 1
        val generation = controlsGeneration
        BackgroundAudioService.controller = if (controls == null) null else this
        Log.i("TUBENEXT_AUDIO", "controls=${controls != null}")
        if (controls == null) {
            if (appInBackground && lastState?.isPlaying == true) {
                handler.postDelayed({
                    if (controlsGeneration == generation && this.controls == null && appInBackground) {
                        Log.i("TUBENEXT_AUDIO", "controls grace expired")
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
        artworkVersion += 1
        Log.i(
            "TUBENEXT_AUDIO",
            "artwork=${artwork != null} source=${bitmap?.width ?: 0}x${bitmap?.height ?: 0}"
        )
        if (appInBackground) {
            lastState?.let { showNotification(it, force = true) }
        }
    }

    override fun onForegroundPlaybackState(state: EnginePlaybackState) {
        lastState = state
        if (state.isPlaying && controls != null && !requestAudioFocus()) {
            pauseForAudioFocus("request denied")
            return
        }
        if (!state.isPlaying) {
            abandonAudioFocus()
        }
        if (appInBackground && controls != null) {
            showNotification(state)
        }
    }

    override fun onAppBackgrounded() {
        appInBackground = true
        handler.removeCallbacks(foregroundServiceStopRunnable)
        Log.i("TUBENEXT_AUDIO", "app backgrounded playing=${lastState?.isPlaying}")
        if (controls != null) {
            lastState?.let { showNotification(it, force = true) }
        }
    }

    override fun onAppForegrounded() {
        appInBackground = false
        lastNotification = null
        Log.i("TUBENEXT_AUDIO", "app foregrounded")
        handler.postDelayed(foregroundServiceStopRunnable, FOREGROUND_SERVICE_STOP_DELAY_MS)
    }

    override fun shutdown() {
        BackgroundAudioService.controller = null
        handler.removeCallbacksAndMessages(null)
        abandonAudioFocus()
        BackgroundAudioService.stop(context)
    }

    override fun play() {
        if (requestAudioFocus()) {
            controls?.play()
        } else {
            pauseForAudioFocus("play denied")
        }
    }

    override fun pause() {
        controls?.pause()
        abandonAudioFocus()
    }

    override fun stop() {
        controls?.stop()
        abandonAudioFocus()
    }

    override fun seekForward() {
        controls?.seekForward()
    }

    override fun seekBackward() {
        controls?.seekBackward()
    }

    private fun showNotification(state: EnginePlaybackState, force: Boolean = false) {
        val snapshot = NotificationSnapshot(
            title = state.title.ifBlank { "YouTube" },
            text = state.url,
            isPlaying = state.isPlaying,
            artworkVersion = artworkVersion
        )
        if (!force && snapshot == lastNotification) return
        lastNotification = snapshot
        Log.i(
            "TUBENEXT_AUDIO",
            "notify playing=${snapshot.isPlaying} artwork=${artwork != null} title=${snapshot.title}"
        )
        BackgroundAudioService.update(
            context,
            BackgroundAudioService.NotificationState(
                title = snapshot.title,
                text = snapshot.text,
                isPlaying = snapshot.isPlaying,
                artwork = artwork
            )
        )
    }

    private fun requestAudioFocus(): Boolean {
        if (hasAudioFocus) return true
        val result = audioManager.requestAudioFocus(audioFocusRequest)
        hasAudioFocus = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        Log.i("TUBENEXT_AUDIO", "audioFocus granted=$hasAudioFocus result=$result")
        return hasAudioFocus
    }

    private fun abandonAudioFocus() {
        if (!hasAudioFocus) return
        audioManager.abandonAudioFocusRequest(audioFocusRequest)
        hasAudioFocus = false
        Log.i("TUBENEXT_AUDIO", "audioFocus abandoned")
    }

    private fun onAudioFocusChanged(focusChange: Int) {
        Log.i("TUBENEXT_AUDIO", "audioFocus change=$focusChange")
        when (focusChange) {
            AudioManager.AUDIOFOCUS_LOSS,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                hasAudioFocus = false
                pauseForAudioFocus("focus loss $focusChange")
            }
            AudioManager.AUDIOFOCUS_GAIN -> {
                hasAudioFocus = true
            }
        }
    }

    private fun pauseForAudioFocus(reason: String) {
        Log.i("TUBENEXT_AUDIO", "pause for audio focus: $reason")
        controls?.pause()
        lastState = lastState?.copy(isPlaying = false)
        lastState?.let { state ->
            if (appInBackground && controls != null) {
                showNotification(state, force = true)
            }
        }
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
        private const val FOREGROUND_SERVICE_STOP_DELAY_MS = 750L
    }

    private data class NotificationSnapshot(
        val title: String,
        val text: String,
        val isPlaying: Boolean,
        val artworkVersion: Int
    )
}
