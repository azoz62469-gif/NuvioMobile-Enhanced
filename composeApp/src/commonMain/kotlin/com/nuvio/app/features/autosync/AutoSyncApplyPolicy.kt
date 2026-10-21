package com.nuvio.app.features.autosync

/** The live preference and playback target must still be valid when an analysis finishes. */
internal fun canApplyAutoSyncResult(
    enabled: Boolean,
    capturedToken: Long,
    currentToken: Long,
    sourceUnchanged: Boolean,
): Boolean = enabled && capturedToken == currentToken && sourceUnchanged

internal fun shouldShowManualFallbackNotice(
    capturedToken: Long,
    currentToken: Long,
    lastNotifiedSourceUrl: String?,
    currentSourceUrl: String,
): Boolean =
    capturedToken == currentToken &&
        (lastNotifiedSourceUrl == null || lastNotifiedSourceUrl != currentSourceUrl)
