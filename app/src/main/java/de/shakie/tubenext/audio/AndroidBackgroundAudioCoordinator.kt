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
import android.os.Build
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
        // Notifications should duck media briefly instead of pausing Gecko.
        .setWillPauseWhenDucked(false)
        .setOnAudioFocusChangeListener(::onAudioFocusChanged, Handler(Looper.getMainLooper()))
        .build()
    private var controls: EngineMediaControls? = null
    private var controlsTabId: String? = null
    private var lastState: EnginePlaybackState? = null
    private var lastStateTabId: String? = null
    private var artwork: Bitmap? = null
    private var appInBackground = false
    private var audioFocusRequestActive = false
    private var playingWithoutFocusDuringCall = false
    private var lastUserInteractionAtMs: Long? = null
    private val audioFocusInterruptionPolicy = AudioFocusInterruptionPolicy()
    private var foregroundRecoveryPending = false
    private var artworkVersion = 0
    private var controlsGeneration = 0
    private var lastNotification: NotificationSnapshot? = null
    private var lastMediaMetadata: MediaMetadataSnapshot? = null
    private var isShutdown = false
    private val handler = Handler(Looper.getMainLooper())
    private var activeCallMode = isActiveCallMode(audioManager.mode)
    private var audioModeChangedListener: AudioManager.OnModeChangedListener? = null
    private val transientFocusLossRunnable = Runnable {
        val ringing = audioManager.mode == AudioManager.MODE_RINGTONE
        when {
            !audioFocusInterruptionPolicy.shouldPauseForTransientFocusLoss(ringing) -> {
                debugLog("transient focus loss while ringing; keep playback active")
            }
            isActiveCall() -> pauseForActiveCall()
            else -> handleAudioFocusLoss(AudioFocusInterruptionPolicy.Loss.TRANSIENT)
        }
    }
    private val resumeAfterCallRunnable = Runnable {
        resumeAfterActiveCallIfNeeded()
    }
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

    init {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            audioModeChangedListener = AudioManager.OnModeChangedListener(::onAudioModeChanged)
                .also { listener ->
                    audioManager.addOnModeChangedListener(context.mainExecutor, listener)
                }
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
        if (state.isPlaying && controls != null) {
            if (!requestAudioFocus(explicitUserPlayback = hadRecentUserInteraction())) {
                pauseForAudioFocus("request denied")
                return
            }
            audioFocusInterruptionPolicy.cancelResume()
        }
        if (!state.isPlaying && !audioFocusInterruptionPolicy.resumePending) {
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            audioModeChangedListener?.let(audioManager::removeOnModeChangedListener)
            audioModeChangedListener = null
        }
        BackgroundAudioService.controller = null
        handler.removeCallbacksAndMessages(null)
        abandonAudioFocus()
        clearPlaybackState(stopService = false)
        BackgroundAudioService.stop(context)
        mediaSession.release()
    }

    override fun play() {
        if (requestAudioFocus(explicitUserPlayback = true)) {
            audioFocusInterruptionPolicy.cancelResume()
            controls?.play()
        } else {
            audioFocusInterruptionPolicy.cancelResume()
            pauseForAudioFocus("play denied")
        }
    }

    fun onUserInteraction() {
        lastUserInteractionAtMs = SystemClock.elapsedRealtime()
    }

    override fun pause() {
        audioFocusInterruptionPolicy.cancelResume()
        controls?.pause()
        abandonAudioFocus()
    }

    override fun pauseForAudioRouteChange() {
        debugLog("pause for audio route change")
        audioFocusInterruptionPolicy.cancelResume()
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
        audioFocusInterruptionPolicy.cancelResume()
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

    private fun requestAudioFocus(explicitUserPlayback: Boolean): Boolean {
        if (audioFocusRequestActive) {
            if (isActiveCall() && audioFocusInterruptionPolicy.resumePending) {
                return mayPlayWithoutFocusDuringCall(explicitUserPlayback)
            }
            return true
        }
        if (playingWithoutFocusDuringCall) {
            if (mayPlayWithoutFocusDuringCall(explicitUserPlayback = true)) return true
            playingWithoutFocusDuringCall = false
        }
        val result = audioManager.requestAudioFocus(audioFocusRequest)
        audioFocusRequestActive = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        debugLog("audioFocus granted=$audioFocusRequestActive result=$result")
        if (audioFocusRequestActive) return true
        if (mayPlayWithoutFocusDuringCall(explicitUserPlayback)) {
            // Android can lock audio focus during calls. A foreground start is
            // nevertheless an explicit user choice and Android deliberately
            // permits newly started media to remain audible during the call.
            playingWithoutFocusDuringCall = true
            debugLog("audioFocus denied during active call; allow foreground playback")
            return true
        }
        return false
    }

    private fun isActiveCall(): Boolean = isActiveCallMode(audioManager.mode)

    private fun isActiveCallMode(mode: Int): Boolean = when (mode) {
        AudioManager.MODE_IN_CALL,
        AudioManager.MODE_IN_COMMUNICATION,
        AudioManager.MODE_CALL_REDIRECT,
        AudioManager.MODE_COMMUNICATION_REDIRECT -> true
        else -> false
    }

    private fun mayPlayWithoutFocusDuringCall(explicitUserPlayback: Boolean): Boolean {
        return audioFocusInterruptionPolicy.mayPlayWithoutFocus(
            appInBackground = appInBackground,
            activeCall = isActiveCall(),
            explicitUserPlayback = explicitUserPlayback
        )
    }

    private fun hadRecentUserInteraction(): Boolean {
        val interactionAtMs = lastUserInteractionAtMs ?: return false
        val elapsed = SystemClock.elapsedRealtime() - interactionAtMs
        return elapsed in 0..USER_PLAYBACK_INTERACTION_WINDOW_MS
    }

    private fun abandonAudioFocus() {
        audioFocusInterruptionPolicy.cancelResume()
        playingWithoutFocusDuringCall = false
        if (!audioFocusRequestActive) return
        audioManager.abandonAudioFocusRequest(audioFocusRequest)
        audioFocusRequestActive = false
        debugLog("audioFocus abandoned")
    }

    private fun onAudioFocusChanged(focusChange: Int) {
        debugLog("audioFocus change=$focusChange mode=${audioManager.mode}")
        when (focusChange) {
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                handleAudioFocusLoss(AudioFocusInterruptionPolicy.Loss.CAN_DUCK)
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                handler.removeCallbacks(transientFocusLossRunnable)
                handler.postDelayed(transientFocusLossRunnable, TRANSIENT_FOCUS_MODE_SETTLE_MS)
            }
            AudioManager.AUDIOFOCUS_LOSS -> {
                audioFocusRequestActive = false
                if (isActiveCall()) {
                    pauseForActiveCall()
                } else {
                    handleAudioFocusLoss(AudioFocusInterruptionPolicy.Loss.PERMANENT)
                }
            }
            AudioManager.AUDIOFOCUS_GAIN -> {
                handler.removeCallbacks(transientFocusLossRunnable)
                handler.removeCallbacks(resumeAfterCallRunnable)
                audioFocusRequestActive = true
                playingWithoutFocusDuringCall = false
                resumeAfterActiveCallIfNeeded()
            }
        }
    }

    private fun onAudioModeChanged(mode: Int) {
        if (isShutdown) return
        debugLog("audioMode change=$mode")
        val wasActiveCall = activeCallMode
        activeCallMode = isActiveCallMode(mode)
        if (!wasActiveCall && activeCallMode) {
            handler.removeCallbacks(transientFocusLossRunnable)
            handler.removeCallbacks(resumeAfterCallRunnable)
            pauseForActiveCall()
        } else if (wasActiveCall && !activeCallMode) {
            handler.removeCallbacks(resumeAfterCallRunnable)
            handler.postDelayed(resumeAfterCallRunnable, CALL_END_FOCUS_SETTLE_MS)
        }
    }

    private fun pauseForActiveCall() {
        if (lastState?.isPlaying != true || controls == null) {
            debugLog("active call does not pause inactive playback")
            return
        }
        debugLog("active call pauses playback")
        audioFocusInterruptionPolicy.onFocusLoss(
            loss = AudioFocusInterruptionPolicy.Loss.TRANSIENT,
            wasPlaying = true
        )
        pauseForAudioFocus("active call")
    }

    private fun resumeAfterActiveCallIfNeeded() {
        if (!audioFocusInterruptionPolicy.resumePending || isActiveCall()) return
        if (requestAudioFocus(explicitUserPlayback = false)) {
            debugLog("resume after active call")
            controls?.play()
        }
    }

    private fun handleAudioFocusLoss(loss: AudioFocusInterruptionPolicy.Loss) {
        when (audioFocusInterruptionPolicy.onFocusLoss(
            loss = loss,
            wasPlaying = lastState?.isPlaying == true && controls != null
        )) {
            AudioFocusInterruptionPolicy.LossAction.KEEP_PLAYING -> {
                // Usually Android handles this automatically because
                // willPauseWhenDucked=false. Keep playing if a vendor still
                // delivers the callback.
                debugLog("audioFocus ducking keeps playback active")
            }
            AudioFocusInterruptionPolicy.LossAction.PAUSE_UNTIL_GAIN -> {
                pauseForAudioFocus("transient focus loss")
            }
            AudioFocusInterruptionPolicy.LossAction.PAUSE -> {
                pauseForAudioFocus("permanent focus loss")
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
        audioFocusInterruptionPolicy.cancelResume()
        playingWithoutFocusDuringCall = false
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
        private const val USER_PLAYBACK_INTERACTION_WINDOW_MS = 10_000L
        private const val TRANSIENT_FOCUS_MODE_SETTLE_MS = 100L
        private const val CALL_END_FOCUS_SETTLE_MS = 200L
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
