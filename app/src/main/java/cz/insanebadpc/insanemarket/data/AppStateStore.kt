package cz.insanebadpc.insanemarket.data

import android.content.Context
import cz.insanebadpc.insanemarket.model.AppEntry
import org.json.JSONArray
import org.json.JSONObject

data class KnownRelease(val tag: String?, val versionCode: Long?)
data class DownloadedApk(
    val path: String,
    val tag: String?,
    val versionCode: Long?,
    val packageName: String?,
)

class AppStateStore(context: Context) {
    private val prefs = context.getSharedPreferences("insanemarket_state", Context.MODE_PRIVATE)

    fun installedTag(repoFullName: String): String? =
        prefs.getString("installed_tag:$repoFullName", null)?.ifBlank { null }

    fun setInstalledTag(repoFullName: String, tag: String?) {
        if (tag.isNullOrBlank()) prefs.edit().remove("installed_tag:$repoFullName").apply()
        else prefs.edit().putString("installed_tag:$repoFullName", tag).apply()
    }

    fun githubToken(): String? = prefs.getString("github_token", null)?.ifBlank { null }

    fun setGithubToken(token: String?) {
        prefs.edit().putString("github_token", token?.trim().orEmpty()).apply()
    }

    fun owner(): String =
        prefs.getString("owner", GitHubMarket.DEFAULT_OWNER)?.ifBlank { GitHubMarket.DEFAULT_OWNER }
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

    // --- Hodnocení 1 až 5 hvězdiček ---
    fun userRating(repoFullName: String): Int =
        prefs.getInt("rating:${repoFullName.lowercase()}", 0).coerceIn(0, 5)

    fun setUserRating(repoFullName: String, stars: Int) {
        val clamped = stars.coerceIn(0, 5)
        prefs.edit().putInt("rating:${repoFullName.lowercase()}", clamped).apply()
    }

    // --- Skrývání položek ---
    fun hiddenRepos(): Set<String> =
        prefs.getStringSet("hidden_repos", emptySet())?.toSet() ?: emptySet()

    fun isHidden(repoFullName: String): Boolean =
        repoFullName.lowercase() in hiddenRepos()

    fun setHidden(repoFullName: String, hidden: Boolean) {
        val next = hiddenRepos().toMutableSet()
        if (hidden) next.add(repoFullName.lowercase())
        else next.remove(repoFullName.lowercase())
        prefs.edit().putStringSet("hidden_repos", next).apply()
    }

    // --- Cizí GitHub repozitáře (Sekce 3) ---
    fun externalRepos(): Set<String> =
        prefs.getStringSet("external_repos", emptySet())?.toSet() ?: emptySet()

    fun addExternalRepo(repoFullName: String) {
        val next = externalRepos().toMutableSet()
        next.add(repoFullName.trim())
        prefs.edit().putStringSet("external_repos", next).apply()
    }

    fun removeExternalRepo(repoFullName: String) {
        val next = externalRepos().filterNot { it.equals(repoFullName, ignoreCase = true) }.toSet()
        prefs.edit().putStringSet("external_repos", next).apply()
    }

    // --- Ručně přidané GitHub repozitáře (Sekce 1 / 2) ---
    fun manualRepos(): Set<String> =
        prefs.getStringSet("manual_repos", emptySet())?.toSet() ?: emptySet()

    fun addManualRepo(repoFullName: String) {
        val next = manualRepos().toMutableSet()
        next.add(repoFullName.trim())
        prefs.edit().putStringSet("manual_repos", next).apply()
    }

    fun removeManualRepo(repoFullName: String) {
        val next = manualRepos().filterNot { it.equals(repoFullName, ignoreCase = true) }.toSet()
        prefs.edit().putStringSet("manual_repos", next).apply()
    }

    // --- Ručně přidané přímé URL položky (APK nebo Web App mimo GitHub) ---
    fun customEntries(): List<AppEntry> {
        val raw = prefs.getString("custom_entries_json", null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val full = o.optString("repoFullName").ifBlank { return@mapNotNull null }
                val name = o.optString("displayName").ifBlank { "Vlastní aplikace" }
                AppEntry(
                    repoName = o.optString("repoName").ifBlank { name },
                    repoFullName = full,
                    repoUrl = o.optString("repoUrl"),
                    displayName = name,
                    description = o.optString("description").ifBlank { "Ručně přidaná položka." },
                    language = o.optString("language").takeIf { it.isNotBlank() },
                    latestTag = o.optString("latestTag").takeIf { it.isNotBlank() },
                    latestName = o.optString("latestName").takeIf { it.isNotBlank() },
                    apkUrl = o.optString("apkUrl").takeIf { it.isNotBlank() },
                    assetName = o.optString("assetName").takeIf { it.isNotBlank() },
                    assetSize = o.optLong("assetSize", 0L).takeIf { it > 0 },
                    assetDigest = null,
                    publishedAt = o.optString("publishedAt").takeIf { it.isNotBlank() },
                    releaseNotes = null,
                    webUrl = o.optString("webUrl").takeIf { it.isNotBlank() },
                    iconUrl = o.optString("iconUrl").takeIf { it.isNotBlank() },
                    isManual = true,
                    isExternal = o.optBoolean("isExternal", false),
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun addOrUpdateCustomEntry(entry: AppEntry) {
        val current = customEntries().filterNot { it.repoFullName.equals(entry.repoFullName, ignoreCase = true) }.toMutableList()
        current.add(entry)
        saveCustomEntries(current)
    }

    fun removeCustomEntry(repoFullName: String) {
        val current = customEntries().filterNot { it.repoFullName.equals(repoFullName, ignoreCase = true) }
        saveCustomEntries(current)
    }

    private fun saveCustomEntries(list: List<AppEntry>) {
        val arr = JSONArray()
        for (e in list) {
            arr.put(
                JSONObject()
                    .put("repoName", e.repoName)
                    .put("repoFullName", e.repoFullName)
                    .put("repoUrl", e.repoUrl)
                    .put("displayName", e.displayName)
                    .put("description", e.description)
                    .put("language", e.language ?: "")
                    .put("latestTag", e.latestTag ?: "")
                    .put("latestName", e.latestName ?: "")
                    .put("apkUrl", e.apkUrl ?: "")
                    .put("assetName", e.assetName ?: "")
                    .put("assetSize", e.assetSize ?: 0L)
                    .put("publishedAt", e.publishedAt ?: "")
                    .put("webUrl", e.webUrl ?: "")
                    .put("iconUrl", e.iconUrl ?: "")
                    .put("isExternal", e.isExternal)
            )
        }
        prefs.edit().putString("custom_entries_json", arr.toString()).apply()
    }
}
