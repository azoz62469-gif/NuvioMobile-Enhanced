package com.nuvio.app.features.autosync

import com.nuvio.app.features.player.SubtitleSyncCue

internal data class AutoSyncReferenceTimeline(
    val key: String,
    val language: String?,
    val label: String?,
    val cues: List<SubtitleSyncCue>,
    /** True when demuxing stopped before the whole source was inspected. */
    val sampled: Boolean = false,
)

internal data class AutoSyncReferenceMatch(
    val reference: AutoSyncReferenceTimeline,
    val timeline: AutoSyncTimelineRetimeResult,
    val score: Double,
)

/**
 * Selects the most plausible embedded reference for a subtitle timeline.
 *
 * The retimer remains the authority for whether a pairing is usable. This selector only ranks
 * already-confident pairings, so a second reference can beat a weak first one without lowering the
 * confidence gates used before a subtitle is changed.
 */
internal object AutoSyncReferenceMatcher {
    fun findBest(
        target: List<SubtitleSyncCue>,
        references: List<AutoSyncReferenceTimeline>,
    ): AutoSyncReferenceMatch? {
        if (target.size < MIN_TARGET_CUES) return null
        var best: AutoSyncReferenceMatch? = null
        for (reference in references) {
            if (reference.cues.size < MIN_REFERENCE_CUES) continue
            if (reference.cues.last().startTimeMs - reference.cues.first().startTimeMs <
                MIN_REFERENCE_SPAN_MS
            ) {
                continue
            }
            val timeline = if (reference.sampled) {
                val alignment = AutoSyncSampledReferenceAligner.findDelay(
                    reference = reference.cues,
                    target = target,
                ) ?: continue
                AutoSyncTimelineRetimer.buildDelayOnlyTimeline(target, alignment)
            } else {
                val detailed = AutoSyncTimelineRetimer.retime(
                    reference = reference.cues,
                    target = target,
                    coarseScale = 1.0,
                    coarseInterceptMs = 0.0,
                    discoverAlignment = true,
                    allowAmbiguousDelayOnlyMargin =
                        AutoSyncTimelineRetimer.shouldRelaxDelayOnlyMargin(
                            sdhReference = isSdh(reference),
                            referenceSize = reference.cues.size,
                            targetSize = target.size,
                        ),
                )
                if (detailed != null && detailed.confident) {
                    detailed
                } else {
                    // Subtitle releases often differ only by a constant intro offset. When the
                    // grouped alignment is not confident, fall back to the conservative
                    // delay-only matcher instead of reporting a failure.
                    val alignment = AutoSyncSampledReferenceAligner.findDelay(
                        reference = reference.cues,
                        target = target,
                    ) ?: continue
                    AutoSyncTimelineRetimer.buildDelayOnlyTimeline(target, alignment)
                }
            }
            if (!timeline.confident) continue

            val candidate = AutoSyncReferenceMatch(
                reference = reference,
                timeline = timeline,
                score = if (reference.sampled) {
                    timeline.activityScore
                } else {
                    qualityScore(reference, timeline)
                },
            )
            if (best == null || candidate.score > best.score) {
                best = candidate
            }
        }
        return best
    }

    private fun qualityScore(
        reference: AutoSyncReferenceTimeline,
        timeline: AutoSyncTimelineRetimeResult,
    ): Double {
        val costScore = 1.0 / (1.0 + timeline.averageGroupCost.coerceAtLeast(0.0))
        val skipPenalty = timeline.longestTargetSkipRun.coerceAtMost(20) * 0.004
        val sdhPenalty = if (isSdh(reference)) 0.010 else 0.0
        return timeline.targetCoverage * 0.42 +
            timeline.referenceCoverage * 0.15 +
            costScore * 0.23 +
            timeline.simpleGroupRatio * 0.20 -
            skipPenalty -
            sdhPenalty
    }

    private fun isSdh(reference: AutoSyncReferenceTimeline): Boolean {
        val names = listOfNotNull(reference.label, reference.language)
            .joinToString(" ")
            .lowercase()
        return "sdh" in names || "hearing impaired" in names || "hearing-impaired" in names
    }

    private const val MIN_TARGET_CUES = 2
    private const val MIN_REFERENCE_CUES = 4
    private const val MIN_REFERENCE_SPAN_MS = 30_000L
}
