package cz.insanebadpc.insanemarket.data

import android.content.Context

class AppStateStore(context: Context) {
    private val prefs = context.getSharedPreferences("insanemarket_state", Context.MODE_PRIVATE)

    fun lastSeenTag(repoFullName: String): String? = prefs.getString("seen:$repoFullName", null)
    fun markSeen(repoFullName: String, tag: String?) { if (!tag.isNullOrBlank()) prefs.edit().putString("seen:$repoFullName", tag).apply() }

    fun githubToken(): String? = prefs.getString("github_token", null)?.ifBlank { null }
    fun setGithubToken(token: String?) { prefs.edit().putString("github_token", token?.trim().orEmpty()).apply() }

    fun owner(): String = prefs.getString("owner", "InsaneBadPC")?.ifBlank { "InsaneBadPC" } ?: "InsaneBadPC"
    fun setOwner(owner: String) { prefs.edit().putString("owner", owner.trim().ifBlank { "InsaneBadPC" }).apply() }

    fun lastScanTime(): Long = prefs.getLong("last_scan", 0L)
    fun setLastScanTime(value: Long) { prefs.edit().putLong("last_scan", value).apply() }

    fun packageForRepo(repoFullName: String): String? = prefs.getString("pkg:$repoFullName", null)?.ifBlank { null }
    fun setPackageForRepo(repoFullName: String, packageName: String?) {
        if (!packageName.isNullOrBlank()) prefs.edit().putString("pkg:$repoFullName", packageName.trim()).apply()
    }
}
