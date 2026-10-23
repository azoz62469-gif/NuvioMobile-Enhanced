package com.nuvio.app.features.autosync

import com.nuvio.app.features.profiles.ProfileRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
internal data class SubtitleEnhancementOptions(
    val consensus: Boolean = false,
    val segmentedSync: Boolean = false,
    val chooseBestSubtitle: Boolean = false,
    val showDiagnostics: Boolean = false,
    val keepHistory: Boolean = false,
    val exportSynced: Boolean = false,
    val offlineSync: Boolean = false,
    val speechLocale: String = "en-US",
    val onDeviceOnly: Boolean = true,
    val analysisMinutes: Int = 3,
    val arabicDirection: Boolean = false,
    val arabicPunctuation: Boolean = false,
    val wrapLines: Boolean = false,
    val lineLength: Int = 42,
    val removeSdh: Boolean = false,
    val removeMusic: Boolean = false,
    val glossaryEnabled: Boolean = false,
    val glossary: String = "",
    val filterWords: Boolean = false,
    val blockedWords: String = "",
    val dualSubtitles: Boolean = false,
    val secondaryLanguage: String = "en",
) {
    fun validated() = copy(
        speechLocale = speechLocale.takeIf { it in speechLocales } ?: "en-US",
        analysisMinutes = analysisMinutes.coerceIn(1, 12),
        lineLength = lineLength.coerceIn(24, 80),
        glossary = glossary.take(16_000),
        blockedWords = blockedWords.take(8_000),
        secondaryLanguage = secondaryLanguage.takeIf { it in subtitleLanguages } ?: "en",
    )

    val transformsText: Boolean get() = arabicDirection || arabicPunctuation || wrapLines ||
        removeSdh || removeMusic || glossaryEnabled || filterWords

    companion object {
        val speechLocales = listOf("en-US", "ar-SA", "fr-FR", "es-ES", "de-DE", "it-IT", "ja-JP", "ko-KR", "zh-CN", "hi-IN", "tr-TR", "pt-BR")
        val subtitleLanguages = listOf("en", "ar", "fr", "es", "de", "ja", "ko", "zh", "tr", "pt")
    }
}

internal object SubtitleEnhancements {
    private val json = Json { ignoreUnknownKeys = true }
    private val mutable = MutableStateFlow(SubtitleEnhancementOptions())
    val state = mutable.asStateFlow()
    private var profile: Int? = null
    private var load: () -> String? = { null }
    private var save: (String) -> Unit = {}

    fun installPersistence(load: () -> String?, save: (String) -> Unit) {
        this.load = load
        this.save = save
        profile = null
    }

    fun ensureLoaded() {
        if (profile == ProfileRepository.activeProfileId) return
        mutable.value = runCatching {
            json.decodeFromString<SubtitleEnhancementOptions>(load().orEmpty()).validated()
        }.getOrDefault(SubtitleEnhancementOptions())
        profile = ProfileRepository.activeProfileId
    }

    fun update(transform: (SubtitleEnhancementOptions) -> SubtitleEnhancementOptions) {
        ensureLoaded()
        mutable.value = transform(mutable.value).validated()
        save(json.encodeToString(mutable.value))
    }
}

internal data class AutoSyncDiagnostic(
    val state: String = "جاهز",
    val progressPercent: Int = 0,
    val outcome: AutoSyncOutcome = AutoSyncOutcome.IDLE,
    val method: String = "",
    val score: Double? = null,
    val offsetMs: Long = 0,
    val scale: Double = 1.0,
    val coverage: Double = 0.0,
    val references: Int = 0,
)

internal enum class AutoSyncOutcome {
    IDLE,
    RUNNING,
    SUCCESS,
    FAILURE,
    DISABLED,
}

internal object AutoSyncDiagnostics {
    val current = MutableStateFlow(AutoSyncDiagnostic())
    var restoreOriginal: (() -> Unit)? = null
    var runAgain: (() -> Unit)? = null
    var exportCurrent: (() -> Unit)? = null
}
