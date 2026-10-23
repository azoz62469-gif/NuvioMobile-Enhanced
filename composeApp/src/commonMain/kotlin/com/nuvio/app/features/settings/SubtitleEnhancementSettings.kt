package com.nuvio.app.features.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.graphics.Color
import com.nuvio.app.features.autosync.*
import com.nuvio.app.features.player.PlayerSettingsRepository
import com.nuvio.app.features.player.SubtitleStyleState
import com.nuvio.app.isIos
import kotlin.math.roundToInt

@Composable
internal fun SubtitleEnhancementSettingsRow(isTablet: Boolean) {
    if (!isIos) return
    var visible by remember { mutableStateOf(false) }
    SettingsNavigationRow(
        title = "تحسينات الترجمة والمزامنة",
        description = "خيارات الترجمة المتقدمة",
        isTablet = isTablet,
        onClick = { visible = true },
    )
    if (!visible) return
    SubtitleEnhancements.ensureLoaded()
    val options by SubtitleEnhancements.state.collectAsState()
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        AlertDialog(
            onDismissRequest = { visible = false },
            title = { Text("الترجمة والمزامنة") },
            confirmButton = { TextButton(onClick = { visible = false }) { Text("تم") } },
            text = {
                Column(Modifier.fillMaxWidth().heightIn(max = 650.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("المزامنة", style = MaterialTheme.typography.titleMedium)
                    Toggle("لوحة نتائج المزامنة", options.showDiagnostics) { options.copy(showDiagnostics = it) }
                    Toggle("مقارنة المراجع والتصويت", options.consensus) { options.copy(consensus = it) }
                    Toggle("مطابقة المقاطع بشكل مستقل", options.segmentedSync) { options.copy(segmentedSync = it) }
                    Toggle("اختيار أفضل ترجمة تلقائياً", options.chooseBestSubtitle) { options.copy(chooseBestSubtitle = it) }
                    Toggle("حفظ سجل المزامنة محلياً", options.keepHistory) { options.copy(keepHistory = it) }
                    Toggle("حفظ نسخة مصححة في الملفات", options.exportSynced) { options.copy(exportSynced = it) }
                    Toggle("مزامنة ترجمة التنزيلات عند توفر مرجع", options.offlineSync) { options.copy(offlineSync = it) }
                    Choice("لغة الصوت", options.speechLocale, SubtitleEnhancementOptions.speechLocales,
                        listOf("الإنجليزية", "العربية", "الفرنسية", "الإسبانية", "الألمانية", "الإيطالية", "اليابانية", "الكورية", "الصينية", "الهندية", "التركية", "البرتغالية")) {
                        SubtitleEnhancements.update { old -> old.copy(speechLocale = it) }
                    }
                    Toggle("التعرف على الكلام على الجهاز فقط", options.onDeviceOnly) { options.copy(onDeviceOnly = it) }
                    Text("مدة عينة الصوت: ${options.analysisMinutes} دقائق")
                    Slider(value = options.analysisMinutes.toFloat(), onValueChange = { value ->
                        SubtitleEnhancements.update { it.copy(analysisMinutes = value.roundToInt()) }
                    }, valueRange = 1f..12f, steps = 10)
                    HorizontalDivider()
                    Text("النص العربي", style = MaterialTheme.typography.titleMedium)
                    Toggle("تحسين اتجاه العربية", options.arabicDirection) { options.copy(arabicDirection = it) }
                    Toggle("علامات الترقيم العربية", options.arabicPunctuation) { options.copy(arabicPunctuation = it) }
                    Toggle("تقسيم الأسطر الطويلة", options.wrapLines) { options.copy(wrapLines = it) }
                    if (options.wrapLines) {
                        Text("طول السطر: ${options.lineLength} حرفاً")
                        Slider(value = options.lineLength.toFloat(), onValueChange = { value ->
                            SubtitleEnhancements.update { it.copy(lineLength = value.roundToInt()) }
                        }, valueRange = 24f..80f, steps = 55)
                    }
                    Toggle("إخفاء أوصاف الأصوات", options.removeSdh) { options.copy(removeSdh = it) }
                    Toggle("إخفاء الأسطر الموسيقية", options.removeMusic) { options.copy(removeMusic = it) }
                    Toggle("القاموس الشخصي", options.glossaryEnabled) { options.copy(glossaryEnabled = it) }
                    if (options.glossaryEnabled) OutlinedTextField(
                        value = options.glossary,
                        onValueChange = { value -> SubtitleEnhancements.update { it.copy(glossary = value) } },
                        label = { Text("الاسم الأصلي=الاسم البديل") }, minLines = 3, modifier = Modifier.fillMaxWidth(),
                    )
                    Toggle("حجب الكلمات المحددة", options.filterWords) { options.copy(filterWords = it) }
                    if (options.filterWords) OutlinedTextField(
                        value = options.blockedWords,
                        onValueChange = { value -> SubtitleEnhancements.update { it.copy(blockedWords = value) } },
                        label = { Text("كلمة في كل سطر") }, minLines = 3, modifier = Modifier.fillMaxWidth(),
                    )
                    HorizontalDivider()
                    Toggle("عرض ترجمتين", options.dualSubtitles) { options.copy(dualSubtitles = it) }
                    if (options.dualSubtitles) Choice("اللغة الثانية", options.secondaryLanguage,
                        SubtitleEnhancementOptions.subtitleLanguages,
                        listOf("الإنجليزية", "العربية", "الفرنسية", "الإسبانية", "الألمانية", "اليابانية", "الكورية", "الصينية", "التركية", "البرتغالية")) {
                        SubtitleEnhancements.update { old -> old.copy(secondaryLanguage = it) }
                    }
                    HorizontalDivider()
                    Text("مظهر الترجمة", style = MaterialTheme.typography.titleMedium)
                    val presets = listOf(
                        "افتراضي" to SubtitleStyleState.DEFAULT,
                        "واضح" to SubtitleStyleState(fontSizeSp = 24, bold = true, backgroundColor = Color.Black.copy(alpha = 0.65f)),
                        "سينمائي" to SubtitleStyleState(fontSizeSp = 20, textColor = Color(0xFFFFD700), outlineWidth = 3),
                    )
                    for ((name, style) in presets) TextButton(onClick = { PlayerSettingsRepository.setSubtitleStyle(style) }) { Text(name) }
                    val style by PlayerSettingsRepository.uiState.collectAsState()
                    Text("معاينة الترجمة العربية • Nuvio", color = style.subtitleStyle.textColor,
                        fontSize = style.subtitleStyle.fontSizeSp.sp,
                        fontWeight = if (style.subtitleStyle.bold) androidx.compose.ui.text.font.FontWeight.Bold else androidx.compose.ui.text.font.FontWeight.Normal)
                    HorizontalDivider()
                    TextButton(onClick = { SubtitleStudioLauncher.show() }, enabled = SubtitleStudioLauncher.available) {
                        Text("استوديو الترجمة: تحرير وترجمة وتصدير")
                    }
                }
            },
        )
    }
}

@Composable
private fun Toggle(title: String, value: Boolean, change: (Boolean) -> SubtitleEnhancementOptions) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(title, Modifier.weight(1f).padding(end = 8.dp))
        Switch(checked = value, onCheckedChange = { SubtitleEnhancements.update { _ -> change(it) } })
    }
}

@Composable
private fun Choice(title: String, selected: String, values: List<String>, labels: List<String>, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { expanded = true }) { Text("$title: ${labels.getOrElse(values.indexOf(selected)) { selected }}") }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            values.forEachIndexed { index, value ->
                DropdownMenuItem(text = { Text(labels[index]) }, onClick = { onSelect(value); expanded = false })
            }
        }
    }
}

/** The native studio is registered by the iOS application at launch. */
object SubtitleStudioLauncher {
    private var presenter: SubtitleStudioPresenter? = null
    val available: Boolean get() = presenter != null
    fun register(presenter: SubtitleStudioPresenter) { this.presenter = presenter }
    fun show() { presenter?.show() }
}

interface SubtitleStudioPresenter { fun show() }
