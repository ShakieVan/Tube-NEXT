package de.shakie.tubenext.engine

data class EngineWatchProgress(
    val url: String,
    val positionSeconds: Double,
    val durationSeconds: Double,
    val ended: Boolean
)
