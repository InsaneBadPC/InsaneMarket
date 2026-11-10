package cz.insanebadpc.insanemarket.data

import cz.insanebadpc.insanemarket.model.AppEntry
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class CachedScan(val entries: List<AppEntry>, val savedAt: Long)

/**
 * Malá JSON mezipaměť seznamu aplikací. Šetří GitHub API limit při častém
 * otevírání aplikace; pull-to-refresh ji vždy obejde.
 */
class ScanCache(private val file: File) {
    fun load(owner: String, maxAgeMs: Long): CachedScan? {
        if (!file.isFile) return null
        val text = try {
            file.readText()
        } catch (_: Exception) {
            return null
        }
        return try {
            val root = JSONObject(text)
            if (root.optString("owner") != owner) return null
            val savedAt = root.optLong("savedAt")
            if (savedAt <= 0L || System.currentTimeMillis() - savedAt > maxAgeMs) return null
            val array = root.optJSONArray("entries") ?: return null
            val entries = (0 until array.length()).mapNotNull { index ->
                array.optJSONObject(index)?.let(::fromJson)
            }
            if (entries.isEmpty()) null else CachedScan(entries, savedAt)
        } catch (_: Exception) {
            null
        }
    }

    fun save(owner: String, entries: List<AppEntry>) {
        val root = JSONObject()
            .put("owner", owner)
            .put("savedAt", System.currentTimeMillis())
            .put("entries", JSONArray(entries.map(::toJson)))
        val temp = File(file.parentFile, "${file.name}.tmp")
        try {
            file.parentFile?.mkdirs()
            temp.writeText(root.toString())
            if (!temp.renameTo(file)) {
                temp.copyTo(file, overwrite = true)
                temp.delete()
            }
        } catch (_: Exception) {
            temp.delete()
        }
    }

    private fun toJson(entry: AppEntry): JSONObject = JSONObject().apply {
        put("repoName", entry.repoName)
        put("repoFullName", entry.repoFullName)
        put("repoUrl", entry.repoUrl)
        put("displayName", entry.displayName)
        put("description", entry.description.take(8000))
        put("language", entry.language ?: JSONObject.NULL)
        put("latestTag", entry.latestTag ?: JSONObject.NULL)
        put("latestName", entry.latestName ?: JSONObject.NULL)
        put("apkUrl", entry.apkUrl ?: JSONObject.NULL)
        put("assetName", entry.assetName ?: JSONObject.NULL)
        put("assetSize", entry.assetSize ?: JSONObject.NULL)
        put("assetDigest", entry.assetDigest ?: JSONObject.NULL)
        put("publishedAt", entry.publishedAt ?: JSONObject.NULL)
        put("releaseNotes", entry.releaseNotes?.take(8000) ?: JSONObject.NULL)
        put("manifestPackage", entry.manifestPackage ?: JSONObject.NULL)
    }

    private fun fromJson(objectJson: JSONObject): AppEntry? {
        val repoName = objectJson.optString("repoName")
        val repoFullName = objectJson.optString("repoFullName")
        if (repoName.isBlank() || repoFullName.isBlank()) return null
        return AppEntry(
            repoName = repoName,
            repoFullName = repoFullName,
            repoUrl = objectJson.optString("repoUrl"),
            displayName = objectJson.optString("displayName").ifBlank { repoName },
            description = objectJson.optString("description"),
            language = objectJson.stringOrNull("language"),
            latestTag = objectJson.stringOrNull("latestTag"),
            latestName = objectJson.stringOrNull("latestName"),
            apkUrl = objectJson.stringOrNull("apkUrl"),
            assetName = objectJson.stringOrNull("assetName"),
            assetSize = objectJson.optLong("assetSize").takeIf { it > 0 },
            assetDigest = objectJson.stringOrNull("assetDigest"),
            publishedAt = objectJson.stringOrNull("publishedAt"),
            releaseNotes = objectJson.stringOrNull("releaseNotes"),
            manifestPackage = objectJson.stringOrNull("manifestPackage"),
        )
    }

    private fun JSONObject.stringOrNull(key: String): String? =
        optString(key).takeIf { it.isNotBlank() }
}
