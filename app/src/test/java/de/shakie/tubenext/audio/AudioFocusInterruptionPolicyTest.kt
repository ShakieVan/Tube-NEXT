package de.shakie.tubenext.audio

import org.junit.Assert.assertEquals
import org.junit.Test

class AudioFocusInterruptionPolicyTest {
    private val policy = AudioFocusInterruptionPolicy()

    @Test
    fun duckableInterruptionsKeepPlaybackRunning() {
        assertEquals(
            AudioFocusInterruptionPolicy.LossAction.KEEP_PLAYING,
            policy.onFocusLoss(AudioFocusInterruptionPolicy.Loss.CAN_DUCK, wasPlaying = true)
        )
        assertEquals(false, policy.resumePending)
    }

    @Test
    fun transientInterruptionsPauseUntilFocusReturns() {
        assertEquals(
            AudioFocusInterruptionPolicy.LossAction.PAUSE_UNTIL_GAIN,
            policy.onFocusLoss(AudioFocusInterruptionPolicy.Loss.TRANSIENT, wasPlaying = true)
        )
        assertEquals(true, policy.resumePending)
    }

    @Test
    fun transientFocusLossDuringRingingDoesNotPausePlayback() {
        assertEquals(false, policy.shouldPauseForTransientFocusLoss(ringing = true))
        assertEquals(true, policy.shouldPauseForTransientFocusLoss(ringing = false))
    }

    @Test
    fun permanentFocusLossDoesNotRequestAutomaticResume() {
        assertEquals(
            AudioFocusInterruptionPolicy.LossAction.PAUSE,
            policy.onFocusLoss(AudioFocusInterruptionPolicy.Loss.PERMANENT, wasPlaying = true)
        )
        assertEquals(false, policy.resumePending)
    }

    @Test
    fun pausedPlaybackDoesNotResumeAfterTransientInterruption() {
        policy.onFocusLoss(AudioFocusInterruptionPolicy.Loss.TRANSIENT, wasPlaying = false)

        assertEquals(false, policy.resumePending)
    }

    @Test
    fun activeUserPlaybackDuringCallCancelsPendingAutomaticResume() {
        policy.onFocusLoss(AudioFocusInterruptionPolicy.Loss.TRANSIENT, wasPlaying = true)

        // Explicit Play clears the resume marker before starting Gecko again.
        policy.cancelResume()

        assertEquals(false, policy.resumePending)
    }

    @Test
    fun foregroundPlaybackMayStartWithoutFocusDuringActiveCall() {
        assertEquals(
            true,
            policy.mayPlayWithoutFocus(
                appInBackground = false,
                activeCall = true,
                explicitUserPlayback = true
            )
        )
    }

    @Test
    fun focusIsStillRequiredOutsideForegroundCallUseCase() {
        assertEquals(
            false,
            policy.mayPlayWithoutFocus(
                appInBackground = true,
                activeCall = true,
                explicitUserPlayback = true
            )
        )
        assertEquals(
            false,
            policy.mayPlayWithoutFocus(
                appInBackground = false,
                activeCall = false,
                explicitUserPlayback = true
            )
        )
        assertEquals(
            false,
            policy.mayPlayWithoutFocus(
                appInBackground = false,
                activeCall = true,
                explicitUserPlayback = false
            )
        )
    }
}
