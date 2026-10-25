package com.nuvio.app.features.player

import androidx.compose.runtime.Composable

@Composable
internal expect fun rememberSubtitleFilePicker(
    onPicked: (fileUrl: String) -> Unit,
    onError: (message: String) -> Unit,
): () -> Unit
