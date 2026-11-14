package cz.insanebadpc.insanemarket.util

import android.content.Context
import cz.insanebadpc.insanemarket.model.AppEntry
import cz.insanebadpc.insanemarket.model.AppOverride
import org.json.JSONObject

class LocalOverrides(context: Context) {
    private val prefs = context.getSharedPreferences("insanemarket_overrides", Context.MODE_PRIVATE)

    fun get(repoFullName: String): AppOverride? {
        val raw = prefs.getString(repoFullName, null) ?: return null
        return try {
            val o = JSONObject(raw)
            AppOverride(
                displayName = o.optString("displayName").ifBlank { null },
                description = o.optString("description").ifBlank { null },
                webUrl = o.optString("webUrl").ifBlank { null },
                apkUrl = o.optString("apkUrl").ifBlank { null },
                iconUrl = o.optString("iconUrl").ifBlank { null },
            )
        } catch (_: Exception) {
            null
        }
    }

    fun set(
        repoFullName: String,
        displayName: String?,
        description: String?,
        webUrl: String? = null,
        apkUrl: String? = null,
        iconUrl: String? = null,
    ) {
        val existing = get(repoFullName)
        val o = JSONObject()
            .put("displayName", displayName ?: existing?.displayName ?: "")
            .put("description", description ?: existing?.description ?: "")
            .put("webUrl", webUrl ?: existing?.webUrl ?: "")
            .put("apkUrl", apkUrl ?: existing?.apkUrl ?: "")
            .put("iconUrl", iconUrl ?: existing?.iconUrl ?: "")
        prefs.edit().putString(repoFullName, o.toString()).apply()
    }

    fun applyTo(entries: MutableList<AppEntry>) {
        for (e in entries) {
            get(e.repoFullName)?.let { ov ->
                if (!ov.displayName.isNullOrBlank()) e.displayName = ov.displayName
                if (!ov.description.isNullOrBlank()) e.description = ov.description
                if (!ov.webUrl.isNullOrBlank()) e.webUrl = ov.webUrl
                if (!ov.apkUrl.isNullOrBlank()) e.apkUrl = ov.apkUrl
                if (!ov.iconUrl.isNullOrBlank()) e.iconUrl = ov.iconUrl
            }
        }
    }
}
