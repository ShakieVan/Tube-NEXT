package de.shakie.tubenext.audio

import android.app.Application
import androidx.lifecycle.AndroidViewModel

/** Keeps the native media-session bridge across Activity recreation. */
class BackgroundAudioCoordinatorViewModel(application: Application) : AndroidViewModel(application) {
    val coordinator = AndroidBackgroundAudioCoordinator(application.applicationContext)

    override fun onCleared() {
        coordinator.shutdown()
    }
}
