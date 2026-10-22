package com.nuvio.app.features.downloads

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Device-level download preferences. Not synced across profiles/devices: whether the
 * current device is fine using mobile data for downloads, and where downloads are saved,
 * are inherently per-device facts, not per-profile ones.
 */
object DownloadsSettingsRepository {
    private val _watchedCleanup = MutableStateFlow(false)
    val watchedCleanup: StateFlow<Boolean> = _watchedCleanup.asStateFlow()
    fun setWatchedCleanup(value: Boolean) {
        ensureLoaded()
        _watchedCleanup.value = value
        DownloadsSettingsStorage.saveWatchedCleanup(value)
    }
    private val _seasonDownloads = MutableStateFlow(false)
    val seasonDownloads: StateFlow<Boolean> = _seasonDownloads.asStateFlow()
    fun setSeasonDownloads(value: Boolean) {
        ensureLoaded()
        _seasonDownloads.value = value
        DownloadsSettingsStorage.saveSeasonDownloads(value)
        if (!value) SeasonDownloadQueue.cancel()
    }
    private val _storageLimitGb = MutableStateFlow(0)
    val storageLimitGb: StateFlow<Int> = _storageLimitGb.asStateFlow()

    fun setStorageLimitGb(value: Int) {
        ensureLoaded()
        _storageLimitGb.value = value.coerceIn(0, 1024)
        DownloadsSettingsStorage.saveStorageLimitGb(_storageLimitGb.value)
    }
    private val _allowMobileDataDownloads = MutableStateFlow(false)
    val allowMobileDataDownloads: StateFlow<Boolean> = _allowMobileDataDownloads.asStateFlow()

    private val _showDownloadButton = MutableStateFlow(true)
    val showDownloadButton: StateFlow<Boolean> = _showDownloadButton.asStateFlow()

    private val _downloadLocationUri = MutableStateFlow<String?>(null)
    val downloadLocationUri: StateFlow<String?> = _downloadLocationUri.asStateFlow()

    private var hasLoaded = false

    fun ensureLoaded() {
        if (hasLoaded) return
        hasLoaded = true
        _watchedCleanup.value = DownloadsSettingsStorage.loadWatchedCleanup() ?: false
        _seasonDownloads.value = DownloadsSettingsStorage.loadSeasonDownloads() ?: false
        _storageLimitGb.value = (DownloadsSettingsStorage.loadStorageLimitGb() ?: 0).coerceIn(0, 1024)
        _allowMobileDataDownloads.value = DownloadsSettingsStorage.loadAllowMobileDataDownloads() ?: false
        _showDownloadButton.value = DownloadsSettingsStorage.loadShowDownloadButton() ?: true
        _downloadLocationUri.value = DownloadsStorage.getDownloadLocationUri()
    }

    fun setAllowMobileDataDownloads(enabled: Boolean) {
        ensureLoaded()
        if (_allowMobileDataDownloads.value == enabled) return
        _allowMobileDataDownloads.value = enabled
        DownloadsSettingsStorage.saveAllowMobileDataDownloads(enabled)
    }

    fun setShowDownloadButton(enabled: Boolean) {
        ensureLoaded()
        if (_showDownloadButton.value == enabled) return
        _showDownloadButton.value = enabled
        DownloadsSettingsStorage.saveShowDownloadButton(enabled)
    }

    fun setDownloadLocationUri(uri: String?) {
        ensureLoaded()
        _downloadLocationUri.value = uri
        DownloadsStorage.setDownloadLocationUri(uri)
    }

    // No-op: called from DownloadsRepository.onProfileChanged() for symmetry with the other
    // per-profile repositories it resets, but these settings are device-level (see class doc)
    // and already loaded once for the whole process lifetime, so there's nothing to reload.
    fun onProfileChanged() = Unit
}
