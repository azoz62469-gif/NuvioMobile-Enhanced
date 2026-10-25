package com.nuvio.app.features.updater

import com.nuvio.app.features.addons.httpRequestRaw
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.updates_github_api_error
import org.jetbrains.compose.resources.getString

@Serializable
internal data class GitHubReleaseDto(
    @SerialName("tag_name") val tagName: String? = null,
    val name: String? = null,
    val body: String? = null,
    val draft: Boolean = false,
    val prerelease: Boolean = false,
    @SerialName("html_url") val htmlUrl: String? = null,
    @SerialName("published_at") val publishedAt: String? = null,
    val assets: List<GitHubAssetDto> = emptyList(),
)

@Serializable
internal data class GitHubAssetDto(
    val name: String,
    @SerialName("browser_download_url") val browserDownloadUrl: String,
    val size: Long? = null,
    @SerialName("content_type") val contentType: String? = null,
)

internal class NoChannelReleaseException : IllegalStateException()

internal const val GITHUB_API_BASE = "https://api.github.com"
internal const val GITHUB_OWNER = "azoz62469-gif"
internal const val GITHUB_REPO = "NuvioMobile-Enhanced"

internal object AppUpdaterRepository {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    suspend fun getLatestChannelUpdate(channel: UpdateChannel): Result<AppUpdate> = runCatching {
        val response = httpRequestRaw(
            method = "GET",
            url = "$GITHUB_API_BASE/repos/$GITHUB_OWNER/$GITHUB_REPO/${releasePath(channel)}",
            headers = mapOf(
                "Accept" to "application/vnd.github+json",
                "User-Agent" to "NuvioMobile",
            ),
            body = "",
        )
        currentCoroutineContext().ensureActive()
        if (response.status == 404) throw NoChannelReleaseException()
        if (response.status !in 200..299) {
            error(getString(Res.string.updates_github_api_error, response.status))
        }
        selectUpdate(response.body, channel, AppUpdaterPlatform.getSupportedAbis())
            ?: throw NoChannelReleaseException()
    }

    internal fun releasePath(channel: UpdateChannel): String = when (channel) {
        UpdateChannel.STABLE -> "releases/latest"
        UpdateChannel.BETA -> "releases?per_page=100"
    }

    internal fun selectUpdate(
        responseBody: String,
        channel: UpdateChannel,
        supportedAbis: List<String>,
    ): AppUpdate? {
        val releases = when (channel) {
            UpdateChannel.STABLE -> listOf(json.decodeFromString<GitHubReleaseDto>(responseBody))
            UpdateChannel.BETA -> json.decodeFromString<List<GitHubReleaseDto>>(responseBody)
        }
        return ReleaseSelector.eligibleReleases(releases, channel).firstNotNullOfOrNull { release ->
            val asset = chooseBestUpdateAsset(
                assets = release.assets,
                supportedAbis = supportedAbis,
                extension = AppUpdaterPlatform.updateAssetExtension,
            ) ?: return@firstNotNullOfOrNull null
            val tag = release.tagName?.takeIf { VersionUtils.parse(it) != null }
                ?: release.name.orEmpty()
            AppUpdate(
                tag = tag,
                title = release.name?.takeIf { it.isNotBlank() } ?: tag,
                notes = release.body.orEmpty(),
                releaseUrl = release.htmlUrl,
                assetName = asset.name,
                assetUrl = asset.browserDownloadUrl,
                assetSizeBytes = asset.size,
            )
        }
    }

    private fun chooseBestUpdateAsset(
        assets: List<GitHubAssetDto>,
        supportedAbis: List<String>,
        extension: String,
    ): GitHubAssetDto? {
        val normalizedExtension = extension.trim().removePrefix(".").ifBlank { "apk" }
        val updateAssets = assets.filter { asset ->
            asset.name.endsWith(".$normalizedExtension", ignoreCase = true)
        }
        if (updateAssets.isEmpty()) return null
        if (normalizedExtension.equals("ipa", ignoreCase = true)) {
            return updateAssets.firstOrNull { asset ->
                val name = asset.name.lowercase()
                name.contains("enhanced") || name.contains("autosync")
            } ?: updateAssets.first()
        }
        for (abi in supportedAbis) {
            updateAssets.firstOrNull { it.name.contains(abi, ignoreCase = true) }?.let { return it }
        }
        return updateAssets.firstOrNull { asset ->
            val name = asset.name.lowercase()
            name.contains("universal") || name.contains("all")
        } ?: updateAssets.firstOrNull()
    }
}
