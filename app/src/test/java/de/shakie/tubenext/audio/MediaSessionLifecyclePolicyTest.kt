package de.shakie.tubenext.audio

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
}
