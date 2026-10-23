package com.nuvio.app.features.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.nuvio.app.features.autosync.AutoSyncDiagnostics
import com.nuvio.app.features.autosync.AutoSyncOutcome
import kotlinx.coroutines.delay

@Composable
internal fun AutoSyncStatusOverlay(modifier: Modifier = Modifier) {
    val status by AutoSyncDiagnostics.current.collectAsState()
    var visible by remember { mutableStateOf(false) }

    LaunchedEffect(status) {
        visible = status.outcome != AutoSyncOutcome.IDLE && status.outcome != AutoSyncOutcome.DISABLED
        if (status.outcome == AutoSyncOutcome.SUCCESS || status.outcome == AutoSyncOutcome.FAILURE) {
            delay(6_000L)
            visible = false
        }
    }

    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = modifier,
    ) {
        Surface(
            color = Color.Black.copy(alpha = 0.82f),
            contentColor = Color.White,
            shape = MaterialTheme.shapes.small,
            tonalElevation = 2.dp,
        ) {
            Column(
                modifier = Modifier.widthIn(min = 230.dp, max = 420.dp).padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                Text(status.state, style = MaterialTheme.typography.bodyMedium)
                LinearProgressIndicator(
                    progress = { status.progressPercent.coerceIn(0, 100) / 100f },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text("${status.progressPercent.coerceIn(0, 100)}٪", style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}
