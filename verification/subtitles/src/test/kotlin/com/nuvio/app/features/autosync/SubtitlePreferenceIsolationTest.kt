package com.nuvio.app.features.autosync

import com.nuvio.app.features.profiles.ProfileRepository
import kotlin.test.*

class SubtitlePreferenceIsolationTest {
    @Test fun settingsSurviveReloadAndStaySeparateBetweenProfiles() {
        val persisted = mutableMapOf<Int, String>()
        SubtitleEnhancements.installPersistence(
            { persisted[ProfileRepository.activeProfileId] },
            { persisted[ProfileRepository.activeProfileId] = it },
        )
        ProfileRepository.activeProfileId = 1
        SubtitleEnhancements.update { it.copy(consensus = true, glossary = "John=جون") }
        ProfileRepository.activeProfileId = 2
        SubtitleEnhancements.ensureLoaded()
        assertFalse(SubtitleEnhancements.state.value.consensus)
        assertEquals("", SubtitleEnhancements.state.value.glossary)
        SubtitleEnhancements.update { it.copy(dualSubtitles = true) }
        ProfileRepository.activeProfileId = 1
        SubtitleEnhancements.ensureLoaded()
        assertTrue(SubtitleEnhancements.state.value.consensus)
        assertFalse(SubtitleEnhancements.state.value.dualSubtitles)
        assertEquals("John=جون", SubtitleEnhancements.state.value.glossary)
    }
}
