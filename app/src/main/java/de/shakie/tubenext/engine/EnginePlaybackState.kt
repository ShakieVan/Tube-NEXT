package de.shakie.tubenext.engine

data class EnginePlaybackState(
    val url: String,
    val title: String,
    val artist: String,
    val isPlaying: Boolean,
    val positionMs: Long,
    val durationMs: Long?,
    val isLive: Boolean,
    /** Only Gecko's play event; metadata and position refreshes may repeat a stale playing state. */
    val startedPlayback: Boolean = false
)
