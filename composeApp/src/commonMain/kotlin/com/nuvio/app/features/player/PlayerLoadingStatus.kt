package com.nuvio.app.features.player

import androidx.compose.runtime.Composable
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.player_loading_buffering
import nuvio.composeapp.generated.resources.player_loading_building
import nuvio.composeapp.generated.resources.player_loading_starting
import nuvio.composeapp.generated.resources.player_loading_subtitles
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

internal fun playerLoadingStatusResource(
    showStatus: Boolean,
    controllerReady: Boolean,
    buffering: Boolean,
): StringResource? = when {
    !showStatus -> null
    !controllerReady -> Res.string.player_loading_building
    buffering -> Res.string.player_loading_buffering
    else -> Res.string.player_loading_starting
}

@Composable
internal fun playerLoadingStatusMessage(
    showStatus: Boolean,
    controllerReady: Boolean,
    buffering: Boolean,
): String? = playerLoadingStatusResource(showStatus, controllerReady, buffering)
    ?.let { stringResource(it) }

@Composable
internal fun subtitleLoadingStatusMessage(): String =
    stringResource(Res.string.player_loading_subtitles)
