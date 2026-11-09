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
    var status: String = ""
) {
    fun matches(query: String): Boolean {
        if (query.isBlank()) return true
        val q = query.lowercase()
        return listOfNotNull(displayName, repoName, description, latestTag, packageName, manifestPackage, language)
            .any { it.lowercase().contains(q) }
    }
}
