package cz.insanebadpc.insanemarket.model

data class RepoFileItem(
    val name: String,
    val path: String,
    val type: String, // "file" or "dir"
    val size: Long,
    val htmlUrl: String?,
    val downloadUrl: String?,
) {
    val isDir: Boolean get() = type.equals("dir", ignoreCase = true)
    val isApk: Boolean get() = !isDir && name.endsWith(".apk", ignoreCase = true)
}

data class RepoReleaseAsset(
    val name: String,
    val size: Long,
    val downloadUrl: String,
    val apiUrl: String?,
    val digest: String?,
) {
    val isApk: Boolean get() = name.endsWith(".apk", ignoreCase = true)
}

data class RepoReleaseInfo(
    val tagName: String,
    val name: String,
    val publishedAt: String?,
    val prerelease: Boolean,
    val body: String?,
    val htmlUrl: String?,
    val assets: List<RepoReleaseAsset>,
)

data class RepoDetailedInfo(
    val fullName: String,
    val name: String,
    val ownerLogin: String,
    val ownerAvatarUrl: String?,
    val description: String,
    val htmlUrl: String,
    val language: String?,
    val defaultBranch: String,
    val starsCount: Int,
    val forksCount: Int,
    val openIssuesCount: Int,
    val isPrivate: Boolean,
    val hasPages: Boolean,
    val pagesUrl: String?,
    val homepageUrl: String?,
    val updatedAt: String?,
    val topics: List<String>,
    val releases: List<RepoReleaseInfo>,
    val readmeText: String?,
)
