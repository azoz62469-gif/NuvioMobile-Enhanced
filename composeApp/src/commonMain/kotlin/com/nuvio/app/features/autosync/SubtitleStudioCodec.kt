package com.nuvio.app.features.autosync

import com.nuvio.app.features.player.SubtitleSyncCue
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Small stable JSON boundary for the native translation/OCR editor. */
object SubtitleStudioCodec {
    @Serializable
    private data class Cue(val start: Long, val end: Long, val text: String)

    fun decode(body: String): String = Json.encodeToString(
        AutoSyncSubtitleCueParser.parse(body, "subtitle.srt").map {
            Cue(it.startTimeMs, it.endTimeMs, SubtitleTextEnhancer.plain(it.text))
        },
    )

    fun encode(cuesJson: String, format: String): String {
        val cues = runCatching { Json.decodeFromString<List<Cue>>(cuesJson) }.getOrNull() ?: return ""
        return SubtitleTextEnhancer.export(cues.map { SubtitleSyncCue(it.start, it.end, it.text) }, format)
    }

    fun enhance(body: String): String {
        SubtitleEnhancements.ensureLoaded()
        return SubtitleTextEnhancer.process(body, SubtitleEnhancements.state.value)
    }
}
