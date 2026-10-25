package com.nuvio.app.features.autosync

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AutoSyncApplyPolicyTest {
    @Test
    fun acceptsRemoteAndLocalMediaSources() {
        assertTrue(supportsAutoSyncMediaSource("https://media.example/movie.mkv"))
        assertTrue(supportsAutoSyncMediaSource("http://media.example/movie.mkv"))
        assertTrue(supportsAutoSyncMediaSource("file:///var/mobile/Downloads/movie.mkv"))
    }

    @Test
    fun rejectsSourcesWithoutReadableMediaReferences() {
        assertFalse(supportsAutoSyncMediaSource(""))
        assertFalse(supportsAutoSyncMediaSource("content://provider/movie"))
        assertFalse(supportsAutoSyncMediaSource("custom://movie"))
    }

    @Test
    fun startupAutoSyncRunsOncePerEpisodeAndAgainForTheNextEpisode() {
        assertTrue(
            shouldClaimAutoSyncStartupRun(
                enabled = true,
                lastSessionKey = 10,
                lastPlaybackKey = "episode:1",
                sessionKey = 10,
                playbackKey = "episode:2",
            ),
        )
        assertFalse(
            shouldClaimAutoSyncStartupRun(
                enabled = true,
                lastSessionKey = 10,
                lastPlaybackKey = "episode:2",
                sessionKey = 10,
                playbackKey = "episode:2",
            ),
        )
        assertFalse(
            shouldClaimAutoSyncStartupRun(
                enabled = false,
                lastSessionKey = 10,
                lastPlaybackKey = "episode:1",
                sessionKey = 10,
                playbackKey = "episode:2",
            ),
        )
    }

    @Test
    fun appliesOnlyToTheStillEnabledCurrentSourceAndOperation() {
        assertTrue(
            canApplyAutoSyncResult(
                enabled = true,
                capturedToken = 7L,
                currentToken = 7L,
                sourceUnchanged = true,
            ),
        )
    }

    @Test
    fun disablingDuringAnalysisPreventsThePendingResult() {
        assertFalse(
            canApplyAutoSyncResult(
                enabled = false,
                capturedToken = 7L,
                currentToken = 7L,
                sourceUnchanged = true,
            ),
        )
    }

    @Test
    fun newerSubtitleOrSourceOperationWins() {
        assertFalse(
            canApplyAutoSyncResult(
                enabled = true,
                capturedToken = 7L,
                currentToken = 8L,
                sourceUnchanged = true,
            ),
        )
        assertFalse(
            canApplyAutoSyncResult(
                enabled = true,
                capturedToken = 7L,
                currentToken = 7L,
                sourceUnchanged = false,
            ),
        )
    }

    @Test
    fun manualFallbackNoticeIsShownOnceForTheCurrentOperationAndSource() {
        assertTrue(
            shouldShowManualFallbackNotice(
                capturedToken = 7,
                currentToken = 7,
                lastNotifiedSourceUrl = "episode:1",
                currentSourceUrl = "episode:2",
            ),
        )
        assertFalse(
            shouldShowManualFallbackNotice(
                capturedToken = 7,
                currentToken = 7,
                lastNotifiedSourceUrl = "episode:2",
                currentSourceUrl = "episode:2",
            ),
        )
        assertFalse(
            shouldShowManualFallbackNotice(
                capturedToken = 7,
                currentToken = 8,
                lastNotifiedSourceUrl = null,
                currentSourceUrl = "episode:2",
            ),
        )
    }

    @Test
    fun rejectsWeakOrPoorlyCoveredMatchesBeforeRewritingTiming() {
        assertTrue(
            isConfidentAutoSyncMatch(
                score = 0.90,
                targetCoverage = 0.96,
                referenceCoverage = 0.72,
                sampledReference = false,
            ),
        )
        assertFalse(
            isConfidentAutoSyncMatch(
                score = 0.76,
                targetCoverage = 0.96,
                referenceCoverage = 0.72,
                sampledReference = false,
            ),
        )
        assertFalse(
            isConfidentAutoSyncMatch(
                score = 0.90,
                targetCoverage = 0.88,
                referenceCoverage = 0.72,
                sampledReference = false,
            ),
        )
        assertTrue(
            isConfidentAutoSyncMatch(
                score = 0.88,
                targetCoverage = 1.0,
                referenceCoverage = 0.88,
                sampledReference = true,
            ),
        )
        assertFalse(
            isConfidentAutoSyncMatch(
                score = 0.82,
                targetCoverage = 1.0,
                referenceCoverage = 0.82,
                sampledReference = true,
            ),
        )
    }
}
