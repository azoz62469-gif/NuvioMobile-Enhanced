package com.nuvio.app.features.player

data class SubtitleSyncCue(
    val startTimeMs: Long,
    val endTimeMs: Long = startTimeMs + 5_000L,
    val text: String,
)
