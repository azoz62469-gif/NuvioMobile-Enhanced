package com.nuvio.app.features.autosync

import com.nuvio.app.features.player.SubtitleSdhFilter
import com.nuvio.app.features.player.SubtitleSyncCue

/** Changes dialogue only. ASS override tags and WebVTT timing/settings remain intact. */
internal object SubtitleTextEnhancer {
    private val tags = Regex("(\\{[^}]*}|<[^>]*>)")
    private val arabic = Regex("[\\u0621-\\u064A]")

    fun process(body: String, options: SubtitleEnhancementOptions): String {
        if (!options.transformsText) return body
        val normalized = body.removePrefix("\uFEFF").replace("\r\n", "\n").replace('\r', '\n')
        if (normalized.contains("[Events]", ignoreCase = true)) {
            var inEvents = false
            var fieldCount = 10
            var textIndex = 9
            return normalized.lines().joinToString("\n") { line ->
                val trimmed = line.trim()
                when {
                    trimmed.startsWith("[") -> {
                        inEvents = trimmed.equals("[Events]", true)
                        line
                    }
                    inEvents && trimmed.startsWith("Format:", true) -> {
                        val fields = trimmed.substringAfter(':').split(',').map(String::trim)
                        fieldCount = fields.size
                        textIndex = fields.indexOfFirst { it.equals("Text", true) }
                        line
                    }
                    inEvents && trimmed.startsWith("Dialogue:", true) && textIndex == fieldCount - 1 -> {
                        val parts = line.substringAfter(':').split(',', limit = fieldCount).toMutableList()
                        if (parts.size == fieldCount) {
                            // Drawing commands and animated karaoke are not ordinary dialogue.
                            if (!Regex("\\\\(?:p[1-9]|k[fo]?\\d)").containsMatchIn(parts[textIndex])) {
                                parts[textIndex] = transform(parts[textIndex].replace("\\N", "\n"), options).replace("\n", "\\N")
                            }
                            line.substringBefore(':') + ":" + parts.joinToString(",")
                        } else line
                    }
                    else -> line
                }
            }
        }
        var dialogue = false
        return normalized.lines().joinToString("\n") { line ->
            when {
                "-->" in line -> { dialogue = true; line }
                line.isBlank() -> { dialogue = false; line }
                dialogue -> transform(line, options)
                else -> line
            }
        }
    }

    fun transform(text: String, options: SubtitleEnhancementOptions): String {
        if (options.removeMusic && (text.contains('♪') || text.contains('♫'))) return ""
        var result = if (options.removeSdh) SubtitleSdhFilter.filter(text).orEmpty() else text
        val replacements = if (options.glossaryEnabled) options.glossary.lines().mapNotNull { line ->
            val key = line.substringBefore('=').trim()
            val value = line.substringAfter('=', "").trim()
            if ('=' in line && key.isNotEmpty() && value.isNotEmpty()) key to value else null
        }.sortedByDescending { it.first.length }.take(200) else emptyList()
        val blocked = if (options.filterWords) options.blockedWords.lines().map(String::trim)
            .filter(String::isNotEmpty).distinct().take(200) else emptyList()
        val parts = tags.split(result)
        val markup = tags.findAll(result).map { it.value }.toList()
        result = buildString {
            parts.forEachIndexed { index, part ->
                var changed = part
                for ((from, to) in replacements) changed = replaceWord(changed, from, to)
                for (word in blocked) changed = replaceWord(changed, word, "***")
                if (options.arabicPunctuation && arabic.containsMatchIn(changed)) {
                    changed = changed.replace('?', '؟').replace(';', '؛')
                        .replace(Regex(",(?=\\s|$)"), "،")
                }
                append(changed)
                if (index < markup.size) append(markup[index])
            }
        }
        // Wrapping styled text could split tags, so leave its authored line breaks alone.
        if (options.wrapLines && !tags.containsMatchIn(result)) {
            result = result.lines().flatMap { wrap(it, options.lineLength) }.joinToString("\n")
        }
        if (options.arabicDirection) {
            result = result.lines().joinToString("\n") { line ->
                if (arabic.containsMatchIn(line) && !line.startsWith('\u200F')) "\u200F$line" else line
            }
        }
        return result
    }

    private fun replaceWord(text: String, word: String, replacement: String): String =
        Regex("(?<![\\p{L}\\p{N}_])" + Regex.escape(word) + "(?![\\p{L}\\p{N}_])", RegexOption.IGNORE_CASE)
            .replace(text) { replacement }

    private fun wrap(line: String, limit: Int): List<String> {
        val lines = mutableListOf<String>()
        var current = ""
        for (word in line.trim().split(Regex("\\s+"))) {
            if (current.isNotEmpty() && current.length + word.length + 1 > limit) {
                lines += current
                current = word
            } else current += (if (current.isEmpty()) "" else " ") + word
        }
        if (current.isNotEmpty()) lines += current
        return lines
    }

    fun export(cues: List<SubtitleSyncCue>, format: String): String {
        val valid = cues.filter { it.startTimeMs >= 0 && it.endTimeMs > it.startTimeMs }
        return when (format.lowercase()) {
            "vtt" -> "WEBVTT\n\n" + valid.joinToString("\n\n") {
                "${timestamp(it.startTimeMs, '.')} --> ${timestamp(it.endTimeMs, '.')}\n${it.text}"
            } + "\n"
            "ass" -> "[Script Info]\nScriptType: v4.00+\n\n[V4+ Styles]\n" +
                "Format: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, OutlineColour, BackColour, Bold, Italic, Underline, StrikeOut, ScaleX, ScaleY, Spacing, Angle, BorderStyle, Outline, Shadow, Alignment, MarginL, MarginR, MarginV, Encoding\n" +
                "Style: Default,Arial,24,&H00FFFFFF,&H00FFFFFF,&H00000000,&H80000000,0,0,0,0,100,100,0,0,1,2,0,2,20,20,20,1\n\n" +
                "[Events]\nFormat: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text\n" +
                valid.joinToString("\n") {
                    "Dialogue: 0,${timestamp(it.startTimeMs, '.').dropLast(1)},${timestamp(it.endTimeMs, '.').dropLast(1)},Default,,0,0,0,," +
                        plain(it.text).replace("\\", "＼").replace("{", "｛").replace("}", "｝").replace("\n", "\\N")
                } + "\n"
            else -> valid.mapIndexed { index, cue ->
                "${index + 1}\n${timestamp(cue.startTimeMs, ',')} --> ${timestamp(cue.endTimeMs, ',')}\n${cue.text}"
            }.joinToString("\n\n") + "\n"
        }
    }

    fun plain(text: String): String = tags.replace(text, "").replace("\\N", "\n")

    private fun timestamp(ms: Long, separator: Char): String =
        (ms / 3_600_000).toString().padStart(2, '0') + ":" +
            (ms / 60_000 % 60).toString().padStart(2, '0') + ":" +
            (ms / 1_000 % 60).toString().padStart(2, '0') + separator +
            (ms % 1_000).toString().padStart(3, '0')
}
