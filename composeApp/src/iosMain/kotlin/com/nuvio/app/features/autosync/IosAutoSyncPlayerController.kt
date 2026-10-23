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
import nuvio.composeapp.generated.resources.autosync_toast_audio_fallback
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
    private var originalSubtitleUrl: String? = null
    private var latestBody: String? = null

    init {
        com.nuvio.app.features.downloads.DownloadPlaybackGuard.activePlayers.add(this)
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
        com.nuvio.app.features.downloads.DownloadPlaybackGuard.activePlayers.remove(this)
        cancel()
        AutoSyncDiagnostics.restoreOriginal = null
        AutoSyncDiagnostics.runAgain = null
        AutoSyncDiagnostics.exportCurrent = null
        AutoSyncDiagnostics.current.value = AutoSyncDiagnostic()
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
        SubtitleEnhancements.ensureLoaded()
        val options = SubtitleEnhancements.state.value
        val token = operationToken
        val source = sourceUrl()
        latestBody = null
        AutoSyncDiagnostics.exportCurrent = null
        AutoSyncDiagnostics.restoreOriginal = { cancel(); delegate.setSubtitleUri(url); delegate.setSubtitleDelayMs(0) }
        AutoSyncDiagnostics.runAgain = { start(url, AutoSyncCandidateScope.SELECTED_ONLY) }
        job = scope.launch {
            val body = loadCandidateBody(url) ?: return@launch
            attachEnhanced(body, options, token, source)
        }
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
        bridge.cancelAudioSpeechReferenceAnalysis()
        bridge.cancelAudioWordRecognition()
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
        SubtitleEnhancements.ensureLoaded()
        val options = SubtitleEnhancements.state.value
        val currentSourceUrl = sourceUrl()
        originalSubtitleUrl = url
        latestBody = null
        AutoSyncDiagnostics.current.value = AutoSyncDiagnostic(
            state = "بدأ فحص الترجمة والمزامنة",
            progressPercent = 0,
            outcome = AutoSyncOutcome.RUNNING,
        )
        AutoSyncDiagnostics.restoreOriginal = {
            cancel()
            AutoSyncSyncedSubtitle.clear()
            delegate.setSubtitleUri(url)
            delegate.setSubtitleDelayMs(0)
            AutoSyncDiagnostics.current.value = AutoSyncDiagnostic(
                state = "تمت استعادة الترجمة الأصلية",
                progressPercent = 100,
                outcome = AutoSyncOutcome.SUCCESS,
            )
        }
        AutoSyncDiagnostics.runAgain = { start(url, AutoSyncCandidateScope.SELECTED_ONLY) }
        AutoSyncDiagnostics.exportCurrent = null
        // Keep useful subtitles visible while the embedded reference is inspected.
        attachOriginal(url)
        if (
            !AutoSyncPreferencesRepository.preferredSubtitleAutoSyncOnStart.value ||
            !supportsAutoSyncMediaSource(currentSourceUrl)
        ) {
            job = scope.launch {
                val body = loadCandidateBody(url)
                if (body == null) {
                    finishFailure(token, "تعذر تحميل ملف الترجمة")
                    return@launch
                }
                if (token != operationToken) return@launch
                attachEnhanced(body, options, token, currentSourceUrl)
                AutoSyncDiagnostics.current.value = AutoSyncDiagnostic(
                    state = "تم اختيار الترجمة؛ المزامنة التلقائية غير مفعلة",
                    progressPercent = 100,
                    outcome = AutoSyncOutcome.DISABLED,
                )
            }
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
                updateProgress(token, 20, "تم تحميل الترجمة؛ جارٍ قراءة توقيت الحلقة")
                if (selectedBody != null && token == operationToken) {
                    attachEnhanced(selectedBody, options, token, currentSourceUrl)
                }
                val referenceLoad = referencesJob.await()
                val references = referenceLoad.references
                updateProgress(token, 40, "تم فحص مراجع الحلقة؛ جارٍ مطابقة التوقيت", references.size)
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
                        AutoSyncConsensus.find(selectedCues, references, options.consensus, options.segmentedSync)
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
                val originalCandidate = bestCandidate

                var addonReferenceCount = 0
                if (best == null && selectedCandidate != null && selectedCues.isNotEmpty()) {
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
                        updateProgress(token, 55, "جارٍ مقارنة مصادر الترجمة")
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
                    candidateScope == AutoSyncCandidateScope.STARTUP_SEARCH &&
                    options.chooseBestSubtitle
                ) {
                    updateProgress(token, 60, "جارٍ اختيار أفضل ترجمة عربية")
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
                                    AutoSyncConsensus.find(alternative.cues, references, options.consensus, options.segmentedSync)
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

                var asrReferenceCount = 0
                if (
                    AutoSyncPreferencesRepository.audioFallbackEnabled.value &&
                    selectedCues.isNotEmpty() &&
                    (best == null || !isStrongMatch(best))
                ) {
                    updateProgress(token, 70, "جارٍ تحليل كلمات الصوت")
                    NuvioToastController.show(getString(Res.string.autosync_toast_audio_fallback))
                    val asrMatch = findAudioWordMatch(
                        target = selectedCues,
                        sourceUrl = currentSourceUrl,
                        headers = currentSourceHeaders,
                        embeddedReferences = references,
                        selectedLanguage = selectedLanguage,
                    )
                    if (asrMatch != null && (best == null || asrMatch.score > best.score)) {
                        asrReferenceCount = asrMatch.reference.cues.size
                        best = asrMatch
                        bestCandidate = originalCandidate
                    }
                }

                var audioReferenceCount = 0
                if (
                    AutoSyncPreferencesRepository.audioFallbackEnabled.value &&
                    selectedCues.isNotEmpty() &&
                    (best == null || !isStrongMatch(best))
                ) {
                    updateProgress(token, 85, "جارٍ التحقق من نشاط الصوت")
                    val audioMatch = findAudioSpeechMatch(
                        target = selectedCues,
                        sourceUrl = currentSourceUrl,
                        headers = currentSourceHeaders,
                    )
                    if (audioMatch != null && (best == null || audioMatch.score > best.score)) {
                        audioReferenceCount = audioMatch.reference.cues.size
                        best = audioMatch
                        bestCandidate = originalCandidate
                    }
                }

                currentCoroutineContext().ensureActive()
                InAppLogger.info(
                    "AutoSync/iOS",
                    "selectedCues=${selectedCues.size} language=${selectedLanguage ?: "none"} " +
                        "best=${best?.score ?: -1.0} " +
                        "alignment=${best?.timeline?.alignmentSource ?: "none"} " +
                        "offsetMs=${best?.timeline?.alignmentInterceptMs ?: 0.0} " +
                        "addonReferences=$addonReferenceCount " +
                        "asrReferences=$asrReferenceCount " +
                        "audioReferences=$audioReferenceCount",
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

                updateProgress(token, 92, "تم العثور على مطابقة؛ جارٍ تطبيق التوقيت")
                val tolerance = AutoSyncPreferencesRepository.syncToleranceMs.value
                val keepOriginalTiming = candidate.candidate.url == url &&
                    tolerance > 0 && !options.dualSubtitles &&
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
                    attachEnhanced(candidate.body, options, token, currentSourceUrl)
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
                    var output = retimed
                    if (options.dualSubtitles) {
                        val secondary = candidates.firstOrNull {
                            it.url != candidate.candidate.url &&
                                SubtitleLanguageMatching.matchesLanguageCode(it.language, options.secondaryLanguage)
                        }?.let { loadCandidate(it) }
                        if (secondary != null) {
                            val primaryCues = AutoSyncSubtitleCueParser.parse(retimed, candidate.candidate.url)
                            val secondaryMatch = withContext(Dispatchers.Default) {
                                AutoSyncReferenceMatcher.findBest(secondary.cues, listOf(
                                    AutoSyncReferenceTimeline("primary", candidate.candidate.language, null, primaryCues),
                                ))
                            }
                            if (secondaryMatch != null) {
                                val secondaryBody = AutoSyncSubtitleTextRetimer.retime(secondary.body, secondaryMatch.timeline)
                                if (secondaryBody != null) output = SubtitleTextEnhancer.export(
                                    SubtitleDualComposer.combine(primaryCues, AutoSyncSubtitleCueParser.parse(secondaryBody, secondary.candidate.url)), "srt",
                                )
                            }
                        }
                    }
                    output = withContext(Dispatchers.Default) { SubtitleTextEnhancer.process(output, options) }
                    currentCoroutineContext().ensureActive()
                    if (token != operationToken || sourceUrl() != currentSourceUrl) return@launch
                    val subtitlePath = writeRetimedSubtitle(output)
                    latestBody = output
                    AutoSyncDiagnostics.exportCurrent = { latestBody?.let { SubtitleExportIos.export(it) } }
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
                AutoSyncDiagnostics.current.value = AutoSyncDiagnostic(
                    state = "تمت المزامنة بنجاح",
                    progressPercent = 100,
                    outcome = AutoSyncOutcome.SUCCESS,
                    method = when {
                        match.reference.key.startsWith("asr:") -> "مطابقة كلمات الصوت"
                        match.reference.key.startsWith("audio:") -> "تحليل نشاط الصوت"
                        match.timeline.alignmentSource == "piecewise-text" -> "مطابقة المقاطع"
                        else -> "مطابقة ترجمة مرجعية"
                    },
                    score = match.score.coerceIn(0.0, 1.0),
                    offsetMs = match.timeline.alignmentInterceptMs.toLong(),
                    scale = match.timeline.alignmentScale,
                    coverage = match.timeline.targetCoverage,
                    references = references.size + addonReferenceCount,
                )
                if (options.keepHistory) SubtitleExportIos.record(currentSourceUrl, candidate.candidate.url, AutoSyncDiagnostics.current.value)
                if (options.exportSynced) latestBody?.let { SubtitleExportIos.save(it) }
                NuvioToastController.show("تمت مزامنة الترجمة بنجاح", durationMillis = 4_000L)
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
        val matches = mutableListOf<AutoSyncReferenceMatch>()
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
            matches += match
        }
        return if (SubtitleEnhancements.state.value.consensus) AutoSyncConsensus.choose(matches) else matches.maxByOrNull { it.score }
    }

    /**
     * Strong audio fallback: timestamps words recognized from the actual soundtrack, then matches
     * those words against the selected subtitle or an English reference. Translated subtitles are
     * bridged to the English reference by their cue timing before the final affine mapping is
     * applied. Any failure returns null and leaves the existing speech-activity fallback intact.
     */
    private suspend fun findAudioWordMatch(
        target: List<SubtitleSyncCue>,
        sourceUrl: String,
        headers: Map<String, String>,
        embeddedReferences: List<AutoSyncReferenceTimeline>,
        selectedLanguage: String?,
    ): AutoSyncReferenceMatch? {
        val references = mutableListOf<AutoSyncReferenceTimeline>()
        references += AutoSyncReferenceTimeline(
            key = "selected",
            language = selectedLanguage,
            label = "Selected subtitle",
            cues = target,
        )
        references += embeddedReferences.filter { reference ->
            reference.cues.any { it.text.isNotBlank() }
        }
        val addonCandidates = candidates
            .asSequence()
            .filter { it.url.isNotBlank() && SubtitleLanguageMatching.matchesLanguageCode(
                it.language, SubtitleEnhancements.state.value.speechLocale.substringBefore('-'),
            ) }
            .distinctBy { it.url }
            .take(MAX_ASR_ADDON_REFERENCES)
            .toList()
        val addonReferences = coroutineScope {
            addonCandidates.map { candidate ->
                async {
                    withTimeoutOrNull(CANDIDATE_TIMEOUT_MS) { loadCandidate(candidate) }
                }
            }.awaitAll().filterNotNull().map { timeline ->
                AutoSyncReferenceTimeline(
                    key = "asr-addon:${timeline.candidate.url}",
                    language = timeline.candidate.language,
                    label = timeline.candidate.sourceName ?: timeline.candidate.name,
                    cues = timeline.cues,
                )
            }
        }
        references += addonReferences
        val uniqueReferences = references
            .filter { it.cues.size >= MIN_REFERENCE_CUES }
            .distinctBy { it.key }
        if (uniqueReferences.isEmpty()) return null

        val payload = suspendCancellableCoroutine { continuation ->
            bridge.recognizeAudioWords(
                sourceUrl = sourceUrl,
                headersJson = encodeHeaders(headers),
                locale = SubtitleEnhancements.state.value.speechLocale,
                onDeviceOnly = SubtitleEnhancements.state.value.onDeviceOnly,
                analysisMinutes = SubtitleEnhancements.state.value.analysisMinutes,
                sampleStartMs = 0L,
            ) { json ->
                if (continuation.isActive) {
                    continuation.resume(decodeAudioWordPayload(json))
                }
            }
            continuation.invokeOnCancellation {
                bridge.cancelAudioWordRecognition()
            }
        }
        val words = payload.words.map { word ->
            AutoSyncHeardWord(
                timeMs = word.startMs,
                text = word.text,
                segment = word.segment,
            )
        }
        InAppLogger.info(
            "AutoSync/iOS",
            "speech words=${words.size} reason=${payload.reason} " +
                "analyzedMs=${payload.analyzedMs} packets=${payload.packetsRead} " +
                "references=${uniqueReferences.size}",
        )
        if (words.size < MIN_ASR_WORDS) return null

        var bestFit: AutoSyncWordFit? = null
        var bestReference: AutoSyncReferenceTimeline? = null
        var bestScale = 1.0
        var bestShiftMs = 0.0
        var bestRank = Double.NEGATIVE_INFINITY
        for (reference in uniqueReferences) {
            val fit = withContext(Dispatchers.Default) {
                AutoSyncWordMatcher.confidentMapping(words, reference.cues)
            } ?: continue
            if (reference.key == "selected") {
                val rank = fit.score
                if (rank > bestRank) {
                    bestFit = fit
                    bestReference = reference
                    bestScale = fit.scale
                    bestShiftMs = fit.shiftMs
                    bestRank = rank
                }
                continue
            }
            val bridge = withContext(Dispatchers.Default) {
                AutoSyncReferenceMatcher.findBest(
                    target = target,
                    references = listOf(reference),
                )
            } ?: continue
            val bridgeScale = bridge.timeline.alignmentScale
            val bridgeShiftMs = bridge.timeline.alignmentInterceptMs
            val scale = fit.scale * bridgeScale
            val shiftMs = fit.scale * bridgeShiftMs + fit.shiftMs
            val rank = fit.score * bridge.timeline.targetCoverage
            if (rank > bestRank) {
                bestFit = fit
                bestReference = reference
                bestScale = scale
                bestShiftMs = shiftMs
                bestRank = rank
            }
        }
        val fit = bestFit ?: return null
        val reference = bestReference ?: return null
        val timeline = withContext(Dispatchers.Default) {
            AutoSyncWordMatcher.buildAffineTimeline(
                target = target,
                scale = bestScale,
                shiftMs = bestShiftMs,
                score = fit.score,
                source = "asr-word-anchor",
            )
        }
        InAppLogger.info(
            "AutoSync/iOS",
            "asr match reference=${reference.key} words=${words.size} " +
                "segments=${fit.segments} score=${fit.score} " +
                "scale=$bestScale offsetMs=$bestShiftMs spanMs=${fit.spanMs}",
        )
        return AutoSyncReferenceMatch(
            reference = reference.copy(key = "asr:${reference.key}"),
            timeline = timeline,
            score = fit.score.coerceIn(0.0, 1.0),
        )
    }

    private fun decodeAudioWordPayload(json: String): AudioWordPayload {
        if (json.isBlank()) return AudioWordPayload(reason = "empty_payload")
        return runCatching {
            referenceJson.decodeFromString<AudioWordPayload>(json)
        }.getOrNull() ?: AudioWordPayload(reason = "decode_failed")
    }

    private fun isEnglishLanguage(language: String?): Boolean {
        val value = language?.trim()?.lowercase().orEmpty()
        return value == "en" ||
            value == "eng" ||
            value.startsWith("en-") ||
            value.startsWith("en_") ||
            value.contains("english")
    }

    private suspend fun findAudioSpeechMatch(
        target: List<SubtitleSyncCue>,
        sourceUrl: String,
        headers: Map<String, String>,
    ): AutoSyncReferenceMatch? {
        val payload = suspendCancellableCoroutine { continuation ->
            bridge.analyzeAudioSpeechReferences(
                sourceUrl = sourceUrl,
                headersJson = encodeHeaders(headers),
            ) { json ->
                if (continuation.isActive) {
                    continuation.resume(decodeAudioSpeechPayload(json))
                }
            }
            continuation.invokeOnCancellation {
                bridge.cancelAudioSpeechReferenceAnalysis()
            }
        }
        val cues = payload.cues.map { cue ->
            SubtitleSyncCue(
                startTimeMs = cue.startMs,
                endTimeMs = cue.endMs.coerceAtLeast(cue.startMs + 1L),
                text = "",
            )
        }.sortedBy { it.startTimeMs }
        InAppLogger.info(
            "AutoSync/iOS",
            "audio analysis reason=${payload.reason} cues=${cues.size} " +
                "analyzedMs=${payload.analyzedMs} packets=${payload.packetsRead}",
        )
        if (cues.size < MIN_AUDIO_REFERENCE_CUES) return null
        val reference = AutoSyncReferenceTimeline(
            key = "audio:speech-activity",
            language = null,
            label = "Audio speech activity",
            cues = cues,
            sampled = true,
        )
        val match = withContext(Dispatchers.Default) {
            AutoSyncReferenceMatcher.findBest(target, listOf(reference))
        } ?: return null
        return match.takeIf(::isStrongMatch)
    }

    private fun decodeAudioSpeechPayload(json: String): AudioSpeechPayload {
        if (json.isBlank()) return AudioSpeechPayload(reason = "empty_payload")
        return runCatching {
            referenceJson.decodeFromString<AudioSpeechPayload>(json)
        }.getOrNull() ?: AudioSpeechPayload(reason = "decode_failed")
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
        if (token == operationToken) finishFailure(token, "فشلت المزامنة؛ التوقيت الأصلي مستمر")
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
        NuvioToastController.show("فشلت المزامنة؛ الترجمة الأصلية ما زالت تعمل", durationMillis = 5_000L)
    }

    private fun updateProgress(
        token: Long,
        percent: Int,
        state: String,
        references: Int? = null,
    ) {
        if (token != operationToken) return
        val current = AutoSyncDiagnostics.current.value
        AutoSyncDiagnostics.current.value = current.copy(
            state = state,
            progressPercent = maxOf(current.progressPercent, percent.coerceIn(0, 99)),
            outcome = AutoSyncOutcome.RUNNING,
            references = references ?: current.references,
        )
    }

    private fun finishFailure(token: Long, state: String) {
        if (token != operationToken) return
        AutoSyncDiagnostics.current.value = AutoSyncDiagnostic(
            state = state,
            progressPercent = 100,
            outcome = AutoSyncOutcome.FAILURE,
        )
    }

    private fun encodeHeaders(headers: Map<String, String>): String? {
        val sanitized = sanitizePlaybackHeaders(headers)
        if (sanitized.isEmpty()) return null
        return runCatching { Json.encodeToString(sanitized) }.getOrNull()
    }

    private suspend fun attachEnhanced(body: String, options: SubtitleEnhancementOptions, token: Long, source: String) {
        val processed = withContext(Dispatchers.Default) { SubtitleTextEnhancer.process(body, options) }
        if (token != operationToken || sourceUrl() != source) return
        latestBody = processed
        AutoSyncDiagnostics.exportCurrent = { latestBody?.let { SubtitleExportIos.export(it) } }
        if (processed != body) writeRetimedSubtitle(processed)?.let {
            delegate.setSubtitleUri(NSURL.fileURLWithPath(it).absoluteString ?: it)
        }
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

    @Serializable
    private data class AudioSpeechPayload(
        val cues: List<AudioSpeechCue> = emptyList(),
        val reason: String = "unknown",
        val analyzedMs: Long = 0L,
        val cueCount: Int = 0,
        val packetsRead: Long = 0L,
    )

    @Serializable
    private data class AudioSpeechCue(
        val startMs: Long,
        val endMs: Long,
    )

    @Serializable
    private data class AudioWordPayload(
        val words: List<AudioWord> = emptyList(),
        val reason: String = "unknown",
        val analyzedMs: Long = 0L,
        val wordCount: Int = 0,
        val packetsRead: Long = 0L,
    )

    @Serializable
    private data class AudioWord(
        val startMs: Long,
        val endMs: Long = startMs + 1L,
        val text: String,
        val segment: Int = 0,
    )

    private companion object {
        const val MAX_ALTERNATIVES = 6
        const val MAX_ADDON_REFERENCES = 6
        const val MAX_ASR_ADDON_REFERENCES = 6
        const val MIN_REFERENCE_CUES = 4
        const val MIN_AUDIO_REFERENCE_CUES = 8
        const val MIN_ASR_WORDS = 8
        const val CANDIDATE_TIMEOUT_MS = 20_000L
        const val STRONG_MATCH_SCORE = 0.92
        const val STRONG_TARGET_COVERAGE = 0.98
        const val STRONG_REFERENCE_COVERAGE = 0.90

        val referenceJson = Json {
            ignoreUnknownKeys = true
            isLenient = true
        }
    }
}
