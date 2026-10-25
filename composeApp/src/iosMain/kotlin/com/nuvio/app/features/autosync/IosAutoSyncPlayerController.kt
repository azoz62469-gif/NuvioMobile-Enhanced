package com.nuvio.app.features.autosync

import com.nuvio.app.core.ui.NuvioToastController
import com.nuvio.app.core.logging.InAppLogger
import com.nuvio.app.features.addons.httpGetTextWithHeaders
import com.nuvio.app.features.player.NuvioPlayerBridge
import com.nuvio.app.features.player.PlayerEngineController
import com.nuvio.app.features.player.PlayerSystemControlHandler
import com.nuvio.app.features.player.PlayerSystemControlSource
import com.nuvio.app.features.player.SubtitleLanguageMatching
import com.nuvio.app.features.player.SubtitleSyncCue
import com.nuvio.app.features.player.sanitizePlaybackHeaders
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.autosync_toast_manual_fallback
import org.jetbrains.compose.resources.getString
import platform.Foundation.NSFileManager
import platform.Foundation.NSString
import platform.Foundation.NSURL
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.create
import platform.Foundation.writeToFile
import platform.Foundation.stringWithContentsOfFile
import kotlin.coroutines.resume

/**
 * Auto Sync for Nuvio's iOS mpv player.
 *
 * Embedded subtitles are read separately through FFmpeg, then the confident timeline is written
 * into the add-on subtitle text. This keeps mpv's own subtitle rendering, styling, and manual
 * delay behavior intact.
 */
internal class IosAutoSyncPlayerController(
    private val bridge: NuvioPlayerBridge,
    private val delegate: PlayerEngineController,
    private val sourceUrl: () -> String,
    private val sourceHeaders: () -> Map<String, String>,
    private val getSubtitleHeaders: (String) -> Map<String, String>,
    private val getPreferredLanguage: () -> String?,
) : PlayerEngineController by delegate,
    AutoSyncPlayerController,
    PlayerSystemControlSource {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var job: Job? = null
    private var operationToken = 0L
    private var candidates: List<AutoSyncSubtitleCandidate> = emptyList()
    private var appliedListener: ((subtitleUrl: String, delayMs: Int) -> Unit)? = null
    private var manualFallbackNoticeSourceUrl: String? = null

    init {
        scope.launch {
            AutoSyncPreferencesRepository.preferredSubtitleAutoSyncOnStart.collect { enabled ->
                if (!enabled) {
                    cancel()
                    AutoSyncSyncedSubtitle.clear()
                }
            }
        }
    }

    override val keepsPlaybackInBackground: Boolean
        get() = (delegate as? PlayerSystemControlSource)?.keepsPlaybackInBackground ?: false

    override fun setSystemControlHandler(handler: PlayerSystemControlHandler?) {
        (delegate as? PlayerSystemControlSource)?.setSystemControlHandler(handler)
    }

    fun dispose() {
        cancel()
        appliedListener = null
        scope.cancel()
    }

    override fun cancelForManualSubtitleDelay() {
        cancel()
        AutoSyncSyncedSubtitle.clear()
    }

    override fun setAutoSyncSubtitleCandidates(value: List<AutoSyncSubtitleCandidate>) {
        candidates = value.distinctBy { it.url }
    }

    override fun setAutoSyncAppliedListener(
        listener: ((subtitleUrl: String, delayMs: Int) -> Unit)?,
    ) {
        appliedListener = listener
    }

    override fun setSubtitleUri(url: String) {
        cancel()
        AutoSyncSyncedSubtitle.clear()
        delegate.setSubtitleUri(url)
    }

    override fun selectSubtitleTrack(index: Int) {
        cancel()
        AutoSyncSyncedSubtitle.clear()
        delegate.selectSubtitleTrack(index)
    }

    override fun clearExternalSubtitle() {
        cancel()
        AutoSyncSyncedSubtitle.clear()
        delegate.clearExternalSubtitle()
    }

    override fun clearExternalSubtitleAndSelect(trackIndex: Int) {
        cancel()
        AutoSyncSyncedSubtitle.clear()
        delegate.clearExternalSubtitleAndSelect(trackIndex)
    }

    override fun setSubtitleUriWithAutoSync(url: String) {
        start(url, AutoSyncCandidateScope.STARTUP_SEARCH)
    }

    override fun setSubtitleUriWithSelectedAutoSync(url: String) {
        start(url, AutoSyncCandidateScope.SELECTED_ONLY)
    }

    private fun cancel() {
        operationToken++
        job?.cancel()
        job = null
        bridge.cancelEmbeddedSubtitleReferenceLoad()
    }

    fun invalidateSource() {
        cancel()
        AutoSyncSyncedSubtitle.clear()
    }

    private fun attachOriginal(url: String) {
        AutoSyncSyncedSubtitle.clear()
        delegate.setSubtitleUri(url)
    }

    private fun start(url: String, candidateScope: AutoSyncCandidateScope) {
        cancel()
        AutoSyncSyncedSubtitle.clear()
        val token = operationToken
        AutoSyncPreferencesRepository.ensureLoaded()
        val currentSourceUrl = sourceUrl()
        // Keep useful subtitles visible while the embedded reference is inspected.
        attachOriginal(url)
        if (
            !AutoSyncPreferencesRepository.preferredSubtitleAutoSyncOnStart.value ||
            !supportsAutoSyncMediaSource(currentSourceUrl)
        ) {
            return
        }
        val currentSourceHeaders = sourceHeaders()

        job = scope.launch {
            try {
                val referencesJob = async {
                    loadEmbeddedReferences(currentSourceUrl, currentSourceHeaders)
                }
                val selectedLanguage = languageFor(url)
                val selectedBody = loadCandidateBody(url)
                val selectedCues = selectedBody?.let { body ->
                    withContext(Dispatchers.Default) {
                        AutoSyncSubtitleCueParser.parse(body, url)
                    }
                }.orEmpty()
                val referenceLoad = referencesJob.await()
                val references = referenceLoad.references
                InAppLogger.info(
                    "AutoSync/iOS",
                    "references=${references.size} " +
                        references.joinToString(separator = ", ") { reference ->
                            "${reference.key}:${reference.cues.size}${if (reference.sampled) "s" else ""}"
                        } +
                        " reason=${referenceLoad.reason}" +
                        " subtitleStreams=${referenceLoad.subtitleStreams}" +
                        " candidateStreams=${referenceLoad.candidateStreams}" +
                        " packets=${referenceLoad.packetsRead}" +
                        " eof=${referenceLoad.reachedEof}",
                )
                if (selectedBody == null) {
                    InAppLogger.warn("AutoSync/iOS", "selected subtitle body did not load")
                } else if (selectedCues.isEmpty()) {
                    InAppLogger.warn("AutoSync/iOS", "selected subtitle produced no cues")
                }
                currentCoroutineContext().ensureActive()
                if (
                    !canApplyAutoSyncResult(
                        enabled = AutoSyncPreferencesRepository.preferredSubtitleAutoSyncOnStart.value,
                        capturedToken = token,
                        currentToken = operationToken,
                        sourceUnchanged = sourceUrl() == currentSourceUrl,
                    )
                ) {
                    return@launch
                }

                val selectedCandidate = candidates.firstOrNull { it.url == url }
                var best = if (selectedCues.isNotEmpty()) {
                    withContext(Dispatchers.Default) {
                        AutoSyncReferenceMatcher.findBest(selectedCues, references)
                    }
                } else {
                    null
                }
                var bestCandidate = selectedCues.takeIf { it.isNotEmpty() }?.let { cues ->
                    CandidateTimeline(
                        candidate = selectedCandidate ?: AutoSyncSubtitleCandidate(
                            url = url,
                            language = selectedLanguage.orEmpty(),
                        ),
                        body = selectedBody.orEmpty(),
                        cues = cues,
                    )
                }

                var addonReferenceCount = 0
                if (references.isEmpty() && selectedCandidate != null && selectedCues.isNotEmpty()) {
                    val trustedReferences = AutoSyncAddonReferencePolicy.rankTrustedCandidates(
                        selected = selectedCandidate,
                        candidates = candidates,
                        videoSourceUrl = currentSourceUrl,
                    )
                    val timingOnlyReferences =
                        if (trustedReferences.isEmpty()) {
                            AutoSyncAddonReferencePolicy.rankTimingOnlyCandidates(
                                selected = selectedCandidate,
                                candidates = candidates,
                                videoSourceUrl = currentSourceUrl,
                            )
                        } else {
                            emptyList()
                        }
                    val useTimingOnly =
                        AutoSyncAddonReferencePolicy.shouldUseTimingOnlyFallback(
                            embeddedReferences = references,
                            trustedReferences = trustedReferences,
                            timingOnlyReferences = timingOnlyReferences,
                        )
                    val rankedReferences =
                        if (useTimingOnly) timingOnlyReferences else trustedReferences
                    if (
                        AutoSyncAddonReferencePolicy.shouldUseTextFallback(
                            embeddedReferences = references,
                            rankedReferences = rankedReferences,
                        )
                    ) {
                        addonReferenceCount = rankedReferences.size
                        best = findAddonReferenceMatch(
                            target = selectedCues,
                            rankedReferences = rankedReferences,
                        )
                        InAppLogger.info(
                            "AutoSync/iOS",
                            "text reference fallback trusted=${trustedReferences.size} " +
                                "timingOnly=${timingOnlyReferences.size} " +
                                "best=${best?.score ?: -1.0}",
                        )
                    }
                }

                if (
                    references.isNotEmpty() &&
                    candidateScope == AutoSyncCandidateScope.STARTUP_SEARCH
                ) {
                    val aggressive = AutoSyncPreferencesRepository.aggressiveMode.value
                    if (best == null || aggressive || !isStrongMatch(best)) {
                        val alternatives = sameLanguageAlternativesFor(url, selectedLanguage)
                        val loadedAlternatives = coroutineScope {
                            alternatives.map { candidate ->
                                async {
                                    withTimeoutOrNull(CANDIDATE_TIMEOUT_MS) {
                                        loadCandidate(candidate)
                                    }
                                }
                            }.awaitAll().filterNotNull()
                        }

                        for (alternative in loadedAlternatives) {
                            val match = if (alternative.cues.isNotEmpty()) {
                                withContext(Dispatchers.Default) {
                                    AutoSyncReferenceMatcher.findBest(alternative.cues, references)
                                }
                            } else {
                                null
                            }
                            if (match != null && (best == null || match.score > best.score)) {
                                best = match
                                bestCandidate = alternative
                            }
                            if (!aggressive && best != null && isStrongMatch(best)) break
                        }
                    }
                }

                currentCoroutineContext().ensureActive()
                InAppLogger.info(
                    "AutoSync/iOS",
                    "selectedCues=${selectedCues.size} language=${selectedLanguage ?: "none"} " +
                        "best=${best?.score ?: -1.0} " +
                        "alignment=${best?.timeline?.alignmentSource ?: "none"} " +
                        "offsetMs=${best?.timeline?.alignmentInterceptMs ?: 0.0} " +
                        "addonReferences=$addonReferenceCount",
                )
                if (
                    !canApplyAutoSyncResult(
                        enabled = AutoSyncPreferencesRepository.preferredSubtitleAutoSyncOnStart.value,
                        capturedToken = token,
                        currentToken = operationToken,
                        sourceUnchanged = sourceUrl() == currentSourceUrl,
                    )
                ) {
                    return@launch
                }
                val match = best
                val candidate = bestCandidate
                if (match == null || candidate == null) {
                    InAppLogger.warn(
                        "AutoSync/iOS",
                        "no confident match references=${references.size} selectedCues=${selectedCues.size}",
                    )
                    showManualFallback(token, currentSourceUrl)
                    return@launch
                }
                if (
                    !isConfidentAutoSyncMatch(
                        score = match.score,
                        targetCoverage = match.timeline.targetCoverage,
                        referenceCoverage = match.timeline.referenceCoverage,
                        sampledReference = match.reference.sampled,
                    )
                ) {
                    InAppLogger.warn(
                        "AutoSync/iOS",
                        "match below safe apply threshold score=${match.score} " +
                            "target=${match.timeline.targetCoverage} " +
                            "reference=${match.timeline.referenceCoverage}",
                    )
                    showManualFallback(token, currentSourceUrl)
                    return@launch
                }

                val tolerance = AutoSyncPreferencesRepository.syncToleranceMs.value
                val keepOriginalTiming = candidate.candidate.url == url &&
                    tolerance > 0 &&
                    match.timeline.maxAlignmentShiftMs() <= tolerance
                if (keepOriginalTiming) {
                    if (
                        !canApplyAutoSyncResult(
                            enabled = AutoSyncPreferencesRepository
                                .preferredSubtitleAutoSyncOnStart.value,
                            capturedToken = token,
                            currentToken = operationToken,
                            sourceUnchanged = sourceUrl() == currentSourceUrl,
                        )
                    ) {
                        return@launch
                    }
                    delegate.setSubtitleUri(url)
                } else {
                    val retimed = withContext(Dispatchers.Default) {
                        AutoSyncSubtitleTextRetimer.retime(candidate.body, match.timeline)
                    }
                    currentCoroutineContext().ensureActive()
                    if (
                        !canApplyAutoSyncResult(
                            enabled = AutoSyncPreferencesRepository
                                .preferredSubtitleAutoSyncOnStart.value,
                            capturedToken = token,
                            currentToken = operationToken,
                            sourceUnchanged = sourceUrl() == currentSourceUrl,
                        )
                    ) {
                        return@launch
                    }
                    if (retimed == null) {
                        showManualFallback(token, currentSourceUrl)
                        return@launch
                    }
                    val subtitlePath = writeRetimedSubtitle(retimed)
                    currentCoroutineContext().ensureActive()
                    if (
                        !canApplyAutoSyncResult(
                            enabled = AutoSyncPreferencesRepository
                                .preferredSubtitleAutoSyncOnStart.value,
                            capturedToken = token,
                            currentToken = operationToken,
                            sourceUnchanged = sourceUrl() == currentSourceUrl,
                        )
                    ) {
                        return@launch
                    }
                    if (subtitlePath == null) {
                        showManualFallback(token, currentSourceUrl)
                        return@launch
                    }
                    delegate.setSubtitleUri(
                        NSURL.fileURLWithPath(subtitlePath).absoluteString ?: subtitlePath,
                    )
                }

                currentCoroutineContext().ensureActive()
                if (
                    !canApplyAutoSyncResult(
                        enabled = AutoSyncPreferencesRepository.preferredSubtitleAutoSyncOnStart.value,
                        capturedToken = token,
                        currentToken = operationToken,
                        sourceUnchanged = sourceUrl() == currentSourceUrl,
                    )
                ) {
                    return@launch
                }
                delegate.setSubtitleDelayMs(0)
                AutoSyncSyncedSubtitle.mark(candidate.candidate.url)
                appliedListener?.invoke(candidate.candidate.url, 0)
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Exception) {
                if (token != operationToken) return@launch
                if (!AutoSyncPreferencesRepository.preferredSubtitleAutoSyncOnStart.value) return@launch
                showManualFallback(token, currentSourceUrl)
            }
        }
    }

    private fun languageFor(url: String): String? =
        candidates.firstOrNull { it.url == url }
            ?.language
            ?.takeIf { it.isNotBlank() }
            ?: getPreferredLanguage()?.takeIf { it.isNotBlank() }

    private fun sameLanguageAlternativesFor(
        selectedUrl: String,
        selectedLanguage: String?,
    ): List<AutoSyncSubtitleCandidate> =
        candidates
            .asSequence()
            .filter { it.url.isNotBlank() && it.url != selectedUrl }
            .filter { candidate ->
                selectedLanguage.isNullOrBlank() ||
                    SubtitleLanguageMatching.matchesLanguageCode(
                        candidate.language,
                        selectedLanguage,
                    )
            }
            .distinctBy { it.url }
            .take(MAX_ALTERNATIVES)
            .toList()

    private suspend fun loadCandidate(candidate: AutoSyncSubtitleCandidate): CandidateTimeline? {
        val body = loadCandidateBody(candidate.url) ?: return null
        val cues = withContext(Dispatchers.Default) {
            AutoSyncSubtitleCueParser.parse(body, candidate.url)
        }
        return CandidateTimeline(candidate, body, cues)
    }

    private suspend fun findAddonReferenceMatch(
        target: List<SubtitleSyncCue>,
        rankedReferences: List<AutoSyncAddonReferencePolicy.RankedReference>,
    ): AutoSyncReferenceMatch? {
        val loaded = coroutineScope {
            rankedReferences.take(MAX_ADDON_REFERENCES).map { ranked ->
                async {
                    loadCandidate(ranked.candidate)?.let { timeline -> ranked to timeline }
                }
            }.awaitAll().filterNotNull()
        }
        for ((ranked, timeline) in loaded) {
            if (timeline.cues.isEmpty()) continue
            val match = withContext(Dispatchers.Default) {
                AutoSyncReferenceMatcher.findBest(
                    target = target,
                    references = listOf(
                        AutoSyncReferenceTimeline(
                            key = "addon:${ranked.candidate.addonName.orEmpty()}:" +
                                ranked.candidate.language,
                            language = ranked.candidate.language,
                            label = ranked.candidate.sourceName,
                            cues = timeline.cues,
                            sampled = false,
                        ),
                    ),
                )
            } ?: continue
            InAppLogger.info(
                "AutoSync/iOS",
                "trusted addon text reference language=${ranked.candidate.language} " +
                    "sameAddon=${ranked.sameAddon} " +
                    "evidence=${ranked.versionEvidence.joinToString("+")}",
            )
            return match
        }
        return null
    }

    @OptIn(ExperimentalForeignApi::class)
    private suspend fun loadCandidateBody(url: String): String? =
        if (url.startsWith("file://", ignoreCase = true)) {
            withContext(Dispatchers.Default) {
                runCatching {
                    val path = NSURL(string = url)?.path ?: return@runCatching null
                    NSString.stringWithContentsOfFile(path, NSUTF8StringEncoding, null)
                }.getOrNull()
            }
        } else {
            withTimeoutOrNull(CANDIDATE_TIMEOUT_MS) {
                runCatching {
                    httpGetTextWithHeaders(
                        url = url,
                        headers = subtitleHeadersFor(url),
                    )
                }.getOrNull()
            }
        }

    private fun subtitleHeadersFor(url: String): Map<String, String> =
        sanitizePlaybackHeaders(getSubtitleHeaders(url))

    private suspend fun loadEmbeddedReferences(
        currentSourceUrl: String,
        headers: Map<String, String>,
    ): EmbeddedReferenceLoad {
        val payload = suspendCancellableCoroutine { continuation ->
            bridge.loadEmbeddedSubtitleReferences(
                sourceUrl = currentSourceUrl,
                headersJson = encodeHeaders(headers),
            ) { json ->
                if (continuation.isActive) {
                    continuation.resume(decodeReferencePayload(json))
                }
            }
            continuation.invokeOnCancellation {
                bridge.cancelEmbeddedSubtitleReferenceLoad()
            }
        }
        return payload
    }

    private fun decodeReferencePayload(json: String): EmbeddedReferenceLoad {
        if (json.isBlank()) return EmbeddedReferenceLoad(reason = "empty_payload")
        return runCatching {
            referenceJson.decodeFromString<EmbeddedReferencePayload>(json)
        }.getOrNull()?.let { payload ->
            val references = payload.tracks.map { track ->
                AutoSyncReferenceTimeline(
                    key = track.key,
                    language = track.language,
                    label = track.label,
                    cues = track.cues.map { cue ->
                        SubtitleSyncCue(
                            startTimeMs = cue.startMs,
                            endTimeMs = cue.endMs.coerceAtLeast(cue.startMs + 1L),
                            text = cue.text.orEmpty(),
                        )
                    }.sortedBy { it.startTimeMs },
                    sampled = track.sampled,
                )
            }.filter { it.cues.size >= MIN_REFERENCE_CUES }
            EmbeddedReferenceLoad(
                references = references,
                reason = when {
                    payload.reason != null && payload.reason != "ok" -> payload.reason
                    payload.tracks.isEmpty() -> "no_references"
                    references.isEmpty() -> "references_below_min_cues"
                    else -> "ok"
                },
                subtitleStreams = payload.subtitleStreams,
                candidateStreams = payload.candidateStreams,
                packetsRead = payload.packetsRead,
                reachedEof = payload.reachedEof,
            )
        } ?: EmbeddedReferenceLoad(reason = "decode_failed")
    }

    @OptIn(ExperimentalForeignApi::class)
    private fun writeRetimedSubtitle(text: String): String? {
        val directory = NSTemporaryDirectory().trimEnd('/') + "/nuvio-autosync"
        val created = NSFileManager.defaultManager.createDirectoryAtPath(
            directory,
            withIntermediateDirectories = true,
            attributes = null,
            error = null,
        )
        if (!created) return null
        val extension = when {
            text.trimStart().startsWith("WEBVTT") -> "vtt"
            text.contains("[Events]", ignoreCase = true) -> "ass"
            else -> "srt"
        }
        val path = "$directory/${text.hashCode().toUInt().toString(16)}.$extension"
        val wrote = NSString.create(string = text).writeToFile(
            path,
            atomically = true,
            encoding = NSUTF8StringEncoding,
            error = null,
        )
        return path.takeIf { wrote }
    }

    private suspend fun showManualFallback(token: Long, sourceUrl: String) {
        if (
            !shouldShowManualFallbackNotice(
                capturedToken = token,
                currentToken = operationToken,
                lastNotifiedSourceUrl = manualFallbackNoticeSourceUrl,
                currentSourceUrl = sourceUrl,
            )
        ) {
            return
        }
        manualFallbackNoticeSourceUrl = sourceUrl
        NuvioToastController.show(getString(Res.string.autosync_toast_manual_fallback))
    }

    private fun encodeHeaders(headers: Map<String, String>): String? {
        val sanitized = sanitizePlaybackHeaders(headers)
        if (sanitized.isEmpty()) return null
        return runCatching { Json.encodeToString(sanitized) }.getOrNull()
    }

    private fun isStrongMatch(match: AutoSyncReferenceMatch): Boolean =
        match.score >= STRONG_MATCH_SCORE &&
            match.timeline.targetCoverage >= STRONG_TARGET_COVERAGE &&
            match.timeline.referenceCoverage >= STRONG_REFERENCE_COVERAGE

    private data class CandidateTimeline(
        val candidate: AutoSyncSubtitleCandidate,
        val body: String,
        val cues: List<SubtitleSyncCue>,
    )

    private data class EmbeddedReferenceLoad(
        val references: List<AutoSyncReferenceTimeline> = emptyList(),
        val reason: String,
        val subtitleStreams: Int = 0,
        val candidateStreams: Int = 0,
        val packetsRead: Long = 0L,
        val reachedEof: Boolean = false,
    )

    @Serializable
    private data class EmbeddedReferencePayload(
        val tracks: List<EmbeddedReferenceTrack> = emptyList(),
        val reason: String? = null,
        val subtitleStreams: Int = 0,
        val candidateStreams: Int = 0,
        val packetsRead: Long = 0L,
        val reachedEof: Boolean = false,
    )

    @Serializable
    private data class EmbeddedReferenceTrack(
        val key: String,
        val language: String? = null,
        val label: String? = null,
        val sampled: Boolean = false,
        val cues: List<EmbeddedReferenceCue> = emptyList(),
    )

    @Serializable
    private data class EmbeddedReferenceCue(
        val startMs: Long,
        val endMs: Long,
        val text: String? = null,
    )

    private companion object {
        const val MAX_ALTERNATIVES = 3
        const val MAX_ADDON_REFERENCES = 4
        const val MIN_REFERENCE_CUES = 4
        const val CANDIDATE_TIMEOUT_MS = 3_500L
        const val STRONG_MATCH_SCORE = 0.92
        const val STRONG_TARGET_COVERAGE = 0.98
        const val STRONG_REFERENCE_COVERAGE = 0.90

        val referenceJson = Json {
            ignoreUnknownKeys = true
            isLenient = true
        }
    }
}
