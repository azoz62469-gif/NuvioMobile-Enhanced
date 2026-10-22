plugins {
    kotlin("jvm") version "2.4.10"
    kotlin("plugin.serialization") version "2.4.10"
}
repositories { mavenCentral() }
kotlin { jvmToolchain(21) }
sourceSets {
    main {
        kotlin.srcDir("../../composeApp/src/commonMain/kotlin")
        kotlin.setIncludes(listOf(
            "com/nuvio/app/features/autosync/AutoSyncApplyPolicy.kt",
            "com/nuvio/app/features/autosync/AutoSyncAddonReferencePolicy.kt",
            "com/nuvio/app/features/autosync/AutoSyncPlayerContracts.kt",
            "com/nuvio/app/features/autosync/AutoSyncReferenceMatcher.kt",
            "com/nuvio/app/features/autosync/AutoSyncRunPolicy.kt",
            "com/nuvio/app/features/autosync/AutoSyncSampledReferenceAligner.kt",
            "com/nuvio/app/features/autosync/AutoSyncSubtitleCueParser.kt",
            "com/nuvio/app/features/autosync/AutoSyncSubtitleTextRetimer.kt",
            "com/nuvio/app/features/autosync/AutoSyncTimelineRetime.kt",
            "com/nuvio/app/features/autosync/AutoSyncTimelineTimeMapper.kt",
            "com/nuvio/app/features/autosync/AutoSyncWordMatcher.kt",
            "com/nuvio/app/features/autosync/AutoSyncConsensus.kt",
            "com/nuvio/app/features/autosync/SubtitleEnhancements.kt",
            "com/nuvio/app/features/autosync/SubtitleTextEnhancer.kt",
            "com/nuvio/app/features/autosync/SubtitleDualComposer.kt",
            "com/nuvio/app/features/autosync/SubtitleStudioCodec.kt",
            "com/nuvio/app/features/player/SubtitleSdhFilter.kt",
            "com/nuvio/app/features/player/SubtitleSyncCue.kt",
            "com/nuvio/app/features/downloads/DownloadCleanupPolicy.kt",
            "com/nuvio/app/features/profiles/ProfileRepository.kt",
        ))
        // Use only the profile identity fixture, not the application's networked repository.
        kotlin.exclude { it.file.invariantSeparatorsPath.endsWith("commonMain/kotlin/com/nuvio/app/features/profiles/ProfileRepository.kt") }
    }
    test {
        kotlin.srcDir("../../composeApp/src/commonTest/kotlin")
        kotlin.setIncludes(listOf("com/nuvio/app/features/autosync/*Test.kt", "com/nuvio/app/features/downloads/DownloadCleanupPolicyTest.kt"))
    }
}
dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.10.0")
    testImplementation(kotlin("test-junit"))
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    compilerExecutionStrategy.set(org.jetbrains.kotlin.gradle.tasks.KotlinCompilerExecutionStrategy.IN_PROCESS)
}
