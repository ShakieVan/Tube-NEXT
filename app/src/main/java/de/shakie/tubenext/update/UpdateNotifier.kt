package de.shakie.tubenext.update

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import de.shakie.tubenext.MainActivity
import de.shakie.tubenext.R

object UpdateNotifier {
    fun showUpdateAvailable(context: Context, release: UpdateRelease) {
        val preferences = UpdatePreferences(context)
        if (!preferences.notificationsEnabled) return
        if (preferences.ignoredReleaseTag == release.tagName) return
        if (!canPostNotifications(context)) return

        ensureChannel(context)
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification(context, release))
    }

    fun cancel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.cancel(NOTIFICATION_ID)
    }

    private fun buildNotification(context: Context, release: UpdateRelease): Notification {
        val contentIntent = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra(MainActivity.EXTRA_SHOW_UPDATE_MANAGER, true)
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(context.getString(R.string.update_notification_title))
            .setContentText(
                context.getString(
                    R.string.update_notification_text,
                    release.versionName
                )
            )
            .setContentIntent(contentIntent)
            .setAutoCancel(true)
            .addAction(
                android.R.drawable.ic_menu_view,
                context.getString(R.string.update_action_open_manager),
                contentIntent
            )
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                context.getString(R.string.update_action_ignore_version),
                actionIntent(context, UpdateActionReceiver.ACTION_IGNORE_VERSION, 1, release.tagName)
            )
            .addAction(
                android.R.drawable.ic_menu_manage,
                context.getString(R.string.update_action_disable_notifications),
                actionIntent(context, UpdateActionReceiver.ACTION_DISABLE_NOTIFICATIONS, 2, release.tagName)
            )
            .build()
    }

    private fun actionIntent(
        context: Context,
        action: String,
        requestCode: Int,
        releaseTag: String
    ): PendingIntent {
        val intent = Intent(context, UpdateActionReceiver::class.java)
            .setAction(action)
            .putExtra(UpdateActionReceiver.EXTRA_RELEASE_TAG, releaseTag)
        return PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.update_channel_name),
            NotificationManager.IMPORTANCE_DEFAULT
        )
        manager.createNotificationChannel(channel)
    }

    fun canPostNotifications(context: Context): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
    }

    private const val CHANNEL_ID = "tubenext_updates"
    private const val NOTIFICATION_ID = 84
}
