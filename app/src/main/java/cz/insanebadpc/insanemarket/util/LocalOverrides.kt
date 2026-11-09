package cz.insanebadpc.insanemarket.util

import android.content.Context
import cz.insanebadpc.insanemarket.model.AppOverride
import org.json.JSONObject

class LocalOverrides(context: Context) {
    private val prefs = context.getSharedPreferences("insanemarket_overrides", Context.MODE_PRIVATE)

    fun get(repoFullName: String): AppOverride? {
        val raw = prefs.getString(repoFullName, null) ?: return null
        return try {
            val o = JSONObject(raw)
            AppOverride(o.optString("displayName").ifBlank { null }, o.optString("description").ifBlank { null })
        } catch (_: Exception) { null }
    }

    fun set(repoFullName: String, displayName: String?, description: String?) {
        val o = JSONObject().put("displayName", displayName ?: "").put("description", description ?: "")
        prefs.edit().putString(repoFullName, o.toString()).apply()
    }

    fun applyTo(entries: MutableList<cz.insanebadpc.insanemarket.model.AppEntry>) {
        for (e in entries) get(e.repoFullName)?.let {
            if (!it.displayName.isNullOrBlank()) e.displayName = it.displayName
            if (!it.description.isNullOrBlank()) e.description = it.description
        }
    }
}
