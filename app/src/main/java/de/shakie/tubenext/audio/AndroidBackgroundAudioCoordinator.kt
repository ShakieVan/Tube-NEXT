package de.shakie.tubenext.audio

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import de.shakie.tubenext.BuildConfig
import de.shakie.tubenext.engine.EngineMediaControls
import de.shakie.tubenext.engine.EnginePlaybackState

class AndroidBackgroundAudioCoordinator(
    private val context: Context
) : BackgroundAudioCoordinator, BackgroundAudioService.Controller {
    private val audioManager = context.getSystemService(AudioManager::class.java)
    private val mediaAudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
        .build()
    private val mediaSession = MediaSession(context, MEDIA_SESSION_TAG).apply {
        setCallback(object : MediaSession.Callback() {
            override fun onPlay() = this@AndroidBackgroundAudioCoordinator.play()

            override fun onPause() = this@AndroidBackgroundAudioCoordinator.pause()

            override fun onStop() = this@AndroidBackgroundAudioCoordinator.stop()

            override fun onFastForward() = this@AndroidBackgroundAudioCoordinator.seekForward()

            override fun onRewind() = this@AndroidBackgroundAudioCoordinator.seekBackward()

            override fun onSkipToNext() {
                controls?.nextTrack()
            }

            override fun onSkipToPrevious() {
                controls?.previousTrack()
            }
        })
        setPlaybackToLocal(mediaAudioAttributes)
        setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS or MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS)
        isActive = false
    }
    private val audioFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
        .setAudioAttributes(mediaAudioAttributes)
        .setAcceptsDelayedFocusGain(false)
        .setWillPauseWhenDucked(true)
        .setOnAudioFocusChangeListener(::onAudioFocusChanged, Handler(Looper.getMainLooper()))
        .build()
    private var controls: EngineMediaControls? = null
    private var controlsTabId: String? = null
    private var lastState: EnginePlaybackState? = null
    private var lastStateTabId: String? = null
    private var artwork: Bitmap? = null
    private var appInBackground = false
    private var hasAudioFocus = false
    private var foregroundRecoveryPending = false
    private var artworkVersion = 0
    private var controlsGeneration = 0
    private var lastNotification: NotificationSnapshot? = null
    private var lastMediaMetadata: MediaMetadataSnapshot? = null
    private var isShutdown = false
    private val handler = Handler(Looper.getMainLooper())
    private val foregroundServiceStopRunnable = Runnable {
        if (!appInBackground) {
            BackgroundAudioService.stop(context)
        }
    }

    override fun onMediaControlsChanged(tabId: String, controls: EngineMediaControls?) {
        if (isShutdown) return
        if (controls == null && controlsTabId != tabId) {
            debugLog("ignore controls=false tab=$tabId active=$controlsTabId")
            return
        }
        this.controls = controls
        controlsTabId = if (controls == null) null else tabId
        controlsGeneration += 1
        val generation = controlsGeneration
        mediaSession.isActive = MediaSessionLifecyclePolicy.shouldExposeSession(controls != null)
        BackgroundAudioService.controller = if (controls == null) null else this
        debugLog("controls=${controls != null} tab=$tabId")
        if (controls == null) {
            if (lastStateTabId == tabId && lastState?.isPlaying != true) {
                clearPlaybackState()
            }
            if (appInBackground && lastStateTabId == tabId && lastState?.isPlaying == true) {
                handler.postDelayed({
                    if (controlsGeneration == generation && this.controls == null && appInBackground) {
                        debugLog("controls grace expired")
                        clearPlaybackState()
                    }
                }, CONTROLS_LOST_GRACE_MS)
            } else {
                BackgroundAudioService.stop(context)
            }
        } else if (appInBackground) {
            lastState
                ?.takeIf { lastStateTabId == tabId && it.isPlaying }
                ?.let(::showNotification)
        }
        if (controls != null && lastStateTabId == tabId) {
            lastState?.let(::updateMediaSession)
        }
    }

    fun setArtwork(bitmap: Bitmap?) {
        if (isShutdown) return
        artwork = bitmap?.let(::createSoftArtwork)
        artworkVersion += 1
        debugLog("artwork=${artwork != null} source=${bitmap?.width ?: 0}x${bitmap?.height ?: 0}")
        lastState?.let { updateMediaSession(it, forceMetadata = true) }
        if (appInBackground) {
            lastState?.takeIf { it.isPlaying }?.let { showNotification(it, force = true) }
        }
    }

    override fun onForegroundPlaybackState(tabId: String, state: EnginePlaybackState) {
        if (isShutdown) return
        if (!state.isPlaying && lastStateTabId != null && lastStateTabId != tabId) {
            debugLog("ignore paused state tab=$tabId active=$lastStateTabId")
            return
        }
        lastState = state
        lastStateTabId = tabId
        updateMediaSession(state)
        if (state.isPlaying && controls != null && !requestAudioFocus()) {
            pauseForAudioFocus("request denied")
            return
        }
        if (!state.isPlaying) {
            abandonAudioFocus()
        }
        if (MediaSessionLifecyclePolicy.shouldShowBackgroundPlayer(
                appInBackground = appInBackground,
                isPlaying = state.isPlaying,
                controlsMatchPlayback = controls != null && controlsTabId == tabId
            )
        ) {
            showNotification(state)
        }
    }

    override fun onTabClosing(tabId: String) {
        if (controlsTabId != tabId && lastStateTabId != tabId) return
        debugLog("tab closing clears playback tab=$tabId")
        controls?.stop()
        clearPlaybackState()
    }

    fun isPlaybackActiveForTab(tabId: String): Boolean {
        return lastStateTabId == tabId && lastState?.isPlaying == true
    }

    fun onTabSuspended(tabId: String) {
        if (isPlaybackActiveForTab(tabId)) return
        if (controlsTabId != tabId && lastStateTabId != tabId) return
        debugLog("tab suspended clears playback tab=$tabId")
        clearPlaybackState()
    }

    override fun onAppBackgrounded() {
        if (isShutdown) return
        appInBackground = true
        handler.removeCallbacks(foregroundServiceStopRunnable)
        debugLog("app backgrounded playing=${lastState?.isPlaying} tab=$lastStateTabId controlsTab=$controlsTabId")
        if (MediaSessionLifecyclePolicy.shouldShowBackgroundPlayer(
                appInBackground = appInBackground,
                isPlaying = lastState?.isPlaying == true,
                controlsMatchPlayback = controls != null && controlsTabId == lastStateTabId
            )
        ) {
            lastState?.takeIf { it.isPlaying }?.let { showNotification(it, force = true) }
        }
    }

    override fun onAppForegrounded() {
        if (isShutdown) return
        appInBackground = false
        lastNotification = null
        debugLog("app foregrounded")
        handler.postDelayed(foregroundServiceStopRunnable, FOREGROUND_SERVICE_STOP_DELAY_MS)
    }

    override fun shutdown() {
        if (isShutdown) return
        isShutdown = true
        BackgroundAudioService.controller = null
        handler.removeCallbacksAndMessages(null)
        abandonAudioFocus()
        clearPlaybackState(stopService = false)
        BackgroundAudioService.stop(context)
        mediaSession.release()
    }

    override fun play() {
        if (requestAudioFocus()) {
            controls?.play()
        } else {
            pauseForAudioFocus("play denied")
        }
    }

    override fun pause() {
        controls?.pause()
        abandonAudioFocus()
    }

    override fun pauseForAudioRouteChange() {
        debugLog("pause for audio route change")
        if (appInBackground) {
            foregroundRecoveryPending = true
        }
        controls?.pause()
        lastState = lastState?.copy(isPlaying = false)
        lastState?.let { state ->
            updateMediaSession(state)
            if (appInBackground && controls != null) {
                showNotification(state, force = true)
            }
        }
        abandonAudioFocus()
    }

    override fun stop() {
        controls?.stop()
        abandonAudioFocus()
    }

    override fun seekForward() {
        controls?.seekForward()
    }

    override fun seekBackward() {
        controls?.seekBackward()
    }

    private fun showNotification(state: EnginePlaybackState, force: Boolean = false) {
        val snapshot = NotificationSnapshot(
            title = state.title.ifBlank { "YouTube" },
            text = state.url,
            isPlaying = state.isPlaying,
            artworkVersion = artworkVersion
        )
        if (!force && snapshot == lastNotification) return
        lastNotification = snapshot
        debugLog("notify playing=${snapshot.isPlaying} artwork=${artwork != null} title=${snapshot.title}")
        BackgroundAudioService.update(
            context,
            BackgroundAudioService.NotificationState(
                title = snapshot.title,
                text = state.artist.ifBlank { snapshot.text },
                isPlaying = snapshot.isPlaying,
                artwork = artwork,
                sessionToken = mediaSession.sessionToken
            )
        )
    }

    private fun updateMediaSession(state: EnginePlaybackState, forceMetadata: Boolean = false) {
        val metadataSnapshot = MediaMetadataSnapshot(
            title = state.title,
            artist = state.artist,
            durationMs = state.durationMs,
            artworkVersion = artworkVersion
        )
        if (forceMetadata || metadataSnapshot != lastMediaMetadata) {
            lastMediaMetadata = metadataSnapshot
            val metadata = MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, state.title)
                .putString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE, state.title)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, state.artist)
                .putString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE, state.artist)
                .apply {
                    state.durationMs?.let { putLong(MediaMetadata.METADATA_KEY_DURATION, it) }
                    artwork?.let { bitmap ->
                        putBitmap(MediaMetadata.METADATA_KEY_ART, bitmap)
                        putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, bitmap)
                        putBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON, bitmap)
                    }
                }
                .build()
            mediaSession.setMetadata(metadata)
        }

        val playbackState = if (state.isPlaying) {
            PlaybackState.STATE_PLAYING
        } else {
            PlaybackState.STATE_PAUSED
        }
        mediaSession.setPlaybackState(
            PlaybackState.Builder()
                .setActions(MEDIA_SESSION_ACTIONS)
                .setState(
                    playbackState,
                    state.positionMs.coerceAtLeast(0L),
                    if (state.isPlaying) 1f else 0f,
                    SystemClock.elapsedRealtime()
                )
                .build()
        )
    }

    private fun requestAudioFocus(): Boolean {
        if (hasAudioFocus) return true
        val result = audioManager.requestAudioFocus(audioFocusRequest)
        hasAudioFocus = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        debugLog("audioFocus granted=$hasAudioFocus result=$result")
        return hasAudioFocus
    }

    private fun abandonAudioFocus() {
        if (!hasAudioFocus) return
        audioManager.abandonAudioFocusRequest(audioFocusRequest)
        hasAudioFocus = false
        debugLog("audioFocus abandoned")
    }

    private fun onAudioFocusChanged(focusChange: Int) {
        debugLog("audioFocus change=$focusChange")
        when (focusChange) {
            AudioManager.AUDIOFOCUS_LOSS,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                hasAudioFocus = false
                pauseForAudioFocus("focus loss $focusChange")
            }
            AudioManager.AUDIOFOCUS_GAIN -> {
                hasAudioFocus = true
            }
        }
    }

    private fun pauseForAudioFocus(reason: String) {
        debugLog("pause for audio focus: $reason")
        controls?.pause()
        lastState = lastState?.copy(isPlaying = false)
        lastState?.let { state ->
            updateMediaSession(state)
            if (appInBackground && controls != null) {
                showNotification(state, force = true)
            }
        }
    }

    private fun clearPlaybackState(stopService: Boolean = true) {
        controls = null
        controlsTabId = null
        lastState = null
        lastStateTabId = null
        lastNotification = null
        lastMediaMetadata = null
        foregroundRecoveryPending = false
        mediaSession.setPlaybackState(
            PlaybackState.Builder()
                .setActions(MEDIA_SESSION_ACTIONS)
                .setState(PlaybackState.STATE_NONE, 0L, 0f)
                .build()
        )
        mediaSession.setMetadata(null)
        mediaSession.isActive = false
        BackgroundAudioService.controller = null
        abandonAudioFocus()
        if (stopService) {
            BackgroundAudioService.stop(context)
        }
    }

    fun consumeForegroundRecoveryPending(): Boolean {
        if (!foregroundRecoveryPending) return false
        foregroundRecoveryPending = false
        return true
    }

    private fun createSoftArtwork(source: Bitmap): Bitmap {
        val size = 512
        val scaled = Bitmap.createScaledBitmap(source, size / 8, size / 8, true)
        val artwork = Bitmap.createScaledBitmap(scaled, size, size, true)
        val canvas = Canvas(artwork)
        val dimPaint = Paint().apply {
            color = 0x66000000.toInt()
        }
        canvas.drawRect(0f, 0f, size.toFloat(), size.toFloat(), dimPaint)
        return artwork
    }

    private companion object {
        private const val MEDIA_SESSION_TAG = "Tube NEXT"
        private const val CONTROLS_LOST_GRACE_MS = 5_000L
        private const val FOREGROUND_SERVICE_STOP_DELAY_MS = 750L
        private const val MEDIA_SESSION_ACTIONS =
            PlaybackState.ACTION_PLAY or
                PlaybackState.ACTION_PAUSE or
                PlaybackState.ACTION_PLAY_PAUSE or
                PlaybackState.ACTION_STOP or
                PlaybackState.ACTION_FAST_FORWARD or
                PlaybackState.ACTION_REWIND or
                PlaybackState.ACTION_SKIP_TO_NEXT or
                PlaybackState.ACTION_SKIP_TO_PREVIOUS

        fun debugLog(message: String) {
            if (BuildConfig.DEBUG) {
                Log.i("TUBENEXT_AUDIO", message)
            }
        }
    }

    private data class NotificationSnapshot(
        val title: String,
        val text: String,
        val isPlaying: Boolean,
        val artworkVersion: Int
    )

    private data class MediaMetadataSnapshot(
        val title: String,
        val artist: String,
        val durationMs: Long?,
        val artworkVersion: Int
    )
}
