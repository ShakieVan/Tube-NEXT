package de.shakie.tubenext.audio

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import de.shakie.tubenext.MainActivity
import de.shakie.tubenext.R

class BackgroundAudioService : Service() {
    private lateinit var mediaSession: MediaSession
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        ensureChannel()
        mediaSession = MediaSession(this, MEDIA_SESSION_TAG).apply {
            setCallback(object : MediaSession.Callback() {
                override fun onPlay() {
                    BackgroundAudioService.controller?.play()
                }

                override fun onPause() {
                    BackgroundAudioService.controller?.pause()
                }

                override fun onStop() {
                    BackgroundAudioService.controller?.stop()
                    stopSelf()
                }

                override fun onFastForward() {
                    BackgroundAudioService.controller?.seekForward()
                }

                override fun onRewind() {
                    BackgroundAudioService.controller?.seekBackward()
                }
            })
            isActive = true
        }
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
        mediaSession.setMetadata(buildMetadata(state))
        mediaSession.setPlaybackState(buildPlaybackState(state.isPlaying))
        Log.i(
            "TUBENEXT_AUDIO",
            "service foreground playing=${state.isPlaying} artwork=${state.artwork != null} title=${state.title}"
        )
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
        return if (state.isPlaying) START_STICKY else START_NOT_STICKY
    }

    override fun onDestroy() {
        isRunning = false
        foregroundStartPending = false
        stopAfterForegroundStart = false
        updateWakeLock(false)
        mediaSession.release()
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
            .setStyle(
                Notification.MediaStyle()
                    .setMediaSession(mediaSession.sessionToken)
                    .setShowActionsInCompactView(0)
            )
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

    private fun buildPlaybackState(isPlaying: Boolean): PlaybackState {
        val state = if (isPlaying) {
            PlaybackState.STATE_PLAYING
        } else {
            PlaybackState.STATE_PAUSED
        }
        return PlaybackState.Builder()
            .setActions(
                PlaybackState.ACTION_PLAY or
                    PlaybackState.ACTION_PAUSE or
                    PlaybackState.ACTION_PLAY_PAUSE or
                    PlaybackState.ACTION_STOP or
                    PlaybackState.ACTION_FAST_FORWARD or
                    PlaybackState.ACTION_REWIND
            )
            .setState(state, PlaybackState.PLAYBACK_POSITION_UNKNOWN, 1f)
            .build()
    }

    private fun buildMetadata(state: NotificationState): MediaMetadata {
        val builder = MediaMetadata.Builder()
            .putString(MediaMetadata.METADATA_KEY_TITLE, state.title)
            .putString(MediaMetadata.METADATA_KEY_ARTIST, state.text)
        state.artwork?.let { artwork ->
            builder.putBitmap(MediaMetadata.METADATA_KEY_ART, artwork)
            builder.putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, artwork)
            builder.putBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON, artwork)
        }
        return builder.build()
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

    data class NotificationState(
        val title: String,
        val text: String,
        val isPlaying: Boolean,
        val artwork: Bitmap? = null
    )

    interface Controller {
        fun play()
        fun pause()
        fun stop()
        fun seekForward()
        fun seekBackward()
    }

    companion object {
        private const val CHANNEL_ID = "tubenext_background_audio"
        private const val MEDIA_SESSION_TAG = "Tube NEXT"
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
                Log.i("TUBENEXT_AUDIO", "skip paused notification update; service not running")
            }
        }

        fun stop(context: Context) {
            if (foregroundStartPending) {
                stopAfterForegroundStart = true
                Log.i("TUBENEXT_AUDIO", "defer service stop until foreground start completes")
                return
            }
            if (isRunning) {
                context.stopService(Intent(context, BackgroundAudioService::class.java))
            }
        }
    }
}
