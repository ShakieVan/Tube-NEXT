package de.shakie.tubenext.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaSessionLifecyclePolicyTest {
    @Test
    fun sessionRemainsExposedWheneverGeckoControlsExist() {
        assertTrue(MediaSessionLifecyclePolicy.shouldExposeSession(hasMediaControls = true))
        assertFalse(MediaSessionLifecyclePolicy.shouldExposeSession(hasMediaControls = false))
    }

    @Test
    fun foregroundServiceIsLimitedToMatchingBackgroundPlayback() {
        assertTrue(
            MediaSessionLifecyclePolicy.shouldShowBackgroundPlayer(
                appInBackground = true,
                isPlaying = true,
                controlsMatchPlayback = true
            )
        )
        assertFalse(
            MediaSessionLifecyclePolicy.shouldShowBackgroundPlayer(
                appInBackground = false,
                isPlaying = true,
                controlsMatchPlayback = true
            )
        )
        assertFalse(
            MediaSessionLifecyclePolicy.shouldShowBackgroundPlayer(
                appInBackground = true,
                isPlaying = false,
                controlsMatchPlayback = true
            )
        )
        assertFalse(
            MediaSessionLifecyclePolicy.shouldShowBackgroundPlayer(
                appInBackground = true,
                isPlaying = true,
                controlsMatchPlayback = false
            )
        )
    }

    @Test
    fun pausedBackgroundPlaybackReleasesForegroundPlayer() {
        assertTrue(
            MediaSessionLifecyclePolicy.shouldRemoveBackgroundPlayer(appInBackground = true, isPlaying = false)
        )
        assertFalse(
            MediaSessionLifecyclePolicy.shouldRemoveBackgroundPlayer(appInBackground = true, isPlaying = true)
        )
        assertFalse(
            MediaSessionLifecyclePolicy.shouldRemoveBackgroundPlayer(appInBackground = false, isPlaying = false)
        )
    }

    @Test
    fun onlyRealStartInAnotherTabTakesOverPlayback() {
        assertEquals(
            MediaSessionLifecyclePolicy.PlaybackOwnership.TAKE_OVER,
            MediaSessionLifecyclePolicy.ownershipForPlaybackState(
                ownerTabId = "a",
                tabId = "b",
                isPlaying = true,
                startedPlayback = true
            )
        )
        // Gecko paused tab a for b without a pause event and repeats a's old state in metadata.
        assertEquals(
            MediaSessionLifecyclePolicy.PlaybackOwnership.IGNORE,
            MediaSessionLifecyclePolicy.ownershipForPlaybackState(
                ownerTabId = "b",
                tabId = "a",
                isPlaying = true,
                startedPlayback = false
            )
        )
        assertEquals(
            MediaSessionLifecyclePolicy.PlaybackOwnership.IGNORE,
            MediaSessionLifecyclePolicy.ownershipForPlaybackState(
                ownerTabId = "b",
                tabId = "a",
                isPlaying = false,
                startedPlayback = false
            )
        )
    }

    @Test
    fun ownerAndFirstTabStatesAreAccepted() {
        assertEquals(
            MediaSessionLifecyclePolicy.PlaybackOwnership.ACCEPT,
            MediaSessionLifecyclePolicy.ownershipForPlaybackState(
                ownerTabId = "a",
                tabId = "a",
                isPlaying = false,
                startedPlayback = false
            )
        )
        assertEquals(
            MediaSessionLifecyclePolicy.PlaybackOwnership.ACCEPT,
            MediaSessionLifecyclePolicy.ownershipForPlaybackState(
                ownerTabId = null,
                tabId = "a",
                isPlaying = true,
                startedPlayback = false
            )
        )
    }

    @Test
    fun activatedTabCannotTakeControlsFromPlayingTab() {
        assertFalse(
            MediaSessionLifecyclePolicy.mayMoveControlsTo(ownerTabId = "a", tabId = "b", ownerIsPlaying = true)
        )
        assertTrue(
            MediaSessionLifecyclePolicy.mayMoveControlsTo(ownerTabId = "a", tabId = "b", ownerIsPlaying = false)
        )
        assertTrue(
            MediaSessionLifecyclePolicy.mayMoveControlsTo(ownerTabId = "a", tabId = "a", ownerIsPlaying = true)
        )
        assertTrue(
            MediaSessionLifecyclePolicy.mayMoveControlsTo(ownerTabId = null, tabId = "b", ownerIsPlaying = false)
        )
    }
}
