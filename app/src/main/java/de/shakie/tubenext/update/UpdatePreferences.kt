package de.shakie.tubenext.update

import android.content.Context
import java.io.File

class UpdatePreferences(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    var notificationsEnabled: Boolean
        get() = preferences.getBoolean(KEY_NOTIFICATIONS_ENABLED, true)
        set(value) = preferences.edit().putBoolean(KEY_NOTIFICATIONS_ENABLED, value).apply()

    var ignoredReleaseTag: String?
        get() = preferences.getString(KEY_IGNORED_RELEASE_TAG, null)
        set(value) = preferences.edit().putString(KEY_IGNORED_RELEASE_TAG, value).apply()

    var lastCheckAtMillis: Long
        get() = preferences.getLong(KEY_LAST_CHECK_AT_MILLIS, 0L)
        set(value) = preferences.edit().putLong(KEY_LAST_CHECK_AT_MILLIS, value).apply()

    var lastInstallAttemptVersionName: String?
        get() = preferences.getString(KEY_LAST_INSTALL_ATTEMPT_VERSION_NAME, null)
        set(value) = preferences.edit().putString(KEY_LAST_INSTALL_ATTEMPT_VERSION_NAME, value).apply()

    var lastInstallAttemptTag: String?
        get() = preferences.getString(KEY_LAST_INSTALL_ATTEMPT_TAG, null)
        set(value) = preferences.edit().putString(KEY_LAST_INSTALL_ATTEMPT_TAG, value).apply()

    var postInstallReminderDismissedVersionName: String?
        get() = preferences.getString(KEY_POST_INSTALL_REMINDER_DISMISSED_VERSION_NAME, null)
        set(value) = preferences.edit()
            .putString(KEY_POST_INSTALL_REMINDER_DISMISSED_VERSION_NAME, value)
            .apply()

    var postInstallReminderPermanentlyHidden: Boolean
        get() = preferences.getBoolean(KEY_POST_INSTALL_REMINDER_PERMANENTLY_HIDDEN, false)
        set(value) = preferences.edit()
            .putBoolean(KEY_POST_INSTALL_REMINDER_PERMANENTLY_HIDDEN, value)
            .apply()

    fun saveDownloadedApk(release: UpdateRelease, asset: UpdateAsset, file: File) {
        preferences.edit()
            .putString(KEY_DOWNLOADED_TAG, release.tagName)
            .putString(KEY_DOWNLOADED_ASSET_NAME, asset.name)
            .putString(KEY_DOWNLOADED_FILE_PATH, file.absolutePath)
            .apply()
    }

    fun downloadedApkFor(release: UpdateRelease): File? {
        val tag = preferences.getString(KEY_DOWNLOADED_TAG, null)
        if (tag != release.tagName) return null
        val path = preferences.getString(KEY_DOWNLOADED_FILE_PATH, null) ?: return null
        return File(path).takeIf { it.isFile }
    }

    fun clearDownloadedApk() {
        preferences.edit()
            .remove(KEY_DOWNLOADED_TAG)
            .remove(KEY_DOWNLOADED_ASSET_NAME)
            .remove(KEY_DOWNLOADED_FILE_PATH)
            .apply()
    }

    fun markInstallAttempt(release: UpdateRelease) {
        preferences.edit()
            .putString(KEY_LAST_INSTALL_ATTEMPT_VERSION_NAME, release.versionName)
            .putString(KEY_LAST_INSTALL_ATTEMPT_TAG, release.tagName)
            .apply()
    }

    private companion object {
        private const val PREFERENCES_NAME = "tube_next_update_preferences"
        private const val KEY_NOTIFICATIONS_ENABLED = "notifications_enabled"
        private const val KEY_IGNORED_RELEASE_TAG = "ignored_release_tag"
        private const val KEY_LAST_CHECK_AT_MILLIS = "last_check_at_millis"
        private const val KEY_DOWNLOADED_TAG = "downloaded_tag"
        private const val KEY_DOWNLOADED_ASSET_NAME = "downloaded_asset_name"
        private const val KEY_DOWNLOADED_FILE_PATH = "downloaded_file_path"
        private const val KEY_LAST_INSTALL_ATTEMPT_VERSION_NAME = "last_install_attempt_version_name"
        private const val KEY_LAST_INSTALL_ATTEMPT_TAG = "last_install_attempt_tag"
        private const val KEY_POST_INSTALL_REMINDER_DISMISSED_VERSION_NAME =
            "post_install_reminder_dismissed_version_name"
        private const val KEY_POST_INSTALL_REMINDER_PERMANENTLY_HIDDEN =
            "post_install_reminder_permanently_hidden"
    }
}
