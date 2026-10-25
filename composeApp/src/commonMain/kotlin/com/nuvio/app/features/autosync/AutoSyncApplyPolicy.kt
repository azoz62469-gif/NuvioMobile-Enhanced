package com.nuvio.app.features.autosync

/** The live preference and playback target must still be valid when an analysis finishes. */
internal fun canApplyAutoSyncResult(
    enabled: Boolean,
    capturedToken: Long,
    currentToken: Long,
    sourceUnchanged: Boolean,
): Boolean = enabled && capturedToken == currentToken && sourceUnchanged

/**
 * A confident matcher result is still not enough to rewrite a subtitle unless enough of both
 * timelines agree. This final gate prevents a weak text-reference fallback from replacing useful
 * subtitle timing with a visually plausible but incorrect alignment.
 */
internal fun isConfidentAutoSyncMatch(
    score: Double,
    targetCoverage: Double,
    referenceCoverage: Double,
    sampledReference: Boolean,
): Boolean =
    if (sampledReference) {
        score >= 0.86
    } else {
        score >= 0.84 &&
            targetCoverage >= 0.92 &&
            referenceCoverage >= 0.65
    }

internal fun shouldShowManualFallbackNotice(
    capturedToken: Long,
    currentToken: Long,
    lastNotifiedSourceUrl: String?,
    currentSourceUrl: String,
): Boolean =
    capturedToken == currentToken &&
        (lastNotifiedSourceUrl == null || lastNotifiedSourceUrl != currentSourceUrl)
