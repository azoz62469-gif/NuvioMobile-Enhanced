package com.nuvio.app.features.player

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.nuvio.app.features.autosync.*
import kotlin.math.roundToInt

@Composable
internal fun AutoSyncDiagnosticsPanel() {
    SubtitleEnhancements.ensureLoaded()
    val options by SubtitleEnhancements.state.collectAsState()
    if (!options.showDiagnostics) return
    val status by AutoSyncDiagnostics.current.collectAsState()
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("المزامنة: ${status.state}")
        if (status.method.isNotBlank()) Text(status.method)
        status.score?.let {
            Text("درجة المطابقة: ${(it * 100).roundToInt()}٪ • التغطية: ${(status.coverage * 100).roundToInt()}٪")
            Text("الإزاحة: ${status.offsetMs} مللي ثانية • معدل الزمن: ${(status.scale * 10000).roundToInt() / 10000.0}")
        }
        Text("المراجع: ${status.references}")
        Row {
            TextButton(onClick = { AutoSyncDiagnostics.restoreOriginal?.invoke() }, enabled = AutoSyncDiagnostics.restoreOriginal != null) { Text("الأصلية") }
            TextButton(onClick = { AutoSyncDiagnostics.runAgain?.invoke() }, enabled = AutoSyncDiagnostics.runAgain != null) { Text("إعادة الفحص") }
            TextButton(onClick = { AutoSyncDiagnostics.exportCurrent?.invoke() }, enabled = AutoSyncDiagnostics.exportCurrent != null) { Text("تصدير") }
        }
    }
}
