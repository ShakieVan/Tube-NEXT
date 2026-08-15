package de.shakie.tubenext.audio

internal class AudioFocusInterruptionPolicy {
    var resumePending: Boolean = false
        private set

    enum class LossAction {
        KEEP_PLAYING,
        PAUSE_UNTIL_GAIN,
        PAUSE
    }

    fun onFocusLoss(loss: Loss, wasPlaying: Boolean): LossAction = when (loss) {
        Loss.CAN_DUCK -> LossAction.KEEP_PLAYING
        Loss.TRANSIENT -> {
            resumePending = wasPlaying
            LossAction.PAUSE_UNTIL_GAIN
        }
        Loss.PERMANENT -> {
            resumePending = false
            LossAction.PAUSE
        }
    }

    fun cancelResume() {
        resumePending = false
    }

    fun mayPlayWithoutFocus(
        appInBackground: Boolean,
        activeCall: Boolean,
        explicitUserPlayback: Boolean
    ): Boolean {
        return !appInBackground && activeCall && explicitUserPlayback
    }

    fun shouldPauseForTransientFocusLoss(ringing: Boolean): Boolean {
        return !ringing
    }

    enum class Loss {
        CAN_DUCK,
        TRANSIENT,
        PERMANENT
    }
}
