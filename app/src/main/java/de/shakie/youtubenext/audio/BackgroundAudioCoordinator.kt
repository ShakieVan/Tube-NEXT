package de.shakie.youtubenext.audio

import de.shakie.youtubenext.engine.EnginePlaybackState

interface BackgroundAudioCoordinator {
    fun onForegroundPlaybackState(state: EnginePlaybackState)
    fun onAppBackgrounded()
    fun onAppForegrounded()
    fun shutdown()
}
