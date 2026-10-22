package com.nuvio.app.features.autosync

import com.nuvio.app.features.profiles.ProfileRepository
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.serialization.json.*
import platform.Foundation.*
import platform.UIKit.*

@OptIn(ExperimentalForeignApi::class)
internal object SubtitleExportIos {
    private fun directory(): String {
        val base = NSSearchPathForDirectoriesInDomains(NSDocumentDirectory, NSUserDomainMask, true).first() as String
        return "$base/Subtitles/profile-${ProfileRepository.activeProfileId}"
    }

    fun save(body: String): String? {
        val dir = directory()
        if (!NSFileManager.defaultManager.createDirectoryAtPath(dir, true, null, null)) return null
        val ext = when {
            body.trimStart().startsWith("WEBVTT") -> "vtt"
            body.contains("[Events]", true) -> "ass"
            else -> "srt"
        }
        val path = "$dir/Subtitle-${NSUUID().UUIDString}.$ext"
        return path.takeIf { NSString.create(string = body).writeToFile(it, true, NSUTF8StringEncoding, null) }
    }

    fun export(body: String) {
        val path = save(body) ?: return
        var presenter = UIApplication.sharedApplication.keyWindow?.rootViewController
        while (presenter?.presentedViewController != null) presenter = presenter.presentedViewController
        val host = presenter ?: return
        val share = UIActivityViewController(listOf(NSURL.fileURLWithPath(path)), null)
        share.popoverPresentationController?.sourceView = host.view
        share.popoverPresentationController?.sourceRect = host.view.bounds
        host.presentViewController(share, true, null)
    }

    fun record(source: String, subtitle: String, status: AutoSyncDiagnostic) {
        val defaults = NSUserDefaults.standardUserDefaults
        val key = "subtitle_history_${ProfileRepository.activeProfileId}"
        val old = runCatching { Json.parseToJsonElement(defaults.stringForKey(key).orEmpty()).jsonArray.toList() }
            .getOrDefault(emptyList())
        val entry = buildJsonObject {
            // Signed URLs and request headers must never be written to exported history.
            put("media", source.hashCode().toUInt().toString(16))
            put("subtitle", subtitle.hashCode().toUInt().toString(16))
            put("date", NSDate().description)
            put("method", status.method)
            put("score", status.score ?: 0.0)
            put("offsetMs", status.offsetMs)
            put("scale", status.scale)
        }
        defaults.setObject(JsonArray((listOf(entry) + old).take(50)).toString(), forKey = key)
    }
}
