package com.nuvio.app.features.autosync

import com.nuvio.app.features.player.SubtitleSyncCue

internal object SubtitleDualComposer {
    /** Sweep cue boundaries so differently segmented languages stay independently timed. */
    fun combine(primary: List<SubtitleSyncCue>, secondary: List<SubtitleSyncCue>): List<SubtitleSyncCue> {
        data class Event(val time: Long, val index: Int, val second: Boolean, val start: Boolean)
        val events = buildList {
            for ((second, cues) in listOf(false to primary, true to secondary)) {
                cues.forEachIndexed { index, cue ->
                    if (cue.endTimeMs > cue.startTimeMs && cue.startTimeMs >= 0) {
                        add(Event(cue.startTimeMs, index, second, true))
                        add(Event(cue.endTimeMs, index, second, false))
                    }
                }
            }
        }.sortedBy { it.time }
        val first = linkedSetOf<Int>()
        val second = linkedSetOf<Int>()
        val output = mutableListOf<SubtitleSyncCue>()
        var previous = events.firstOrNull()?.time ?: return emptyList()
        for ((time, boundary) in events.groupBy { it.time }) {
            val text = (first.map { SubtitleTextEnhancer.plain(primary[it].text) } +
                second.map { SubtitleTextEnhancer.plain(secondary[it].text) })
                .filter(String::isNotBlank).joinToString("\n")
            if (time > previous && text.isNotBlank()) {
                val last = output.lastOrNull()
                if (last?.endTimeMs == previous && last.text == text) {
                    output[output.lastIndex] = last.copy(endTimeMs = time)
                } else output += SubtitleSyncCue(previous, time, text)
            }
            for (event in boundary) {
                val active = if (event.second) second else first
                if (event.start) active += event.index else active -= event.index
            }
            previous = time
        }
        return output
    }
}
