package com.nuvio.app.features.autosync

import com.nuvio.app.features.player.SubtitleSyncCue
import com.nuvio.app.features.profiles.ProfileRepository
import kotlin.test.*

class SubtitleEnhancementsTest {
    @Test fun piecewiseSyncHandlesAnInsertedScene() {
        val target = (0 until 100).map { index ->
            SubtitleSyncCue(index * 6000L, index * 6000L + 3000, "anchor$index unique$index marker$index")
        }
        val reference = target.mapIndexed { index, cue ->
            val shift = if (index < 60) 1000 else 5000
            cue.copy(startTimeMs = cue.startTimeMs + shift, endTimeMs = cue.endTimeMs + shift)
        }
        val result = assertNotNull(AutoSyncConsensus.piecewise(target, AutoSyncReferenceTimeline("ref", "en", null, reference)))
        assertEquals(1000, result.timeline.cues.first().startTimeMs)
        assertEquals(target[65].startTimeMs + 5000, result.timeline.cues[65].startTimeMs)
    }
    @Test fun disabledProcessingIsByteIdentical() {
        val source = "\uFEFF1\r\n00:00:01,000 --> 00:00:03,000\r\nمرحبا, John!\r\n"
        assertEquals(source, SubtitleTextEnhancer.process(source, SubtitleEnhancementOptions()))
    }

    @Test fun arabicAndGlossaryDoNotChangeTimestampsOrMarkup() {
        val body = "1\n00:00:01,000 --> 00:00:03,000\n<i>John</i>: مرحبا, كيف حالك?\n"
        val output = SubtitleTextEnhancer.process(body, SubtitleEnhancementOptions(
            arabicPunctuation = true, glossaryEnabled = true, glossary = "John=جون",
        ))
        assertTrue(output.contains("00:00:01,000 --> 00:00:03,000"))
        assertTrue(output.contains("<i>جون</i>"))
        assertTrue(output.contains("مرحبا، كيف حالك؟"))
    }

    @Test fun filtersRespectWordBoundariesAndLiteralReplacement() {
        val result = SubtitleTextEnhancer.transform("bad badge John", SubtitleEnhancementOptions(
            filterWords = true, blockedWords = "bad", glossaryEnabled = true, glossary = "John=\$1",
        ))
        assertEquals("*** badge \$1", result)
    }

    @Test fun assAnimationAndDrawingStayUntouched() {
        val body = "[Events]\nFormat: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text\n" +
            "Dialogue: 0,0:00:01.00,0:00:03.00,Default,,0,0,0,,{\\p1}m 0 0 l 10 10\n" +
            "Dialogue: 0,0:00:05.00,0:00:08.00,Default,,0,0,0,,{\\pos(10,20)}John"
        val output = SubtitleTextEnhancer.process(body, SubtitleEnhancementOptions(glossaryEnabled = true, glossary = "John=جون"))
        assertTrue(output.contains("{\\p1}m 0 0 l 10 10"))
        assertTrue(output.contains("{\\pos(10,20)}جون"))
    }

    @Test fun exportsRoundTripAllTextFormats() {
        val cues = listOf(SubtitleSyncCue(1230, 5670, "Hello, العالم"), SubtitleSyncCue(8000, 10000, "two\nlines"))
        for (format in listOf("srt", "vtt", "ass")) {
            val body = SubtitleTextEnhancer.export(cues, format)
            val read = AutoSyncSubtitleCueParser.parse(body, "test.$format")
            assertEquals(cues.map { it.startTimeMs }, read.map { it.startTimeMs }, format)
            assertEquals(cues.map { it.endTimeMs }, read.map { it.endTimeMs }, format)
            assertEquals(cues.map { it.text }, read.map { it.text }, format)
        }
    }

    @Test fun bilingualCuesPreserveIndependentIntervals() {
        val result = SubtitleDualComposer.combine(
            listOf(SubtitleSyncCue(1000, 3000, "مرحبا")),
            listOf(SubtitleSyncCue(2000, 4000, "Hello")),
        )
        assertEquals(listOf("مرحبا", "مرحبا\nHello", "Hello"), result.map { it.text })
        assertEquals(listOf(1000L, 2000L, 3000L), result.map { it.startTimeMs })
        assertEquals(listOf(2000L, 3000L, 4000L), result.map { it.endTimeMs })
    }

    @Test fun duplicateReferencesDoNotManufactureConsensus() {
        val cues = listOf(SubtitleSyncCue(1000, 2000, "one"), SubtitleSyncCue(6000, 7000, "two"))
        fun match(key: String, shift: Double, referenceShift: Long) = AutoSyncReferenceMatch(
            AutoSyncReferenceTimeline(key, "en", null, cues.map { it.copy(startTimeMs = it.startTimeMs + referenceShift) }),
            AutoSyncWordMatcher.buildAffineTimeline(cues, 1.0, shift, 0.95, "test"), 0.95,
        )
        val first = match("a", 1000.0, 0)
        assertNull(AutoSyncConsensus.choose(listOf(first, first.copy(reference = first.reference.copy(key = "copy")), match("b", 8000.0, 300))))
        assertNotNull(AutoSyncConsensus.choose(listOf(first, match("c", 1100.0, 200), match("b", 8000.0, 300))))
    }

    @Test fun invalidOptionsAreBounded() {
        val options = SubtitleEnhancementOptions(analysisMinutes = 1000, lineLength = -2, speechLocale = "invalid").validated()
        assertEquals(12, options.analysisMinutes)
        assertEquals(24, options.lineLength)
        assertEquals("en-US", options.speechLocale)
    }

    @Test fun wrapNeverDropsWords() {
        val source = "one two three four five six seven eight nine ten"
        val wrapped = SubtitleTextEnhancer.transform(source, SubtitleEnhancementOptions(wrapLines = true, lineLength = 24))
        assertEquals(source, wrapped.replace('\n', ' '))
        assertTrue(wrapped.lines().all { it.length <= 24 })
    }
}
