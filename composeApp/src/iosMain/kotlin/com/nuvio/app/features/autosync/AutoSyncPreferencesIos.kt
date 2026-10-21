package com.nuvio.app.features.autosync

import com.nuvio.app.core.storage.ProfileScopedKey
import platform.Foundation.NSUserDefaults

internal object AutoSyncPreferencesIos {
    private const val preferredSubtitleAutoSyncOnStartKey =
        "preferred_subtitle_auto_sync_on_start"
    private const val aggressiveModeKey = "auto_sync_aggressive_mode"
    private const val syncToleranceMsKey = "auto_sync_tolerance_ms"
    private const val audioFallbackKey = "auto_sync_audio_fallback"
    private var initialized = false

    fun initialize() {
        if (initialized) return
        initialized = true
        val defaults = NSUserDefaults.standardUserDefaults
        val migrationKey = ProfileScopedKey.of("auto_sync_default_on_migrated_v12")
        if (defaults.objectForKey(migrationKey) == null) {
            defaults.setBool(
                true,
                forKey = ProfileScopedKey.of(preferredSubtitleAutoSyncOnStartKey),
            )
            defaults.setBool(true, forKey = migrationKey)
        }
        AutoSyncPreferencesRepository.installPersistence(
            load = {
                val key = ProfileScopedKey.of(preferredSubtitleAutoSyncOnStartKey)
                if (defaults.objectForKey(key) != null) defaults.boolForKey(key) else null
            },
            save = { enabled ->
                defaults.setBool(
                    enabled,
                    forKey = ProfileScopedKey.of(preferredSubtitleAutoSyncOnStartKey),
                )
            },
            loadAggressiveMode = {
                val key = ProfileScopedKey.of(aggressiveModeKey)
                if (defaults.objectForKey(key) != null) defaults.boolForKey(key) else null
            },
            saveAggressiveMode = { enabled ->
                defaults.setBool(enabled, forKey = ProfileScopedKey.of(aggressiveModeKey))
            },
            loadSyncToleranceMs = {
                val key = ProfileScopedKey.of(syncToleranceMsKey)
                if (defaults.objectForKey(key) != null) {
                    defaults.integerForKey(key).toInt()
                } else {
                    null
                }
            },
            saveSyncToleranceMs = { toleranceMs ->
                defaults.setInteger(
                    toleranceMs.toLong(),
                    forKey = ProfileScopedKey.of(syncToleranceMsKey),
                )
            },
            loadAudioFallback = {
                val key = ProfileScopedKey.of(audioFallbackKey)
                if (defaults.objectForKey(key) != null) defaults.boolForKey(key) else null
            },
            saveAudioFallback = { enabled ->
                defaults.setBool(
                    enabled,
                    forKey = ProfileScopedKey.of(audioFallbackKey),
                )
            },
        )
    }
}
