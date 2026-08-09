package de.shakie.tubenext.audio

internal object MediaSessionLifecyclePolicy {
    fun shouldExposeSession(hasMediaControls: Boolean): Boolean = hasMediaControls

    fun shouldShowBackgroundPlayer(
        appInBackground: Boolean,
        isPlaying: Boolean,
        controlsMatchPlayback: Boolean
    ): Boolean = appInBackground && isPlaying && controlsMatchPlayback
}
