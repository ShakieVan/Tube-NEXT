package de.shakie.tubenext.audio

import de.shakie.tubenext.engine.EnginePlaybackState
import de.shakie.tubenext.engine.EngineMediaControls

interface BackgroundAudioCoordinator {
    fun onForegroundPlaybackState(tabId: String, state: EnginePlaybackState)
    fun onMediaControlsChanged(tabId: String, controls: EngineMediaControls?)
    fun onTabClosing(tabId: String)
    fun onAppBackgrounded()
    fun onAppForegrounded()
    fun shutdown()
}
