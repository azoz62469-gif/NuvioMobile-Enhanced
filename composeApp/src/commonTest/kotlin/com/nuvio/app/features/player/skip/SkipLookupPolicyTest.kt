package com.nuvio.app.features.player.skip

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SkipLookupPolicyTest {
    @Test
    fun usesAnUnambiguousParentMappingOnly() {
        assertEquals(
            "123",
            chooseSeasonScopedMalId(
                ids = listOf(null, "123", "123"),
                season = 1,
            ),
        )
    }

    @Test
    fun rejectsMissingAmbiguousOrSeasonUnverifiedAnimeMappings() {
        assertNull(chooseSeasonScopedMalId(emptyList(), season = 1))
        assertNull(chooseSeasonScopedMalId(listOf("123", "321"), season = 1))
        assertNull(chooseSeasonScopedMalId(listOf("123"), season = 2))
        assertNull(chooseSeasonScopedMalId(listOf("123"), season = 0))
    }
}
