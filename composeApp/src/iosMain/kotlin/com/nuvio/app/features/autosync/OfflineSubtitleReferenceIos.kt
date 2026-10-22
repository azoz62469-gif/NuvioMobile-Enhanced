package com.nuvio.app.features.autosync

import com.nuvio.app.features.player.NuvioPlayerBridgeFactory
import com.nuvio.app.features.player.SubtitleSyncCue
import com.nuvio.app.features.player.sanitizePlaybackHeaders
import kotlinx.coroutines.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import kotlin.coroutines.resume

internal object OfflineSubtitleReferenceIos {
    suspend fun load(url: String, headers: Map<String, String>): List<AutoSyncReferenceTimeline> = withContext(Dispatchers.Main) {
        val bridge = NuvioPlayerBridgeFactory.create() ?: return@withContext emptyList()
        try {
            val body = withTimeoutOrNull(90_000L) {
                suspendCancellableCoroutine<String> { continuation ->
                    bridge.loadEmbeddedSubtitleReferences(url, Json.encodeToString(sanitizePlaybackHeaders(headers))) {
                        if (continuation.isActive) continuation.resume(it)
                    }
                    continuation.invokeOnCancellation { bridge.cancelEmbeddedSubtitleReferenceLoad() }
                }
            } ?: return@withContext emptyList()
            withContext(Dispatchers.Default) {
                runCatching {
                    Json.parseToJsonElement(body).jsonObject["tracks"]?.jsonArray.orEmpty().mapNotNull { value ->
                        val track = value.jsonObject
                        val cues = track["cues"]?.jsonArray.orEmpty().mapNotNull { item ->
                            val cue = item.jsonObject
                            val start = cue["startMs"]?.jsonPrimitive?.longOrNull ?: return@mapNotNull null
                            val end = cue["endMs"]?.jsonPrimitive?.longOrNull ?: return@mapNotNull null
                            if (end <= start) null else SubtitleSyncCue(start, end, cue["text"]?.jsonPrimitive?.contentOrNull.orEmpty())
                        }.sortedBy { it.startTimeMs }
                        if (cues.size < 4) null else AutoSyncReferenceTimeline(
                            track["key"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                            track["language"]?.jsonPrimitive?.contentOrNull,
                            track["label"]?.jsonPrimitive?.contentOrNull,
                            cues, track["sampled"]?.jsonPrimitive?.booleanOrNull ?: false,
                        )
                    }
                }.getOrDefault(emptyList())
            }
        } finally {
            bridge.cancelEmbeddedSubtitleReferenceLoad()
            bridge.destroy()
        }
    }
}
