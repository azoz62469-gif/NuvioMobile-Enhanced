package com.nuvio.app.features.autosync

import com.nuvio.app.features.player.SubtitleSyncCue
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AutoSyncWordMatcherTest {
    @Test
    fun findsConstantOffsetFromRecognizedWords() {
        val cueText = listOf(
            "signal alpha",
            "signal bravo",
            "signal charlie",
            "signal delta",
            "signal echo",
            "signal foxtrot",
            "signal golf",
        )
        val cues = cueText.mapIndexed { index, text ->
            val startMs = index * 10_000L
            SubtitleSyncCue(
                startTimeMs = startMs,
                endTimeMs = startMs + 3_000L,
                text = text,
            )
        }
        val words = cueText.mapIndexed { index, text ->
            AutoSyncHeardWord(
                timeMs = index * 10_000L + 2_000L,
                text = text.substringAfter(' '),
                segment = index / 2,
            )
        }

        val fit = assertNotNull(AutoSyncWordMatcher.confidentMapping(words, cues))
        assertTrue(fit.shiftMs in 400.0..600.0)
        assertTrue(fit.segments >= 3)
    }

    @Test
    fun unrelatedWordsAreRejected() {
        val cues = listOf(
            SubtitleSyncCue(0, 3_000, "signal alpha"),
            SubtitleSyncCue(10_000, 13_000, "signal bravo"),
            SubtitleSyncCue(20_000, 23_000, "signal charlie"),
            SubtitleSyncCue(30_000, 33_000, "signal delta"),
        )
        val words = listOf(
            AutoSyncHeardWord(2_000, "unrelated", 0),
            AutoSyncHeardWord(12_000, "nonsense", 1),
            AutoSyncHeardWord(22_000, "different", 2),
            AutoSyncHeardWord(32_000, "words", 3),
        )

        assertTrue(AutoSyncWordMatcher.confidentMapping(words, cues) == null)
    }
}
