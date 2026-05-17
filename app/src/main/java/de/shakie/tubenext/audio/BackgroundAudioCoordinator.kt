package de.shakie.tubenext.audio

import de.shakie.tubenext.engine.EnginePlaybackState

interface BackgroundAudioCoordinator {
    fun onForegroundPlaybackState(state: EnginePlaybackState)
    fun onAppBackgrounded()
    fun onAppForegrounded()
    fun shutdown()
}
