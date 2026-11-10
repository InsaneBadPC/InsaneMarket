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
    val apkUrl: String?,
    val assetName: String?,
    val assetSize: Long?,
    val assetDigest: String?,
    val publishedAt: String?,
    val releaseNotes: String?,
    val manifestPackage: String? = null,
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
        ).any { it.lowercase().contains(q) }
    }
}
