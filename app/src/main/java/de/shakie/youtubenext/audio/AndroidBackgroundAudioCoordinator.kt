package de.shakie.youtubenext.audio

import android.content.Context
import android.util.Log
import de.shakie.youtubenext.engine.EngineMediaControls
import de.shakie.youtubenext.engine.EnginePlaybackState

class AndroidBackgroundAudioCoordinator(
    private val context: Context
) : BackgroundAudioCoordinator, BackgroundAudioService.Controller {
    private var controls: EngineMediaControls? = null
    private var lastState: EnginePlaybackState? = null
    private var appInBackground = false

    fun setControls(controls: EngineMediaControls?) {
        this.controls = controls
        BackgroundAudioService.controller = if (controls == null) null else this
        Log.i("YTNEXT_AUDIO", "controls=${controls != null}")
        if (controls == null) {
            BackgroundAudioService.stop(context)
        }
    }

    override fun onForegroundPlaybackState(state: EnginePlaybackState) {
        lastState = state
        Log.i("YTNEXT_AUDIO", "state playing=${state.isPlaying} title=${state.title}")
        if (appInBackground && state.isPlaying) {
            showNotification(state)
        }
    }

    override fun onAppBackgrounded() {
        appInBackground = true
        Log.i("YTNEXT_AUDIO", "app backgrounded playing=${lastState?.isPlaying}")
        lastState?.takeIf { it.isPlaying }?.let(::showNotification)
    }

    override fun onAppForegrounded() {
        appInBackground = false
        Log.i("YTNEXT_AUDIO", "app foregrounded")
        BackgroundAudioService.stop(context)
    }

    override fun shutdown() {
        BackgroundAudioService.controller = null
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
                isPlaying = state.isPlaying
            )
        )
    }
}
