package com.nuvio.app.features.settings

import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.*
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.fillMaxWidth
import com.nuvio.app.features.downloads.DownloadsRepository
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.features.downloads.DownloadsSettingsRepository
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.compose_settings_root_downloads_title
import nuvio.composeapp.generated.resources.settings_downloads_location_private
import nuvio.composeapp.generated.resources.settings_downloads_location_reset
import nuvio.composeapp.generated.resources.downloads_allow_mobile_data_description
import nuvio.composeapp.generated.resources.downloads_allow_mobile_data_title
import nuvio.composeapp.generated.resources.downloads_show_download_button_description
import nuvio.composeapp.generated.resources.downloads_show_download_button_title
import nuvio.composeapp.generated.resources.settings_downloads_location_title
import org.jetbrains.compose.resources.stringResource

internal fun LazyListScope.downloadsSettingsContent(
    isTablet: Boolean,
) {
    item {
        DownloadsRepository.ensureLoaded()
        val downloadLocationUri by DownloadsSettingsRepository.downloadLocationUri.collectAsStateWithLifecycle()
        val allowMobileData by DownloadsSettingsRepository.allowMobileDataDownloads.collectAsStateWithLifecycle()
        val showDownloadButton by DownloadsSettingsRepository.showDownloadButton.collectAsStateWithLifecycle()
        var showPicker by remember { mutableStateOf(false) }
        var showLimit by remember { mutableStateOf(false) }
        val limit by DownloadsSettingsRepository.storageLimitGb.collectAsStateWithLifecycle()
        val seasonDownloads by DownloadsSettingsRepository.seasonDownloads.collectAsStateWithLifecycle()
        val watchedCleanup by DownloadsSettingsRepository.watchedCleanup.collectAsStateWithLifecycle()
        val downloads by DownloadsRepository.uiState.collectAsStateWithLifecycle()

        SettingsSection(
            title = stringResource(Res.string.compose_settings_root_downloads_title),
            isTablet = isTablet,
        ) {
            SettingsGroup(isTablet = isTablet) {
                if (com.nuvio.app.isIos) {
                    SettingsSwitchRow(
                        title = "حذف الحلقات المشاهدة تلقائياً",
                        description = "بعد أسبوع من مشاهدتها، عند بدء تنزيل جديد وخارج وقت التشغيل",
                        checked = watchedCleanup,
                        isTablet = isTablet,
                        onCheckedChange = DownloadsSettingsRepository::setWatchedCleanup,
                    )
                    SettingsGroupDivider(isTablet = isTablet)
                }
                SettingsSwitchRow(
                    title = "تنزيل موسم كامل",
                    description = "إظهار التنزيل الجماعي ضمن قائمة الموسم",
                    checked = seasonDownloads,
                    isTablet = isTablet,
                    onCheckedChange = DownloadsSettingsRepository::setSeasonDownloads,
                )
                SettingsGroupDivider(isTablet = isTablet)
                SettingsNavigationRow(
                    title = "حد مساحة التنزيلات",
                    description = if (limit == 0) "بدون حد اختياري" else "$limit غيغابايت",
                    isTablet = isTablet,
                    onClick = { showLimit = true },
                )
                SettingsGroupDivider(isTablet = isTablet)
                Text("المساحة المستخدمة: ${downloads.items.sumOf { it.downloadedBytes } / (1024 * 1024)} ميغابايت")
                Text("الملفات المكتملة: ${downloads.completedItems.size} • قيد التنزيل أو الانتظار: ${downloads.activeItems.size}")
                SettingsGroupDivider(isTablet = isTablet)
                SettingsSwitchRow(
                    title = stringResource(Res.string.downloads_allow_mobile_data_title),
                    description = stringResource(Res.string.downloads_allow_mobile_data_description),
                    checked = allowMobileData,
                    isTablet = isTablet,
                    onCheckedChange = DownloadsSettingsRepository::setAllowMobileDataDownloads,
                )
                SettingsGroupDivider(isTablet = isTablet)
                SettingsSwitchRow(
                    title = stringResource(Res.string.downloads_show_download_button_title),
                    description = stringResource(Res.string.downloads_show_download_button_description),
                    checked = showDownloadButton,
                    isTablet = isTablet,
                    onCheckedChange = DownloadsSettingsRepository::setShowDownloadButton,
                )
                SettingsGroupDivider(isTablet = isTablet)
                SettingsNavigationRow(
                    title = stringResource(Res.string.settings_downloads_location_title),
                    description = downloadLocationUri?.let { formatUriForDisplay(it) } ?: stringResource(Res.string.settings_downloads_location_private),
                    isTablet = isTablet,
                    onClick = { showPicker = true },
                )
                if (downloadLocationUri != null) {
                    SettingsGroupDivider(isTablet = isTablet)
                    SettingsClickableRow(
                        title = stringResource(Res.string.settings_downloads_location_reset),
                        isTablet = isTablet,
                        onClick = { DownloadsSettingsRepository.setDownloadLocationUri(null) },
                    )
                }
            }
        }

        if (showPicker) {
            DownloadLocationPicker(
                onLocationSelected = { uri ->
                    DownloadsSettingsRepository.setDownloadLocationUri(uri)
                    showPicker = false
                },
                onDismiss = { showPicker = false },
            )
        }
        if (showLimit) AlertDialog(
            onDismissRequest = { showLimit = false },
            title = { Text("حد المساحة") },
            text = {
                Column {
                    for (value in listOf(0, 4, 8, 16, 32, 64, 128, 256)) {
                        TextButton(modifier = Modifier.fillMaxWidth(), onClick = {
                            DownloadsSettingsRepository.setStorageLimitGb(value)
                            showLimit = false
                        }) { Text(if (value == 0) "بدون حد" else "$value غيغابايت") }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showLimit = false }) { Text("إغلاق") } },
        )
    }
}

@Composable
internal expect fun DownloadLocationPicker(
    onLocationSelected: (uri: String) -> Unit,
    onDismiss: () -> Unit,
)

internal expect fun formatUriForDisplay(uri: String): String
