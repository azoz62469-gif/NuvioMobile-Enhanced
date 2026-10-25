package com.nuvio.app.features.autosync

internal enum class AutoSyncCandidateScope {
    STARTUP_SEARCH,
    SELECTED_ONLY,
}

internal fun decideAutoSyncStart(enabled: Boolean): AutoSyncStartAction =
    if (enabled) AutoSyncStartAction.RUN else AutoSyncStartAction.ATTACH_ORIGINAL

/** Media sources that the shared reference matcher can read without an audio fallback. */
internal fun supportsAutoSyncMediaSource(sourceUrl: String): Boolean =
    sourceUrl.substringBefore(':', missingDelimiterValue = "")
        .lowercase() in setOf("http", "https", "file")

internal enum class AutoSyncStartAction {
    RUN,
    ATTACH_ORIGINAL,
}

internal fun AutoSyncCandidateScope.alternativeCandidates(
    candidates: List<AutoSyncSubtitleCandidate>,
): List<AutoSyncSubtitleCandidate> =
    if (this == AutoSyncCandidateScope.STARTUP_SEARCH) candidates else emptyList()

internal val AutoSyncCandidateScope.usesAlternativeProvider: Boolean
    get() = this == AutoSyncCandidateScope.STARTUP_SEARCH

internal fun shouldClaimAutoSyncStartupRun(
    enabled: Boolean,
    lastSessionKey: Int?,
    lastPlaybackKey: String?,
    sessionKey: Int,
    playbackKey: String,
): Boolean =
    enabled && playbackKey.isNotBlank() &&
        (lastSessionKey != sessionKey || lastPlaybackKey != playbackKey)

internal fun shouldRestoreOriginalSubtitle(
    activeSidecarSubtitleKey: String?,
): Boolean = activeSidecarSubtitleKey == null
