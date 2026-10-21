package com.nuvio.app.features.autosync

import com.nuvio.app.features.player.SubtitleSyncCue
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class AutoSyncReferenceMatcherTest {
    @Test
    fun selectsConfidentShiftedReferenceAndRejectsUnrelatedTiming() {
        val referenceCues = irregularCues(80)
        val shiftedTarget = referenceCues.map { cue ->
            cue.copy(
                startTimeMs = cue.startTimeMs - 2_400L,
                endTimeMs = cue.endTimeMs - 2_400L,
            )
        }
        val unrelated = irregularCues(80).map { cue ->
            cue.copy(
                startTimeMs = cue.startTimeMs * 2L + 30_000L,
                endTimeMs = cue.endTimeMs * 2L + 30_000L,
            )
        }

        val match = assertNotNull(
            AutoSyncReferenceMatcher.findBest(
                target = shiftedTarget,
                references = listOf(
                    AutoSyncReferenceTimeline("unrelated", "en", "English", unrelated),
                    AutoSyncReferenceTimeline("embedded", "en", "English", referenceCues),
                ),
            ),
        )

        assertEquals("embedded", match.reference.key)
        assertEquals(1.0, match.timeline.alignmentScale, 0.0001)
        assertEquals(2_400.0, abs(match.timeline.alignmentInterceptMs), 1.0)
    }

    @Test
    fun returnsNullWithoutEnoughUsableCueTiming() {
        val target = irregularCues(2)
        assertNull(
            AutoSyncReferenceMatcher.findBest(
                target = target,
                references = listOf(
                    AutoSyncReferenceTimeline("short", "en", null, irregularCues(3)),
                ),
            ),
        )
    }

    @Test
    fun sampledReferenceUsesConservativeDelayOnlyAlignment() {
        val fullReference = irregularCues(120)
        val target = fullReference.map { cue ->
            cue.copy(
                startTimeMs = cue.startTimeMs - 3_200L,
                endTimeMs = cue.endTimeMs - 3_200L,
            )
        }

        val match = assertNotNull(
            AutoSyncReferenceMatcher.findBest(
                target = target,
                references = listOf(
                    AutoSyncReferenceTimeline(
                        key = "partial",
                        language = "en",
                        label = "Embedded",
                        cues = fullReference.take(60),
                        sampled = true,
                    ),
                ),
            ),
        )

        assertEquals("delay-only", match.timeline.alignmentSource)
        assertEquals(1.0, match.timeline.alignmentScale, 0.0001)
        assertEquals(3_200.0, abs(match.timeline.alignmentInterceptMs), 1.0)
        assertEquals(match.timeline.referenceCoverage, match.timeline.activityScore, 0.0001)
    }

    @Test
    fun selectsAlignedReferenceFromFortySparseReferencesAgainstFullSubtitle() {
        val timelineCues = irregularCues(1_876)
        val target = timelineCues.map { cue ->
            cue.copy(
                startTimeMs = cue.startTimeMs - 7_500L,
                endTimeMs = cue.endTimeMs - 7_500L,
            )
        }
        val references = List(39) { index ->
            val cueCount = 22 + index % 26
            val spacing = (timelineCues.size - 1).coerceAtLeast(cueCount - 1) / (cueCount - 1)
            AutoSyncReferenceTimeline(
                key = "embedded:$index",
                language = "und",
                label = "Embedded $index",
                cues = List(cueCount) { cueIndex ->
                    timelineCues[(cueIndex * spacing).coerceAtMost(timelineCues.lastIndex)]
                },
                sampled = true,
            )
        }

        val match = assertNotNull(
            AutoSyncReferenceMatcher.findBest(
                target = target,
                references = references,
            ),
        )

        assertEquals("delay-only", match.timeline.alignmentSource)
        assertEquals(7_500.0, abs(match.timeline.alignmentInterceptMs), 1.0)
        assertEquals(match.timeline.referenceCoverage, match.timeline.activityScore, 0.0001)
    }

    @Test
    fun rejectsDenseTargetTimingThatDoesNotShareTheReferenceTimeline() {
        val reference = irregularCues(40)
        val target = irregularCues(1_876).map { cue ->
            cue.copy(
                startTimeMs = cue.startTimeMs * 2L + 240_000L,
                endTimeMs = cue.endTimeMs * 2L + 240_000L,
            )
        }

        assertNull(
            AutoSyncReferenceMatcher.findBest(
                target = target,
                references = listOf(
                    AutoSyncReferenceTimeline(
                        key = "different-cut",
                        language = "ar",
                        label = "Arabic",
                        cues = reference,
                        sampled = true,
                    ),
                ),
            ),
        )
    }

    private fun irregularCues(count: Int): List<SubtitleSyncCue> {
        var start = 4_000L
        return List(count) { index ->
            val duration = 700L + (index * 313L) % 1_500L
            val cue = SubtitleSyncCue(
                startTimeMs = start,
                endTimeMs = start + duration,
                text = "line $index",
            )
            start += duration + 400L + (index * 197L) % 2_200L
            cue
        }
    }
}
