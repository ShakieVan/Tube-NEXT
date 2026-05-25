package de.shakie.tubenext.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class UpdateActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val preferences = UpdatePreferences(context)
        when (intent.action) {
            ACTION_IGNORE_VERSION -> {
                preferences.ignoredReleaseTag = intent.getStringExtra(EXTRA_RELEASE_TAG)
                UpdateNotifier.cancel(context)
            }
            ACTION_DISABLE_NOTIFICATIONS -> {
                preferences.notificationsEnabled = false
                UpdateNotifier.cancel(context)
            }
        }
    }

    companion object {
        const val ACTION_IGNORE_VERSION = "de.shakie.tubenext.update.IGNORE_VERSION"
        const val ACTION_DISABLE_NOTIFICATIONS =
            "de.shakie.tubenext.update.DISABLE_NOTIFICATIONS"
        const val EXTRA_RELEASE_TAG = "de.shakie.tubenext.update.RELEASE_TAG"
    }
}
