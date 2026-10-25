package com.nuvio.app.features.autosync

/**
 * A text subtitle can be used as a reference only when the app has proof that it belongs to the
 * same episode and an identifiable release version as the video. Timing similarity by itself is
 * never treated as proof that a subtitle matches the playing video.
 */
internal object AutoSyncAddonReferencePolicy {
    internal data class RankedReference(
        val candidate: AutoSyncSubtitleCandidate,
        val versionEvidence: Set<String>,
        val sameAddon: Boolean,
        val exactRelease: Boolean,
    )

    fun rankTrustedCandidates(
        selected: AutoSyncSubtitleCandidate,
        candidates: List<AutoSyncSubtitleCandidate>,
        videoSourceUrl: String,
    ): List<RankedReference> {
        val videoFileName = videoSourceUrl
            .substringBefore('?')
            .substringBefore('#')
            .substringAfterLast('/')
        val videoRelease = releaseFacts(videoFileName)
        if (videoRelease.coreTokens.isEmpty()) return emptyList()

        return candidates
            .asSequence()
            .filter { it.url.isNotBlank() && it.url != selected.url }
            .filter { sameEpisode(selected, it, videoSourceUrl) }
            .mapNotNull { candidate ->
                val candidateName = candidate.sourceName ?: candidate.name ?: return@mapNotNull null
                val trusted = trustedReleaseMatch(
                    reference = releaseFacts(candidateName),
                    video = videoRelease,
                ) ?: return@mapNotNull null
                RankedReference(
                    candidate = candidate,
                    versionEvidence = trusted.evidence,
                    sameAddon = selected.addonName?.equals(
                        candidate.addonName,
                        ignoreCase = true,
                    ) == true,
                    exactRelease = trusted.exact,
                )
            }
            .sortedWith(
                compareByDescending<RankedReference> { it.exactRelease }
                    .thenByDescending { it.versionEvidence.size }
                    .thenByDescending { it.sameAddon }
                    .thenByDescending {
                        !it.candidate.language.equals(selected.language, ignoreCase = true)
                    }
                    .thenBy { it.candidate.language }
                    .thenBy { it.candidate.url },
            )
            .toList()
    }

    /**
     * Last-resort references for sources without readable embedded subtitles. Candidate names
     * are not required to expose a release identity because many debrid and HLS URLs are opaque,
     * but the subtitle must still belong to the same requested video and episode. Timing
     * confidence is enforced by [AutoSyncReferenceMatcher] before anything is applied.
     */
    fun rankTimingOnlyCandidates(
        selected: AutoSyncSubtitleCandidate,
        candidates: List<AutoSyncSubtitleCandidate>,
        videoSourceUrl: String,
    ): List<RankedReference> {
        if (selected.videoContextKey.isNullOrBlank()) return emptyList()
        val videoRelease = releaseFacts(videoFileName(videoSourceUrl))

        return candidates
            .asSequence()
            .filter { it.url.isNotBlank() && it.url != selected.url }
            .filter { candidate ->
                candidate.videoContextKey == selected.videoContextKey &&
                    sameEpisode(selected, candidate, videoSourceUrl)
            }
            .map { candidate ->
                val release = (candidate.sourceName ?: candidate.name)
                    ?.let(::releaseFacts)
                val trusted = release?.let { trustedReleaseMatch(it, videoRelease) }
                RankedReference(
                    candidate = candidate,
                    versionEvidence = trusted?.evidence ?: emptySet(),
                    sameAddon = selected.addonName?.equals(
                        candidate.addonName,
                        ignoreCase = true,
                    ) == true,
                    exactRelease = trusted?.exact == true,
                )
            }
            .sortedWith(
                compareByDescending<RankedReference> { it.sameAddon }
                    .thenByDescending { it.exactRelease }
                    .thenByDescending { it.versionEvidence.size }
                    .thenByDescending {
                        selected.language.isNotBlank() &&
                            it.candidate.language.equals(selected.language, ignoreCase = true)
                    }
                    .thenBy { it.candidate.language }
                    .thenBy { it.candidate.url },
            )
            .toList()
    }

    /** The fallback applies only when no embedded video-timed reference exists. */
    fun shouldUseTextFallback(
        embeddedReferences: List<AutoSyncReferenceTimeline>,
        rankedReferences: List<RankedReference>,
    ): Boolean = embeddedReferences.isEmpty() && rankedReferences.isNotEmpty()

    fun shouldUseTimingOnlyFallback(
        embeddedReferences: List<AutoSyncReferenceTimeline>,
        trustedReferences: List<RankedReference>,
        timingOnlyReferences: List<RankedReference>,
    ): Boolean =
        embeddedReferences.isEmpty() &&
            trustedReferences.isEmpty() &&
            timingOnlyReferences.isNotEmpty()

    private fun sameEpisode(
        selected: AutoSyncSubtitleCandidate,
        candidate: AutoSyncSubtitleCandidate,
        videoSourceUrl: String,
    ): Boolean {
        val context = selected.videoContextKey?.trim().orEmpty()
        if (context.isEmpty() || candidate.videoContextKey != context) return false
        val metadataMatches = when {
            selected.seasonNumber != null || selected.episodeNumber != null ->
                selected.seasonNumber != null &&
                    selected.episodeNumber != null &&
                    selected.seasonNumber == candidate.seasonNumber &&
                    selected.episodeNumber == candidate.episodeNumber
            else -> candidate.seasonNumber == null && candidate.episodeNumber == null
        }
        if (!metadataMatches) return false

        val expected = EpisodeMarker(
            season = selected.seasonNumber,
            episode = selected.episodeNumber,
        ).takeIf { it.season != null && it.episode != null }
        val videoFileName = videoFileName(videoSourceUrl)
        return markersAreCompatible(
            value = selected.sourceName ?: selected.name,
            expected = expected,
        ) && markersAreCompatible(
            value = candidate.sourceName ?: candidate.name,
            expected = expected,
        ) && markersAreCompatible(
            value = videoFileName,
            expected = expected,
        )
    }

    private fun videoFileName(videoSourceUrl: String): String =
        videoSourceUrl
            .substringBefore('?')
            .substringBefore('#')
            .substringAfterLast('/')

    private fun trustedReleaseMatch(
        reference: ReleaseFacts,
        video: ReleaseFacts,
    ): ReleaseMatch? {
        if (contradicts(reference.sourceMarkers, video.sourceMarkers)) return null
        if (contradicts(reference.cutMarkers, video.cutMarkers)) return null
        if (contradicts(reference.groupNames, video.groupNames)) return null
        if (contradicts(reference.providerMarkers, video.providerMarkers)) return null

        if (
            reference.coreTokens.isNotEmpty() &&
            reference.coreTokens == video.coreTokens &&
            reference.groupNames == video.groupNames &&
            reference.sourceMarkers == video.sourceMarkers &&
            reference.cutMarkers == video.cutMarkers &&
            reference.providerMarkers == video.providerMarkers
        ) {
            return ReleaseMatch(exact = true, evidence = linkedSetOf("exact-release"))
        }

        val commonGroups = reference.groupNames intersect video.groupNames
        val commonSources = reference.sourceMarkers intersect video.sourceMarkers
        val knownReleaseTuple =
            commonGroups.isNotEmpty() &&
                commonSources.isNotEmpty() &&
                reference.cutMarkers == video.cutMarkers &&
                reference.providerMarkers == video.providerMarkers
        if (!knownReleaseTuple) return null

        return ReleaseMatch(
            exact = false,
            evidence = buildSet {
                add("release-group")
                commonSources.forEach { add("source-$it") }
                reference.cutMarkers.forEach { add("cut-$it") }
                reference.providerMarkers.forEach { add("provider-$it") }
            },
        )
    }

    private fun releaseFacts(rawValue: String): ReleaseFacts {
        val decoded = decodePercentEncodedText(rawValue)
        var stem = decoded
            .substringBefore('?')
            .substringBefore('#')
            .substringAfterLast('/')
            .lowercase()
        val extension = stem.substringAfterLast('.', missingDelimiterValue = "")
        if (extension in MEDIA_EXTENSIONS) {
            stem = stem.substringBeforeLast('.')
        }
        while (true) {
            val trimmed = stem.replace(SUBTITLE_SUFFIX, "")
            if (trimmed == stem) break
            stem = trimmed
        }

        val groups = HashSet<String>()
        HYPHEN_GROUP.find(stem)?.groupValues?.getOrNull(1)
            ?.takeIf { value -> value.any(Char::isLetter) }
            ?.takeUnless { it in RESERVED_GROUP_SUFFIXES }
            ?.let(groups::add)
        BRACKET_GROUP.find(stem)?.groupValues?.getOrNull(1)
            ?.let(groups::add)
        val coreStem = stem
            .replace(HYPHEN_GROUP, "")
            .replace(BRACKET_GROUP, "")
        val normalized = coreStem
            .replace('_', ' ')
            .replace('.', ' ')
            .replace('-', ' ')
            .replace('\'', ' ')
        return ReleaseFacts(
            coreTokens = coreStem
                .split(Regex("""[^a-z0-9]+"""))
                .filter { it.isNotBlank() && it !in CORE_STOP_TOKENS },
            groupNames = groups,
            sourceMarkers = markerSet(normalized, SOURCE_MARKERS),
            cutMarkers = markerSet(normalized, CUT_MARKERS),
            providerMarkers = markerSet(normalized, PROVIDER_MARKERS),
        )
    }

    private fun markerSet(value: String, markers: List<EvidenceMarker>): Set<String> =
        markers
            .filter { marker -> marker.pattern.containsMatchIn(value) }
            .mapTo(linkedSetOf()) { it.key }

    private fun contradicts(first: Set<String>, second: Set<String>): Boolean =
        first.isNotEmpty() && second.isNotEmpty() && (first intersect second).isEmpty()

    private fun decodePercentEncodedText(value: String): String {
        val source = value.take(MAX_DECODE_LENGTH)
        val bytes = ArrayList<Byte>(source.length)
        var index = 0
        while (index < source.length) {
            if (
                source[index] == '%' &&
                index + 2 < source.length
            ) {
                val high = source[index + 1].hexDigitOrNull()
                val low = source[index + 2].hexDigitOrNull()
                if (high != null && low != null) {
                    bytes += ((high shl 4) + low).toByte()
                    index += 3
                    continue
                }
            }
            source[index].toString().encodeToByteArray().forEach(bytes::add)
            index++
        }
        return bytes.toByteArray().decodeToString()
    }

    private fun Char.hexDigitOrNull(): Int? = when (this) {
        in '0'..'9' -> this - '0'
        in 'a'..'f' -> this - 'a' + 10
        in 'A'..'F' -> this - 'A' + 10
        else -> null
    }

    private data class EvidenceMarker(val key: String, val pattern: Regex)
    private data class ReleaseMatch(val exact: Boolean, val evidence: Set<String>)
    private data class ReleaseFacts(
        val coreTokens: List<String>,
        val groupNames: Set<String>,
        val sourceMarkers: Set<String>,
        val cutMarkers: Set<String>,
        val providerMarkers: Set<String>,
    )
    private data class EpisodeMarker(val season: Int?, val episode: Int?)

    private fun markersAreCompatible(value: String?, expected: EpisodeMarker?): Boolean {
        if (value.isNullOrBlank()) return true
        val decoded = decodePercentEncodedText(value)
        val markers = EPISODE_MARKERS
            .flatMap { pattern -> pattern.findAll(decoded).map { it.groupValues }.toList() }
            .map { groups ->
                EpisodeMarker(
                    season = groups.getOrNull(1)?.toIntOrNull(),
                    episode = groups.getOrNull(2)?.toIntOrNull(),
                )
            }
        if (markers.isEmpty()) return true
        return expected != null && markers.all { it == expected }
    }

    private val EPISODE_MARKERS = listOf(
        Regex("""(?i)\bs(\d{1,3})e(\d{1,4})\b"""),
        Regex("""(?i)\b(\d{1,3})x(\d{1,4})\b"""),
    )

    private val HYPHEN_GROUP = Regex("""-([a-z0-9]{2,24})$""")
    private val BRACKET_GROUP = Regex("""\[([a-z0-9]{2,24})]$""")
    private val MEDIA_EXTENSIONS = setOf("mkv", "mp4", "m4v", "avi", "ts", "m2ts", "webm", "srt", "vtt", "ass", "ssa")
    private val RESERVED_GROUP_SUFFIXES = setOf("dl", "rip")
    private val CORE_STOP_TOKENS = setOf(
        "forced",
        "sdh",
        "sub",
        "subs",
        "subtitle",
        "subtitles",
        "ar",
        "ara",
        "arabic",
        "en",
        "eng",
        "english",
        "fr",
        "fre",
        "french",
        "es",
        "spa",
        "spanish",
        "de",
        "ger",
        "german",
        "it",
        "ita",
        "italian",
        "ja",
        "jpn",
        "japanese",
        "ko",
        "kor",
        "korean",
        "zh",
        "chi",
        "chinese",
        "pt",
        "por",
        "portuguese",
        "ru",
        "rus",
        "russian",
    )
    private val SUBTITLE_SUFFIX = Regex(
        """[._ ](?:forced|sdh|subs?|subtitles?|ar|ara|arabic|en|eng|english|fr|fre|french|es|spa|spanish|de|ger|german|it|ita|italian|ja|jpn|japanese|ko|kor|korean|zh|chi|chinese|pt|por|portuguese|ru|rus|russian)$""",
        RegexOption.IGNORE_CASE,
    )

    private val SOURCE_MARKERS = listOf(
        EvidenceMarker("web-dl", Regex("""\bweb\s*dl\b""")),
        EvidenceMarker("webrip", Regex("""\bweb\s*rip\b""")),
        EvidenceMarker("bluray", Regex("""\bblu\s*ray\b|\bbdrip\b|\bbrip\b""")),
        EvidenceMarker("hdtv", Regex("""\bhdtv\b""")),
        EvidenceMarker("dvdrip", Regex("""\bdvd\s*rip\b""")),
    )
    private val CUT_MARKERS = listOf(
        EvidenceMarker("extended", Regex("""\bextended\b""")),
        EvidenceMarker("uncut", Regex("""\buncut\b""")),
        EvidenceMarker("theatrical", Regex("""\btheatrical\b""")),
        EvidenceMarker("directors-cut", Regex("""\bdirector s? cut\b""")),
        EvidenceMarker("fanedit", Regex("""\bfan\s*edit\b""")),
    )
    private val PROVIDER_MARKERS = listOf(
        EvidenceMarker("amzn", Regex("""\bamzn\b""")),
        EvidenceMarker("nf", Regex("""\bnf\b""")),
        EvidenceMarker("dsnp", Regex("""\bdsnp\b""")),
        EvidenceMarker("hmax", Regex("""\bhmax\b""")),
        EvidenceMarker("atvp", Regex("""\batvp\b""")),
        EvidenceMarker("pcok", Regex("""\bpcok\b""")),
        EvidenceMarker("itunes", Regex("""\bitunes\b""")),
    )
    private const val MAX_DECODE_LENGTH = 1_024
}
