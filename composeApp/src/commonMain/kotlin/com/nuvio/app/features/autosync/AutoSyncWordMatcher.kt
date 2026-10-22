package com.nuvio.app.features.autosync

import com.nuvio.app.features.player.SubtitleSyncCue
import kotlin.math.abs
import kotlin.math.roundToLong

internal data class AutoSyncHeardWord(
    val timeMs: Long,
    val text: String,
    val segment: Int,
)

internal data class AutoSyncWordFit(
    val scale: Double,
    val shiftMs: Double,
    val score: Double,
    val runnerUp: Double,
    val segments: Int,
    val spanMs: Long,
    val anchorMs: Long,
)

/**
 * Matches timestamped words heard in the media against a text subtitle reference.
 *
 * Every repeated word votes for a subtitle-to-media offset. Ordered neighbours vote more
 * strongly, and offsets must agree across separate speech segments before a result is accepted.
 */
internal object AutoSyncWordMatcher {
    fun fit(
        words: List<AutoSyncHeardWord>,
        cues: List<SubtitleSyncCue>,
    ): AutoSyncWordFit? {
        if (words.size < MIN_WORDS || cues.size < MIN_CUES) return null
        val subtitleWords = buildSubtitleWords(cues)
        if (subtitleWords.isEmpty()) return null
        val index = subtitleWords
            .withIndex()
            .groupBy { it.value.word }
            .mapValues { entry -> entry.value.map { it.index } }
        val heard = words
            .sortedBy { it.timeMs }
            .mapNotNull { word ->
                val normalized = normalize(word.text)
                normalized.takeIf { it.isNotBlank() }?.let {
                    AutoSyncHeardWord(word.timeMs, it, word.segment)
                }
            }
        val anchors = buildAnchors(heard, subtitleWords, index)
        if (anchors.isEmpty()) return null

        val unit = cluster(anchors, scale = 1.0) ?: return null
        val anchorMs = anchors.map { it.mediaMs }
        val spanMs = anchorMs.maxOrNull()!! - anchorMs.minOrNull()!!
        if (spanMs < MIN_RATE_SPAN_MS) return unit

        var best = unit
        for (scale in RATE_CANDIDATES) {
            val candidate = cluster(anchors, scale) ?: continue
            if (candidate.segments < MIN_RATE_SEGMENTS) continue
            if (candidate.score > best.score * RATE_MARGIN &&
                candidate.score > unit.score * RATE_MARGIN
            ) {
                best = candidate
            }
        }
        return best.takeIf { it.isConfident }
    }

    fun confidentMapping(
        words: List<AutoSyncHeardWord>,
        referenceCues: List<SubtitleSyncCue>,
    ): AutoSyncWordFit? = fit(words, referenceCues)?.takeIf { it.isConfident }

    private fun buildSubtitleWords(cues: List<SubtitleSyncCue>): List<SubtitleWord> {
        val output = ArrayList<SubtitleWord>()
        for (cue in cues.sortedBy { it.startTimeMs }) {
            if (cue.startTimeMs < 0L || cue.endTimeMs <= cue.startTimeMs) continue
            val tokens = tokenize(cue.text)
            if (tokens.isEmpty()) continue
            val durationMs = (cue.endTimeMs - cue.startTimeMs).coerceAtLeast(1L)
            tokens.forEachIndexed { index, word ->
                val timeMs = cue.startTimeMs +
                    (durationMs * index.toDouble() / tokens.size).roundToLong()
                output += SubtitleWord(
                    timeMs = timeMs,
                    word = word,
                    firstInCue = index == 0,
                )
            }
        }
        return output
    }

    private fun buildAnchors(
        heard: List<AutoSyncHeardWord>,
        subtitleWords: List<SubtitleWord>,
        index: Map<String, List<Int>>,
    ): List<Anchor> {
        val output = ArrayList<Anchor>()
        for (heardIndex in heard.indices) {
            val current = heard[heardIndex]
            if (current.text.length < 3 || current.text in STOP_WORDS) continue
            val occurrences = index[current.text] ?: continue
            if (occurrences.size > MAX_OCCURRENCES) continue
            for (subtitleIndex in occurrences) {
                var weight = 1.0 / occurrences.size
                val next = heard.getOrNull(heardIndex + 1)
                if (
                    next != null &&
                    next.segment == current.segment &&
                    subtitleWords.getOrNull(subtitleIndex + 1)?.word == next.text
                ) {
                    weight += 1.0
                }
                val previous = heard.getOrNull(heardIndex - 1)
                if (
                    previous != null &&
                    previous.segment == current.segment &&
                    subtitleWords.getOrNull(subtitleIndex - 1)?.word == previous.text
                ) {
                    weight += 1.0
                }
                output += Anchor(
                    mediaMs = current.timeMs,
                    subtitleMs = subtitleWords[subtitleIndex].timeMs,
                    weight = weight,
                    segment = current.segment,
                    firstInCue = subtitleWords[subtitleIndex].firstInCue,
                )
            }
        }
        return output
    }

    private fun cluster(anchors: List<Anchor>, scale: Double): AutoSyncWordFit? {
        val entries = anchors
            .map { it to (it.mediaMs - scale * it.subtitleMs) }
            .filter { abs(it.second) <= MAX_SHIFT_MS }
            .sortedBy { it.second }
        if (entries.isEmpty()) return null

        var from = 0
        var bestScore = -1.0
        var bestFrom = 0
        var bestTo = 0
        var bestSegments = 0
        for (to in entries.indices) {
            while (entries[to].second - entries[from].second > CLUSTER_WINDOW_MS) from++
            val window = entries.subList(from, to + 1)
            val segments = window.map { it.first.segment }.toSet().size
            val score = window.sumOf { it.first.weight } * minOf(segments, 6) / 6.0
            if (score > bestScore) {
                bestScore = score
                bestFrom = from
                bestTo = to
                bestSegments = segments
            }
        }
        val selected = entries.subList(bestFrom, bestTo + 1)
        val cueStarts = selected
            .filter { it.first.firstInCue }
            .map { it.second.toDouble() }
        val offsets = if (cueStarts.size >= 2) {
            cueStarts
        } else {
            selected.map { it.second.toDouble() }
        }
        val shift = median(offsets)
        val center = shift.roundToLong()
        val far = entries.filter { abs(it.second - center) > RUNNER_UP_EXCLUSION_MS }
        var runnerUp = 0.0
        var runnerFrom = 0
        for (to in far.indices) {
            while (far[to].second - far[runnerFrom].second > CLUSTER_WINDOW_MS) runnerFrom++
            runnerUp = maxOf(
                runnerUp,
                far.subList(runnerFrom, to + 1).sumOf { it.first.weight },
            )
        }
        val times = selected.map { it.first.mediaMs }
        return AutoSyncWordFit(
            scale = scale,
            shiftMs = shift,
            score = bestScore,
            runnerUp = runnerUp,
            segments = bestSegments,
            spanMs = times.maxOrNull()!! - times.minOrNull()!!,
            anchorMs = median(times.map { it.toDouble() }).roundToLong(),
        )
    }

    internal fun buildAffineTimeline(
        target: List<SubtitleSyncCue>,
        scale: Double,
        shiftMs: Double,
        score: Double,
        source: String,
    ): AutoSyncTimelineRetimeResult {
        val retimed = target
            .sortedBy { it.startTimeMs }
            .map { cue ->
                val startMs = (cue.startTimeMs * scale + shiftMs)
                    .roundToLong()
                    .coerceAtLeast(0L)
                val endMs = (cue.endTimeMs * scale + shiftMs)
                    .roundToLong()
                    .coerceAtLeast(startMs + 1L)
                AutoSyncRetimedCue(
                    originalStartTimeMs = cue.startTimeMs,
                    originalEndTimeMs = cue.endTimeMs,
                    startTimeMs = startMs,
                    endTimeMs = endMs,
                )
            }
        return AutoSyncTimelineRetimeResult(
            cues = retimed,
            groups = emptyList(),
            targetCoverage = 1.0,
            referenceCoverage = 1.0,
            skippedTargetCues = 0,
            skippedReferenceCues = 0,
            longestTargetSkipRun = 0,
            averageGroupCost = 0.0,
            oneToOneGroups = target.size,
            oneToTwoGroups = 0,
            twoToOneGroups = 0,
            oneToThreeGroups = 0,
            threeToOneGroups = 0,
            twoToTwoGroups = 0,
            confident = true,
            alignmentSource = source,
            alignmentScale = scale,
            alignmentInterceptMs = shiftMs,
            activityScore = score.coerceIn(0.0, 1.0),
            simpleGroupRatio = 1.0,
        )
    }

    private val AutoSyncWordFit.isConfident: Boolean
        get() = segments >= MIN_SEGMENTS && score >= MIN_SCORE &&
            score >= RATIO * maxOf(runnerUp, 0.5)

    private fun normalize(raw: String): String =
        raw.lowercase()
            .replace(nonWord, "")
            .trim('\'')

    private fun tokenize(text: String): List<String> =
        text.replace(tagPattern, " ")
            .split(wordSeparator)
            .map(::normalize)
            .filter { it.isNotBlank() }

    private fun median(values: List<Double>): Double {
        if (values.isEmpty()) return 0.0
        val sorted = values.sorted()
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 1) {
            sorted[middle]
        } else {
            (sorted[middle - 1] + sorted[middle]) / 2.0
        }
    }

    private data class SubtitleWord(
        val timeMs: Long,
        val word: String,
        val firstInCue: Boolean,
    )

    private data class Anchor(
        val mediaMs: Long,
        val subtitleMs: Long,
        val weight: Double,
        val segment: Int,
        val firstInCue: Boolean,
    )

    private const val MIN_WORDS = 4
    private const val MIN_CUES = 4
    private const val MIN_SEGMENTS = 3
    private const val MIN_RATE_SEGMENTS = 5
    private const val MIN_SCORE = 3.0
    private const val RATIO = 2.5
    private const val RATE_MARGIN = 1.3
    private const val MAX_OCCURRENCES = 6
    private const val MAX_SHIFT_MS = 60_000L
    private const val CLUSTER_WINDOW_MS = 700L
    private const val RUNNER_UP_EXCLUSION_MS = 2_000L
    private const val MIN_RATE_SPAN_MS = 180_000L

    private val RATE_CANDIDATES = doubleArrayOf(
        24_000.0 / 23_976.0,
        23_976.0 / 24_000.0,
        25.0 / 23.976,
        23.976 / 25.0,
        25.0 / 24.0,
        24.0 / 25.0,
    )

    private val STOP_WORDS = setOf(
        "the", "a", "an", "and", "or", "but", "to", "of", "in", "on", "at", "for",
        "is", "it", "i", "you", "he", "she", "we", "they", "me", "my", "your", "our",
        "his", "her", "its", "be", "are", "was", "were", "am", "do", "did", "not",
        "no", "yes", "so", "that", "this", "with", "as", "what", "who", "how", "why",
        "oh", "uh", "um",
    )

    private val tagPattern = Regex("<[^>]+>|\\{[^}]*\\}")
    private val nonWord = Regex("[^\\p{L}\\p{Nd}']")
    private val wordSeparator = Regex("\\s+")
}
