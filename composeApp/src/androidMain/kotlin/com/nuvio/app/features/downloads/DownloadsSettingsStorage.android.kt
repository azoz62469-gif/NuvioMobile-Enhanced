package com.nuvio.app.features.downloads

import android.content.Context
import android.content.SharedPreferences

actual object DownloadsSettingsStorage {
    actual fun loadWatchedCleanup(): Boolean? = preferences?.getBoolean("watched_download_cleanup", false)
    actual fun saveWatchedCleanup(value: Boolean) {
        preferences?.edit()?.putBoolean("watched_download_cleanup", value)?.apply()
    }
    actual fun loadSeasonDownloads(): Boolean? = preferences?.getBoolean("season_downloads_enabled", false)
    actual fun saveSeasonDownloads(value: Boolean) {
        preferences?.edit()?.putBoolean("season_downloads_enabled", value)?.apply()
    }
    actual fun loadStorageLimitGb(): Int? = preferences?.getInt("download_storage_limit_gb", 0)
    actual fun saveStorageLimitGb(value: Int) {
        preferences?.edit()?.putInt("download_storage_limit_gb", value)?.apply()
    }
    private const val preferencesName = "nuvio_downloads_settings"
    private const val allowMobileDataDownloadsKey = "allow_mobile_data_downloads"
    private const val showDownloadButtonKey = "show_download_button"

    private var preferences: SharedPreferences? = null

    fun initialize(context: Context) {
        preferences = context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
    }

    actual fun loadAllowMobileDataDownloads(): Boolean? =
        preferences?.let { prefs ->
            if (prefs.contains(allowMobileDataDownloadsKey)) {
                prefs.getBoolean(allowMobileDataDownloadsKey, false)
            } else {
                null
            }
        }

    actual fun saveAllowMobileDataDownloads(enabled: Boolean) {
        preferences
            ?.edit()
            ?.putBoolean(allowMobileDataDownloadsKey, enabled)
            ?.apply()
    }

    actual fun loadShowDownloadButton(): Boolean? =
        preferences?.let { prefs ->
            if (prefs.contains(showDownloadButtonKey)) {
                prefs.getBoolean(showDownloadButtonKey, false)
            } else {
                null
            }
        }

    actual fun saveShowDownloadButton(enabled: Boolean) {
        preferences
            ?.edit()
            ?.putBoolean(showDownloadButtonKey, enabled)
            ?.apply()
    }
}
