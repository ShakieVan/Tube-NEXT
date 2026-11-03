package de.shakie.tubenext.audio

internal object MediaSessionLifecyclePolicy {
    enum class PlaybackOwnership {
        ACCEPT,
        TAKE_OVER,
        IGNORE
    }

    fun shouldExposeSession(hasMediaControls: Boolean): Boolean = hasMediaControls

    fun shouldShowBackgroundPlayer(
        appInBackground: Boolean,
        isPlaying: Boolean,
        controlsMatchPlayback: Boolean
    ): Boolean = appInBackground && isPlaying && controlsMatchPlayback

    /**
     * A pause from the system media controls, a headset or the page itself must reach a
     * running background player so that it releases its foreground service and wake lock.
     */
    fun shouldRemoveBackgroundPlayer(appInBackground: Boolean, isPlaying: Boolean): Boolean =
        appInBackground && !isPlaying

    /**
     * Gecko pauses the previous player when another tab starts, without a pause event,
     * and may still repeat that tab's old playing state. Only a real start moves the session.
     */
    fun ownershipForPlaybackState(
        ownerTabId: String?,
        tabId: String,
        isPlaying: Boolean,
        startedPlayback: Boolean
    ): PlaybackOwnership = when {
        ownerTabId == null || ownerTabId == tabId -> PlaybackOwnership.ACCEPT
        isPlaying && startedPlayback -> PlaybackOwnership.TAKE_OVER
        else -> PlaybackOwnership.IGNORE
    }

    /** A newly activated tab waits for its own start while another tab is still playing. */
    fun mayMoveControlsTo(ownerTabId: String?, tabId: String, ownerIsPlaying: Boolean): Boolean =
        ownerTabId == null || ownerTabId == tabId || !ownerIsPlaying
}
