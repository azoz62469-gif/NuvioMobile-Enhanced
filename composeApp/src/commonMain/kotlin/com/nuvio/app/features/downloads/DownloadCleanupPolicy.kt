package com.nuvio.app.features.downloads

internal object DownloadPlaybackGuard {
    val activePlayers = mutableSetOf<Any>()
}

internal fun eligibleForDownloadCleanup(completed: Boolean, episode: Boolean, downloadedAt: Long, watchedAt: Long?, now: Long): Boolean =
    completed && episode && watchedAt != null && watchedAt >= downloadedAt &&
        now >= watchedAt && now - watchedAt >= 7L * 24 * 60 * 60 * 1000
