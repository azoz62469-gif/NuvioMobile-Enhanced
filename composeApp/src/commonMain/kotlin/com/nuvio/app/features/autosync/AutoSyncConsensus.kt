package com.nuvio.app.features.autosync

import com.nuvio.app.features.player.SubtitleSyncCue
import kotlin.math.abs
import kotlin.math.roundToLong

internal object AutoSyncConsensus {
    fun choose(matches: List<AutoSyncReferenceMatch>): AutoSyncReferenceMatch? {
        val unique = matches.filter { it.timeline.confident && it.score.isFinite() }
            .distinctBy { match -> match.reference.cues.map { it.startTimeMs to it.endTimeMs } }
        if (unique.size < 2) return unique.firstOrNull()
        val groups = unique.map { anchor ->
            unique.filter { candidate -> agrees(anchor.timeline, candidate.timeline) }
        }
        val best = groups.maxByOrNull { it.size } ?: return null
        // An equal vote or a minority is not evidence that a correction is correct.
        if (best.size <= unique.size / 2) return null
        return best.maxByOrNull { it.score }
    }

    fun agrees(a: AutoSyncTimelineRetimeResult, b: AutoSyncTimelineRetimeResult): Boolean {
        val left = a.cues.associateBy { it.originalStartTimeMs }
        val compared = b.cues.mapNotNull { cue -> left[cue.originalStartTimeMs]?.let { it to cue } }
        return compared.isNotEmpty() && compared.all { (x, y) ->
            abs(x.startTimeMs - y.startTimeMs) <= 350L && abs(x.endTimeMs - y.endTimeMs) <= 500L
        }
    }

    fun find(
        target: List<SubtitleSyncCue>,
        references: List<AutoSyncReferenceTimeline>,
        consensus: Boolean,
        segmented: Boolean,
    ): AutoSyncReferenceMatch? {
        val matches = references.take(6).mapNotNull { reference ->
            AutoSyncReferenceMatcher.findBest(target, listOf(reference))
                ?: if (segmented && !reference.sampled) piecewise(target, reference) else null
        }
        return if (consensus) choose(matches) else matches.maxByOrNull { it.score }
    }

    /** Independent text anchors per window; never extrapolate a cut from a single speech sample. */
    fun piecewise(
        target: List<SubtitleSyncCue>,
        reference: AutoSyncReferenceTimeline,
    ): AutoSyncReferenceMatch? {
        if (target.size < 40 || reference.cues.size < 40) return null
        val heard = reference.cues.flatMapIndexed { index, cue ->
            val words = SubtitleTextEnhancer.plain(cue.text).split(Regex("\\s+")).filter(String::isNotBlank)
            words.mapIndexed { wordIndex, word ->
                AutoSyncHeardWord(
                    cue.startTimeMs + ((cue.endTimeMs - cue.startTimeMs) * wordIndex.toDouble() / words.size).roundToLong(),
                    word, index,
                )
            }
        }
        val windows = target.groupBy { it.startTimeMs / 180_000L }.values
        val fits = windows.mapNotNull { window ->
            if (window.size < 8) return@mapNotNull null
            val range = (window.first().startTimeMs - 180_000)..(window.last().endTimeMs + 180_000)
            val fit = AutoSyncWordMatcher.confidentMapping(heard.filter { it.timeMs in range }, window)
                ?: return@mapNotNull null
            if (fit.score < 0.80 || fit.segments < 4 || fit.spanMs < 30_000) return@mapNotNull null
            window to fit
        }
        val covered = fits.sumOf { it.first.size }
        if (fits.size < 2 || covered.toDouble() / target.size < 0.90) return null
        val mappings = fits.flatMap { (window, fit) ->
            window.map { cue -> cue.startTimeMs to (fit.scale to fit.shiftMs) }
        }.toMap()
        val baseline = AutoSyncWordMatcher.buildAffineTimeline(target, 1.0, 0.0, 1.0, "piecewise-text")
        val retimed = baseline.cues.map { cue ->
            val mapping = mappings[cue.originalStartTimeMs] ?: return@map cue
            cue.copy(
                startTimeMs = (cue.originalStartTimeMs * mapping.first + mapping.second).roundToLong().coerceAtLeast(0),
                endTimeMs = (cue.originalEndTimeMs * mapping.first + mapping.second).roundToLong().coerceAtLeast(1),
            )
        }
        if (retimed.any { it.endTimeMs <= it.startTimeMs } ||
            retimed.zipWithNext().any { (a, b) ->
                b.originalStartTimeMs >= a.originalEndTimeMs && b.startTimeMs < a.endTimeMs
            }) return null
        val score = fits.minOf { it.second.score }.coerceIn(0.0, 1.0)
        return AutoSyncReferenceMatch(
            reference,
            baseline.copy(cues = retimed, targetCoverage = covered.toDouble() / target.size, activityScore = score),
            score,
        )
    }
}
