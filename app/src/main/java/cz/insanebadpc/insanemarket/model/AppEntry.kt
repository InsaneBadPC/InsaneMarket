package cz.insanebadpc.insanemarket.model

data class AppEntry(
    val repoName: String,
    val repoFullName: String,
    val repoUrl: String,
    var displayName: String,
    var description: String,
    val language: String?,
    val latestTag: String?,
    val latestName: String?,
    var apkUrl: String?,
    val assetName: String?,
    val assetSize: Long?,
    val assetDigest: String?,
    val publishedAt: String?,
    val releaseNotes: String?,
    val manifestPackage: String? = null,
    val apkApiUrl: String? = null,
    var webUrl: String? = null,
    var iconUrl: String? = null,
    val isPrivate: Boolean = false,
    val isExternal: Boolean = false,
    val isManual: Boolean = false,
    val starsCount: Int = 0,
    val forksCount: Int = 0,
    val defaultBranch: String = "main",
    var userRating: Int = 0,
    var isHidden: Boolean = false,
    var installedVersion: String? = null,
    var installedVersionCode: Long? = null,
    var packageName: String? = null,
    var hasUpdate: Boolean = false,
    var isInstalled: Boolean = false,
    var isDownloading: Boolean = false,
    var downloadProgress: Int = 0,
    var downloadReady: Boolean = false,
    var note: String? = null,
) {
    val hasApk: Boolean
        get() = !apkUrl.isNullOrBlank()

    val hasWebApp: Boolean
        get() = !webUrl.isNullOrBlank()

    fun matches(query: String): Boolean {
        if (query.isBlank()) return true
        val q = query.trim().lowercase()
        return listOfNotNull(
            displayName,
            repoName,
            repoFullName,
            description,
            latestTag,
            latestName,
            packageName,
            manifestPackage,
            language,
            webUrl,
        ).any { it.lowercase().contains(q) }
    }
}
