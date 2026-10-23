package com.nuvio.app.features.downloads

import com.nuvio.app.core.ui.NuvioToastController
import com.nuvio.app.features.details.MetaDetails
import com.nuvio.app.features.details.MetaVideo
import com.nuvio.app.features.player.PlayerStreamsRepository
import com.nuvio.app.features.profiles.ProfileRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow

internal data class SeasonDownloadProgress(val running: Boolean = false, val processed: Int = 0, val total: Int = 0, val queued: Int = 0, val skipped: Int = 0)

internal object SeasonDownloadQueue {
    val state = MutableStateFlow(SeasonDownloadProgress())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var job: Job? = null

    fun cancel() { job?.cancel(); job = null; state.value = state.value.copy(running = false) }

    fun start(meta: MetaDetails, episodes: List<MetaVideo>) {
        if (job?.isActive == true) return
        val profile = ProfileRepository.activeProfileId
        val selected = episodes.filter { it.available }.distinctBy { it.id }.sortedWith(compareBy({ it.season }, { it.episode }))
        state.value = SeasonDownloadProgress(running = true, total = selected.size)
        job = scope.launch {
            var completedNaturally = false
            try {
                DownloadsRepository.ensureLoaded()
                for (episode in selected) {
                    ensureActive()
                    if (ProfileRepository.activeProfileId != profile) break
                    while (DownloadsRepository.uiState.value.items.count { it.status == DownloadStatus.Downloading } >= 2) {
                        delay(1000)
                        if (ProfileRepository.activeProfileId != profile) return@launch
                    }
                    val existing = DownloadsRepository.uiState.value.items.any {
                        it.parentMetaId == meta.id && it.seasonNumber == episode.season && it.episodeNumber == episode.episode
                    }
                    val stream = if (existing) null else runCatching {
                        PlayerStreamsRepository.streamsForDownload(
                            meta.type, episode.id, episode.season, episode.episode,
                        ).firstOrNull { it.playableDirectUrl?.isSupportedDownloadUrl() == true }
                    }.getOrNull()
                    ensureActive()
                    if (ProfileRepository.activeProfileId != profile) break
                    val result = stream?.let {
                        DownloadsRepository.enqueueFromStream(
                            contentType = meta.type, videoId = episode.id, parentMetaId = meta.id, parentMetaType = meta.type,
                            title = meta.name, logo = meta.logo, poster = meta.poster, background = meta.background,
                            seasonNumber = episode.season, episodeNumber = episode.episode, episodeTitle = episode.title,
                            episodeThumbnail = episode.thumbnail, stream = it,
                        )
                    }
                    val queued = result == DownloadEnqueueResult.Started || result == DownloadEnqueueResult.Replaced
                    state.value = state.value.copy(processed = state.value.processed + 1,
                        queued = state.value.queued + if (queued) 1 else 0,
                        skipped = state.value.skipped + if (queued) 0 else 1)
                }
                completedNaturally = true
            } finally {
                state.value = state.value.copy(running = false)
                if (completedNaturally) {
                    NuvioToastController.show(
                        "تمت إضافة ${state.value.queued} حلقة للتنزيل" +
                            if (state.value.skipped > 0) "، وتعذر أو تكرر ${state.value.skipped}" else "",
                        durationMillis = 5_000L,
                    )
                }
            }
        }
    }
}
