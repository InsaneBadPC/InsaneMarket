package cz.insanebadpc.insanemarket.data

import android.content.Context
import org.json.JSONObject

class AppStateStore(context: Context) {
    private val prefs = context.getSharedPreferences("insanemarket_state", Context.MODE_PRIVATE)

    fun lastSeenTag(repoFullName: String): String? = prefs.getString("seen:$repoFullName", null)
    fun markSeen(repoFullName: String, tag: String?) { if (!tag.isNullOrBlank()) prefs.edit().putString("seen:$repoFullName", tag).apply() }

    fun githubToken(): String? = prefs.getString("github_token", null)?.ifBlank { null }
    fun setGithubToken(token: String?) { prefs.edit().putString("github_token", token ?: "").apply() }
}
