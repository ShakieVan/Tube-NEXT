package de.shakie.tubenext.audio

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.media.AudioManager
import android.media.session.MediaSession
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import de.shakie.tubenext.BuildConfig
import de.shakie.tubenext.MainActivity
import de.shakie.tubenext.R

class BackgroundAudioService : Service() {
    private var wakeLock: PowerManager.WakeLock? = null
    private var noisyReceiverRegistered = false
    private val noisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != AudioManager.ACTION_AUDIO_BECOMING_NOISY) return
            debugLog("audio becoming noisy; pause playback")
            notificationState = notificationState?.copy(isPlaying = false)
            BackgroundAudioService.controller?.pauseForAudioRouteChange()
        }
    }

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        ensureChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PLAY -> {
                notificationState = notificationState?.copy(isPlaying = true)
                BackgroundAudioService.controller?.play()
            }
            ACTION_PAUSE -> {
                notificationState = notificationState?.copy(isPlaying = false)
                BackgroundAudioService.controller?.pause()
            }
            ACTION_STOP -> {
                BackgroundAudioService.controller?.stop()
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_DISMISS -> {
                stopSelf()
                return START_NOT_STICKY
            }
        }

        val state = notificationState ?: NotificationState(
            title = getString(R.string.app_name),
            text = getString(R.string.background_audio_notification_text),
            isPlaying = false
        )
        updateWakeLock(state.isPlaying)
        updateNoisyReceiver(state.isPlaying)
        debugLog("service foreground playing=${state.isPlaying} artwork=${state.artwork != null} title=${state.title}")
        startForeground(NOTIFICATION_ID, buildNotification(state))
        foregroundStartPending = false
        if (stopAfterForegroundStart) {
            stopAfterForegroundStart = false
            stopSelf()
            return START_NOT_STICKY
        }
        if (!state.isPlaying) {
            // Samsung keeps media-style notifications non-clearable in a few
            // timing paths. Once playback is paused, remove our background
            // player completely instead of leaving a sticky paused row behind.
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
        // Restarting this service without its Gecko controller would create an
        // orphan notification that cannot control playback.
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        isRunning = false
        foregroundStartPending = false
        stopAfterForegroundStart = false
        updateWakeLock(false)
        updateNoisyReceiver(false)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildNotification(state: NotificationState): Notification {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            openIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val playPauseAction = if (state.isPlaying) {
            Notification.Action.Builder(
                android.R.drawable.ic_media_pause,
                getString(R.string.background_audio_pause),
                serviceIntent(ACTION_PAUSE, 1)
            ).build()
        } else {
            Notification.Action.Builder(
                android.R.drawable.ic_media_play,
                getString(R.string.background_audio_play),
                serviceIntent(ACTION_PLAY, 2)
            ).build()
        }
        val stopAction = Notification.Action.Builder(
            android.R.drawable.ic_menu_close_clear_cancel,
            getString(R.string.background_audio_stop),
            serviceIntent(ACTION_STOP, 3)
        ).build()

        val mediaStyle = Notification.MediaStyle()
            .setShowActionsInCompactView(0)
        state.sessionToken?.let(mediaStyle::setMediaSession)

        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setLargeIcon(state.artwork)
            .setContentTitle(state.title.ifBlank { getString(R.string.app_name) })
            .setContentText(state.text)
            .setContentIntent(contentIntent)
            .setDeleteIntent(serviceIntent(ACTION_DISMISS, 4))
            .setOngoing(state.isPlaying)
            .setShowWhen(false)
            .addAction(playPauseAction)
            .addAction(stopAction)
            .setStyle(mediaStyle)
            .build()
    }

    private fun serviceIntent(action: String, requestCode: Int): PendingIntent {
        val intent = Intent(this, BackgroundAudioService::class.java).setAction(action)
        return PendingIntent.getService(
            this,
            requestCode,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.background_audio_channel_name),
            NotificationManager.IMPORTANCE_LOW
        )
        manager.createNotificationChannel(channel)
    }

    private fun updateWakeLock(shouldHold: Boolean) {
        if (!shouldHold) {
            wakeLock?.takeIf { it.isHeld }?.release()
            wakeLock = null
            return
        }
        if (wakeLock?.isHeld == true) return
        val powerManager = getSystemService(PowerManager::class.java)
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "$packageName:BackgroundAudio"
        ).apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun updateNoisyReceiver(shouldListen: Boolean) {
        if (shouldListen && !noisyReceiverRegistered) {
            registerReceiver(noisyReceiver, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY))
            noisyReceiverRegistered = true
        } else if (!shouldListen && noisyReceiverRegistered) {
            unregisterReceiver(noisyReceiver)
            noisyReceiverRegistered = false
        }
    }

    data class NotificationState(
        val title: String,
        val text: String,
        val isPlaying: Boolean,
        val artwork: Bitmap? = null,
        val sessionToken: MediaSession.Token? = null
    )

    interface Controller {
        fun play()
        fun pause()
        fun pauseForAudioRouteChange()
        fun stop()
        fun seekForward()
        fun seekBackward()
    }

    companion object {
        private const val CHANNEL_ID = "tubenext_background_audio"
        private const val NOTIFICATION_ID = 42
        private const val ACTION_PLAY = "de.shakie.tubenext.audio.PLAY"
        private const val ACTION_PAUSE = "de.shakie.tubenext.audio.PAUSE"
        private const val ACTION_STOP = "de.shakie.tubenext.audio.STOP"
        private const val ACTION_DISMISS = "de.shakie.tubenext.audio.DISMISS"

        @Volatile
        var controller: Controller? = null

        @Volatile
        private var notificationState: NotificationState? = null

        @Volatile
        private var isRunning = false

        @Volatile
        private var foregroundStartPending = false

        @Volatile
        private var stopAfterForegroundStart = false

        fun update(context: Context, state: NotificationState) {
            notificationState = state
            val intent = Intent(context, BackgroundAudioService::class.java)
            if (state.isPlaying) {
                foregroundStartPending = true
                context.startForegroundService(intent)
            } else if (isRunning) {
                context.startService(intent)
            } else {
                debugLog("skip paused notification update; service not running")
            }
        }

        fun stop(context: Context) {
            if (foregroundStartPending) {
                stopAfterForegroundStart = true
                debugLog("defer service stop until foreground start completes")
                return
            }
            if (isRunning) {
                context.stopService(Intent(context, BackgroundAudioService::class.java))
            }
        }

        private fun debugLog(message: String) {
            if (BuildConfig.DEBUG) {
                Log.i("TUBENEXT_AUDIO", message)
            }
        }
    }
}
