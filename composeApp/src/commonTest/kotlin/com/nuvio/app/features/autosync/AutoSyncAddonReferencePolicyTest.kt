package com.nuvio.app.features.autosync

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AutoSyncAddonReferencePolicyTest {
    @Test
    fun permitsVerifiedTextReferenceOnlyWhenEmbeddedReferencesAreEmpty() {
        val selected = candidate(
            url = "https://subs.example/ar.srt",
            language = "ar",
            sourceName = "Arabic translation for episode 7.srt",
            episode = 7,
        )
        val english = candidate(
            url = "https://subs.example/en.srt",
            language = "en",
            sourceName = "Show.S01E07.1080p.WEB-DL-GROUP.English.srt",
            episode = 7,
        )

        val ranked = AutoSyncAddonReferencePolicy.rankTrustedCandidates(
            selected = selected,
            candidates = listOf(selected, english),
            videoSourceUrl =
                "https://video.example/Show%2ES01E07%2E1080p%2EWEB%2DDL%2DGROUP.mkv",
        )

        assertEquals(listOf(english.url), ranked.map { it.candidate.url })
        assertTrue(
            AutoSyncAddonReferencePolicy.shouldUseTextFallback(
                embeddedReferences = emptyList(),
                rankedReferences = ranked,
            ),
        )
    }

    @Test
    fun rejectsWrongEpisodeWrongReleaseAndUnverifiableVideoVersion() {
        val selected = candidate(
            url = "https://subs.example/ar.srt",
            language = "ar",
            sourceName = "Show.S02E03.Arabic.WEB-DL.srt",
            season = 2,
            episode = 3,
        )
        val wrongEpisode = candidate(
            url = "https://subs.example/wrong-episode.srt",
            sourceName = "Show.S02E04.English.WEB-DL.srt",
            season = 2,
            episode = 4,
        )
        val wrongRelease = candidate(
            url = "https://subs.example/wrong-release.srt",
            sourceName = "Show.S02E03.English.WEBRip.srt",
            season = 2,
            episode = 3,
        )

        assertTrue(
            AutoSyncAddonReferencePolicy.rankTrustedCandidates(
                selected = selected,
                candidates = listOf(selected, wrongEpisode, wrongRelease),
                videoSourceUrl = "https://video.example/Show.S02E03.WEB-DL.mkv",
            ).isEmpty(),
        )
        assertTrue(
            AutoSyncAddonReferencePolicy.rankTrustedCandidates(
                selected = selected,
                candidates = listOf(
                    selected,
                    wrongEpisode.copy(
                        url = "https://subs.example/same-episode.srt",
                        sourceName = "Show.S02E03.English.WEB-DL.srt",
                        episodeNumber = 3,
                    ),
                ),
                videoSourceUrl = "https://video.example/stream/master.m3u8",
            ).isEmpty(),
        )
    }

    @Test
    fun aTrustedTextReferenceStillCannotReplaceAnAvailableEmbeddedReference() {
        val selected = candidate(
            url = "https://subs.example/ar.srt",
            language = "ar",
            sourceName = "Movie.Arabic.WEB-DL.srt",
            season = null,
            episode = null,
        )
        val reference = candidate(
            url = "https://subs.example/en.srt",
            sourceName = "Movie.English.WEB-DL.srt",
            season = null,
            episode = null,
        )
        val ranked = AutoSyncAddonReferencePolicy.rankTrustedCandidates(
            selected = selected,
            candidates = listOf(selected, reference),
            videoSourceUrl = "https://video.example/Movie.WEB-DL.mkv",
        )

        assertFalse(
            AutoSyncAddonReferencePolicy.shouldUseTextFallback(
                embeddedReferences = listOf(
                    AutoSyncReferenceTimeline(
                        key = "embedded:1",
                        language = "en",
                        label = "Embedded",
                        cues = emptyList(),
                    ),
                ),
                rankedReferences = ranked,
            ),
        )
    }

    @Test
    fun rejectsAReleaseFilenameThatConflictsWithTheRequestedEpisode() {
        val selected = candidate(
            url = "https://subs.example/ar.srt",
            language = "ar",
            sourceName = "Show.S01E07.Arabic.WEB-DL.srt",
            episode = 7,
        )
        val conflictingFilename = candidate(
            url = "https://subs.example/en.srt",
            language = "en",
            sourceName = "Show.S01E08.English.WEB-DL.srt",
            episode = 7,
        )

        assertTrue(
            AutoSyncAddonReferencePolicy.rankTrustedCandidates(
                selected = selected,
                candidates = listOf(selected, conflictingFilename),
                videoSourceUrl = "https://video.example/Show.S01E07.WEB-DL.mkv",
            ).isEmpty(),
        )
        assertTrue(
            AutoSyncAddonReferencePolicy.rankTrustedCandidates(
                selected = selected,
                candidates = listOf(
                    selected,
                    conflictingFilename.copy(
                        sourceName = "Show.S01E07.English.WEB-DL.srt",
                    ),
                ),
                videoSourceUrl = "https://video.example/Show.S01E08.WEB-DL.mkv",
            ).isEmpty(),
        )
    }

    @Test
    fun rejectsCutProviderAndNamedReleaseContradictions() {
        val selected = candidate(
            url = "https://subs.example/ar.srt",
            language = "ar",
            sourceName = "Localized subtitle",
            season = null,
            episode = null,
        )
        fun reference(name: String) = candidate(
            url = "https://subs.example/$name.srt",
            sourceName = name,
            season = null,
            episode = null,
        )

        assertTrue(
            AutoSyncAddonReferencePolicy.rankTrustedCandidates(
                selected = selected,
                candidates = listOf(
                    selected,
                    reference("Movie.1080p.BluRay-GROUP.Theatrical.English.srt"),
                ),
                videoSourceUrl = "https://video.example/Movie.1080p.BluRay-GROUP.Extended.mkv",
            ).isEmpty(),
        )
        assertTrue(
            AutoSyncAddonReferencePolicy.rankTrustedCandidates(
                selected = selected,
                candidates = listOf(
                    selected,
                    reference("Movie.1080p.WEB-DL.NF-GROUP.English.srt"),
                ),
                videoSourceUrl = "https://video.example/Movie.1080p.WEB-DL.AMZN-GROUP.mkv",
            ).isEmpty(),
        )
        assertTrue(
            AutoSyncAddonReferencePolicy.rankTrustedCandidates(
                selected = selected,
                candidates = listOf(
                    selected,
                    reference("Movie.1080p.WEB-DL-OTHER.English.srt"),
                ),
                videoSourceUrl = "https://video.example/Movie.1080p.WEB-DL-GROUP.mkv",
            ).isEmpty(),
        )
        assertTrue(
            AutoSyncAddonReferencePolicy.rankTrustedCandidates(
                selected = selected,
                candidates = listOf(
                    selected,
                    reference("Movie.BluRay.English.srt"),
                ),
                videoSourceUrl = "https://video.example/Movie.1080p.BluRay.mkv",
            ).isEmpty(),
        )
        assertTrue(
            AutoSyncAddonReferencePolicy.rankTrustedCandidates(
                selected = selected,
                candidates = listOf(
                    selected,
                    reference("Movie.1080p.BluRay-GROUP.English.srt"),
                ),
                videoSourceUrl = "https://video.example/Movie.1080p.BluRay.mkv",
            ).isEmpty(),
        )
    }

    @Test
    fun timingOnlyFallbackUsesSameEpisodeWhenTheVideoUrlIsOpaque() {
        val selected = candidate(
            url = "https://subs.example/ar.srt",
            language = "ar",
            sourceName = "Arabic subtitle",
            episode = 7,
        )
        val sameEpisode = candidate(
            url = "https://subs.example/en.srt",
            language = "en",
            sourceName = "English subtitle",
            episode = 7,
        )
        val otherEpisode = candidate(
            url = "https://subs.example/other.srt",
            language = "en",
            sourceName = "Other episode",
            episode = 8,
        )
        val opaqueVideoUrl = "https://video.example/stream/opaque-token.m3u8"

        assertTrue(
            AutoSyncAddonReferencePolicy.rankTrustedCandidates(
                selected = selected,
                candidates = listOf(selected, sameEpisode),
                videoSourceUrl = opaqueVideoUrl,
            ).isEmpty(),
        )
        val timingOnly = AutoSyncAddonReferencePolicy.rankTimingOnlyCandidates(
            selected = selected,
            candidates = listOf(selected, sameEpisode, otherEpisode),
            videoSourceUrl = opaqueVideoUrl,
        )

        assertEquals(listOf(sameEpisode.url), timingOnly.map { it.candidate.url })
        assertTrue(
            AutoSyncAddonReferencePolicy.shouldUseTimingOnlyFallback(
                trustedReferences = emptyList(),
                timingOnlyReferences = timingOnly,
            ),
        )
    }

    @Test
    fun timingOnlyFallbackNeverCrossesVideoIdentity() {
        val selected = candidate(
            url = "https://subs.example/ar.srt",
            language = "ar",
            sourceName = "Arabic subtitle",
            episode = 7,
        )
        val otherVideo = candidate(
            url = "https://subs.example/en.srt",
            language = "en",
            sourceName = "English subtitle",
            episode = 7,
        ).copy(videoContextKey = "tt9999999")

        assertTrue(
            AutoSyncAddonReferencePolicy.rankTimingOnlyCandidates(
                selected = selected,
                candidates = listOf(selected, otherVideo),
                videoSourceUrl = "https://video.example/opaque-token.m3u8",
            ).isEmpty(),
        )
    }

    private fun candidate(
        url: String,
        language: String = "en",
        sourceName: String,
        season: Int? = 1,
        episode: Int?,
    ) = AutoSyncSubtitleCandidate(
        url = url,
        language = language,
        name = sourceName,
        addonName = "Subtitles",
        sourceName = sourceName,
        videoContextKey = "tt1234567",
        seasonNumber = season,
        episodeNumber = episode,
    )
}
