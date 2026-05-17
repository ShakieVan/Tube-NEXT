package de.shakie.tubenext.engine

data class EnginePlaybackState(
    val url: String,
    val title: String,
    val isPlaying: Boolean,
    val positionMs: Long,
    val durationMs: Long?,
    val isLive: Boolean
)
