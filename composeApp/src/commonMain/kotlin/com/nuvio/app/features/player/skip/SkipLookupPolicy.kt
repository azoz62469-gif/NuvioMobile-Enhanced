package com.nuvio.app.features.player.skip

/** ARM entries do not carry an authoritative season. Only a single parent mapping is unambiguous. */
internal fun chooseSeasonScopedMalId(ids: List<String?>, season: Int): String? {
    if (season != 1) return null
    return ids
        .mapNotNull { it?.trim()?.takeIf(String::isNotEmpty) }
        .distinct()
        .singleOrNull()
}
