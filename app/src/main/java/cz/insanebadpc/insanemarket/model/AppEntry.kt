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
    val publishedAt: String?,
    val releaseNotes: String?,
    var installedVersion: String? = null,
    var installedVersionCode: Long? = null,
    var packageName: String? = null,
    var hasUpdate: Boolean = false,
    var status: String = ""
)
