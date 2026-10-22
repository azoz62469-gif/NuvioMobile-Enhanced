package com.nuvio.app.features.downloads

import kotlin.test.*

class DownloadCleanupPolicyTest {
    private val week = 7L * 24 * 60 * 60 * 1000
    @Test fun preservesRecentUnwatchedAndRedownloadedEpisodes() {
        assertFalse(eligibleForDownloadCleanup(true, true, 100, null, week * 2))
        assertFalse(eligibleForDownloadCleanup(true, true, 100, week, week + 100))
        assertFalse(eligibleForDownloadCleanup(true, true, 200, 100, week * 2))
        assertFalse(eligibleForDownloadCleanup(false, true, 0, 100, week * 2))
        assertFalse(eligibleForDownloadCleanup(true, false, 0, 100, week * 2))
    }
    @Test fun onlyCleansCompletedEpisodesWatchedAtLeastAWeekAgo() {
        assertTrue(eligibleForDownloadCleanup(true, true, 100, 200, 200 + week))
        assertFalse(eligibleForDownloadCleanup(true, true, 100, 200, 200 + week - 1))
    }
}
