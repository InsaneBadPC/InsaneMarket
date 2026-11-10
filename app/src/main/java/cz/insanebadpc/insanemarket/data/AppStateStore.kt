package cz.insanebadpc.insanemarket.data

import android.content.Context

data class KnownRelease(val tag: String?, val versionCode: Long?)
data class DownloadedApk(
    val path: String,
    val tag: String?,
    val versionCode: Long?,
    val packageName: String?,
)

class AppStateStore(context: Context) {
    private val prefs = context.getSharedPreferences("insanemarket_state", Context.MODE_PRIVATE)

    fun installedTag(repoFullName: String): String? = prefs.getString("installed_tag:$repoFullName", null)?.ifBlank { null }

    fun setInstalledTag(repoFullName: String, tag: String?) {
        if (tag.isNullOrBlank()) prefs.edit().remove("installed_tag:$repoFullName").apply()
        else prefs.edit().putString("installed_tag:$repoFullName", tag).apply()
    }

    fun githubToken(): String? = prefs.getString("github_token", null)?.ifBlank { null }

    fun setGithubToken(token: String?) {
        prefs.edit().putString("github_token", token?.trim().orEmpty()).apply()
    }

    fun owner(): String = prefs.getString("owner", GitHubMarket.DEFAULT_OWNER)?.ifBlank { GitHubMarket.DEFAULT_OWNER }
        ?: GitHubMarket.DEFAULT_OWNER

    fun setOwner(owner: String) {
        prefs.edit().putString("owner", owner.trim().ifBlank { GitHubMarket.DEFAULT_OWNER }).apply()
    }

    fun lastScanTime(): Long = prefs.getLong("last_scan", 0L)

    fun setLastScanTime(value: Long) {
        prefs.edit().putLong("last_scan", value).apply()
    }

    fun packageForRepo(repoFullName: String): String? =
        prefs.getString("pkg:$repoFullName", null)?.ifBlank { null }

    fun setPackageForRepo(repoFullName: String, packageName: String?) {
        if (packageName.isNullOrBlank()) return
        prefs.edit()
            .putString("pkg:$repoFullName", packageName.trim())
            .putString("pkg_owner:${packageName.trim()}", repoFullName)
            .apply()
    }

    fun packageOwner(packageName: String): String? =
        prefs.getString("pkg_owner:$packageName", null)?.ifBlank { null }

    fun setPackageOwner(packageName: String, repoFullName: String) {
        if (packageName.isBlank() || repoFullName.isBlank()) return
        prefs.edit().putString("pkg_owner:$packageName", repoFullName).apply()
    }

    fun knownRelease(repoFullName: String): KnownRelease? {
        val tag = prefs.getString("known_tag:$repoFullName", null)?.ifBlank { null }
        val versionCode = prefs.getLong("known_vc:$repoFullName", 0L).takeIf { it > 0 }
        if (tag == null && versionCode == null) return null
        return KnownRelease(tag, versionCode)
    }

    fun setKnownRelease(repoFullName: String, tag: String?, versionCode: Long?) {
        val editor = prefs.edit()
        if (tag.isNullOrBlank()) editor.remove("known_tag:$repoFullName")
        else editor.putString("known_tag:$repoFullName", tag)
        if (versionCode != null && versionCode > 0) editor.putLong("known_vc:$repoFullName", versionCode)
        else editor.remove("known_vc:$repoFullName")
        editor.apply()
    }

    fun downloadedApk(repoFullName: String): DownloadedApk? {
        val path = prefs.getString("apk_path:$repoFullName", null)?.ifBlank { null } ?: return null
        return DownloadedApk(
            path = path,
            tag = prefs.getString("apk_tag:$repoFullName", null)?.ifBlank { null },
            versionCode = prefs.getLong("apk_vc:$repoFullName", 0L).takeIf { it > 0 },
            packageName = prefs.getString("apk_pkg:$repoFullName", null)?.ifBlank { null },
        )
    }

    fun setDownloadedApk(
        repoFullName: String,
        path: String,
        tag: String?,
        versionCode: Long?,
        packageName: String?,
    ) {
        val editor = prefs.edit().putString("apk_path:$repoFullName", path)
        if (tag.isNullOrBlank()) editor.remove("apk_tag:$repoFullName")
        else editor.putString("apk_tag:$repoFullName", tag)
        if (versionCode != null && versionCode > 0) editor.putLong("apk_vc:$repoFullName", versionCode)
        else editor.remove("apk_vc:$repoFullName")
        if (packageName.isNullOrBlank()) editor.remove("apk_pkg:$repoFullName")
        else editor.putString("apk_pkg:$repoFullName", packageName)
        editor.apply()
    }

    fun clearDownloadedApk(repoFullName: String) {
        prefs.edit()
            .remove("apk_path:$repoFullName")
            .remove("apk_tag:$repoFullName")
            .remove("apk_vc:$repoFullName")
            .remove("apk_pkg:$repoFullName")
            .apply()
    }
}
