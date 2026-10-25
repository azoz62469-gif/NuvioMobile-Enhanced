package com.nuvio.app.features.downloads

import com.nuvio.app.features.addons.httpGetTextWithHeaders
import com.nuvio.app.features.player.PlayerSubtitleCueParser
import com.nuvio.app.features.autosync.AutoSyncSubtitleCueParser
import com.nuvio.app.features.autosync.AutoSyncSubtitleTextRetimer
import com.nuvio.app.features.autosync.AutoSyncReferenceTimeline
import com.nuvio.app.features.autosync.AutoSyncConsensus
import com.nuvio.app.features.player.loadAddonSubtitles
import com.nuvio.app.features.streams.StreamSubtitle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

internal object DownloadSubtitles {
    private val json = Json { ignoreUnknownKeys = true }
    private const val SUBTITLE_FETCH_TIMEOUT_MS = 5_000L

    suspend fun prepare(
        item: DownloadItem,
        localVideoUri: String,
        references: List<AutoSyncReferenceTimeline> = emptyList(),
        retryFailed: Boolean = false,
    ): Unit = withContext(Dispatchers.Default) {
        val storage = DownloadSubtitleStorage(localVideoUri)
        val manifest = readManifest(storage)
        val saved = manifest.tracks.filter { storage.localFileUri(it.fileName) != null }.toMutableList()
        var nextFileIndex = (saved.maxOfOrNull { it.fileName.substringBefore('.').toIntOrNull() ?: -1 } ?: -1) + 1
        val addonSubtitles = loadAddonSubtitles(item.subtitleRequests).map {
            StreamSubtitle(url = it.url, language = it.language, name = it.display)
        }
        val candidates = (item.sourceSubtitles + addonSubtitles)
            .distinctBy { it.url }
        val pending = candidates
            .filter { subtitle ->
                (subtitle.url.startsWith("https://", true) || subtitle.url.startsWith("http://", true)) &&
                    saved.none { it.sourceUrl == subtitle.url }
            }
        if (pending.isEmpty()) {
            val addonResolutionComplete =
                item.subtitleRequests.isEmpty() || addonSubtitles.isNotEmpty() || item.sourceSubtitles.isNotEmpty()
            storage.write(
                "manifest.json",
                json.encodeToString(SubtitleManifest(complete = addonResolutionComplete, tracks = saved)),
            )
            return@withContext
        }
        var failed = false
        for (batch in pending.chunked(4)) {
            val fetched = coroutineScope {
                batch.map { subtitle ->
                    async {
                        val body = try {
                            withTimeoutOrNull(SUBTITLE_FETCH_TIMEOUT_MS) {
                                httpGetTextWithHeaders(subtitle.url, subtitle.headers.orEmpty())
                            }?.takeIf { it.isNotBlank() && AutoSyncSubtitleCueParser.parse(it, subtitle.url).isNotEmpty() }
                        } catch (error: Exception) {
                            if (error is CancellationException) throw error
                            null
                        }
                        subtitle to body
                    }
                }.awaitAll()
            }
            for ((subtitle, body) in fetched) {
                currentCoroutineContext().ensureActive()
                if (body == null) { failed = true; continue }
                val extension = when {
                    body.contains("[Events]", true) -> "ass"
                    body.trimStart().startsWith("WEBVTT") -> "vtt"
                    else -> PlayerSubtitleCueParser.fileExtension(body, subtitle.url)
                }
                val fileName = "${nextFileIndex++}.$extension"
                try {
                    storage.write(fileName, body)
                    saved += DownloadedSubtitle(subtitle.url, fileName, subtitle.language, subtitle.name)
                    if (references.isNotEmpty()) {
                        val cues = AutoSyncSubtitleCueParser.parse(body, subtitle.url)
                        val match = AutoSyncConsensus.find(cues, references, consensus = true, segmented = false)
                        val synced = match?.let { AutoSyncSubtitleTextRetimer.retime(body, it.timeline) }
                        if (synced != null) {
                            val syncedName = "${nextFileIndex++}.$extension"
                            storage.write(syncedName, synced)
                            saved += DownloadedSubtitle(subtitle.url, syncedName, subtitle.language, "${subtitle.name ?: subtitle.language} • مزامنة")
                        }
                    }
                    storage.write("manifest.json", json.encodeToString(SubtitleManifest(tracks = saved.toList())))
                } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    failed = true
                }
            }
        }
        currentCoroutineContext().ensureActive()
        val allCandidatesSaved = candidates.all { candidate ->
            saved.any { it.sourceUrl == candidate.url }
        }
        val addonResolutionComplete =
            !retryFailed ||
                item.subtitleRequests.isEmpty() ||
                addonSubtitles.isNotEmpty() ||
                item.sourceSubtitles.isNotEmpty()
        runCatching {
            storage.write(
                "manifest.json",
                json.encodeToString(
                    SubtitleManifest(
                        complete = !failed && allCandidatesSaved && addonResolutionComplete,
                        tracks = saved,
                    ),
                ),
            )
        }
        Unit
    }

    fun localSubtitles(localVideoUri: String): List<StreamSubtitle> {
        if (!localVideoUri.startsWith("file:")) return emptyList()
        val storage = DownloadSubtitleStorage(localVideoUri)
        return readManifest(storage).tracks.mapNotNull { track ->
            val uri = storage.localFileUri(track.fileName) ?: return@mapNotNull null
            StreamSubtitle(url = uri, language = track.language, name = track.name)
        }
    }

    private fun readManifest(storage: DownloadSubtitleStorage): SubtitleManifest =
        runCatching {
            storage.read("manifest.json")?.let { json.decodeFromString<SubtitleManifest>(it) }
        }.getOrNull() ?: SubtitleManifest()
}

@Serializable
private data class SubtitleManifest(
    val complete: Boolean = false,
    val tracks: List<DownloadedSubtitle> = emptyList(),
)

@Serializable
private data class DownloadedSubtitle(
    val sourceUrl: String,
    val fileName: String,
    val language: String,
    val name: String? = null,
)

internal expect class DownloadSubtitleStorage(localVideoUri: String) {
    fun read(fileName: String): String?
    fun write(fileName: String, text: String)
    fun localFileUri(fileName: String): String?
    fun remove()
}
