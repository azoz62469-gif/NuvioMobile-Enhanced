package com.nuvio.app.features.player

import androidx.compose.runtime.Composable

@Composable
internal actual fun rememberSubtitleFilePicker(
    onPicked: (fileUrl: String) -> Unit,
    onError: (message: String) -> Unit,
): () -> Unit = {}
