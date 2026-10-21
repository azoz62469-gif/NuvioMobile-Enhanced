package com.nuvio.app.features.autosync

internal data class AutoSyncSubtitleCandidate(
    val url: String,
    val language: String,
    val name: String? = null,
    val addonName: String? = null,
    val sourceName: String? = null,
    val videoContextKey: String? = null,
    val seasonNumber: Int? = null,
    val episodeNumber: Int? = null,
)

/** Optional AutoSync capability layered beside a platform player controller. */
internal interface AutoSyncPlayerController {
    fun cancelForManualSubtitleDelay()
    fun setAutoSyncSubtitleCandidates(candidates: List<AutoSyncSubtitleCandidate>)
    fun setSubtitleUriWithAutoSync(url: String)
    fun setSubtitleUriWithSelectedAutoSync(url: String)
    fun setAutoSyncAppliedListener(
        listener: ((subtitleUrl: String, delayMs: Int) -> Unit)?,
    )
}
