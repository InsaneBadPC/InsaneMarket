package cz.insanebadpc.insanemarket.data

import cz.insanebadpc.insanemarket.model.AppEntry
import org.json.JSONArray
import org.json.JSONObject

/**
 * Čistá přeměna GitHub JSONu na [AppEntry]. Záměrně bez Android závislostí,
 * aby se dala testovat jednotkovými testy.
 */
object MarketParser {
    private const val DEFAULT_DESCRIPTION = "Aplikace z GitHub release."

    fun parse(repo: JSONObject, release: JSONObject, metadata: JSONObject?): AppEntry? {
        val asset = selectApk(release.optJSONArray("assets") ?: JSONArray()) ?: return null
        val apkUrl = asset.optString("browser_download_url").ifBlank { return null }
        val name = repo.optString("name").ifBlank { return null }
        val full = repo.optString("full_name").ifBlank { return null }

        val metadataPackage = metadata?.stringOrNull("packageName") ?: metadata?.stringOrNull("package")
        val display = metadata?.stringOrNull("displayName")
            ?: metadata?.stringOrNull("name")
            ?: prettify(name)
        val description = metadata?.stringOrNull("description")
            ?: release.stringOrNull("body")
            ?: repo.stringOrNull("description")
            ?: DEFAULT_DESCRIPTION
        val publishedAt = release.stringOrNull("published_at") ?: release.stringOrNull("created_at")
        val digest = asset.stringOrNull("digest")?.takeIf { it.startsWith("sha256:", ignoreCase = true) }

        return AppEntry(
            repoName = name,
            repoFullName = full,
            repoUrl = repo.optString("html_url"),
            displayName = display,
            description = description.trim(),
            language = repo.stringOrNull("language"),
            latestTag = release.stringOrNull("tag_name"),
            latestName = release.stringOrNull("name"),
            apkUrl = apkUrl,
            assetName = asset.optString("name").ifBlank { null },
            assetSize = asset.optLong("size").takeIf { it > 0 },
            assetDigest = digest,
            publishedAt = publishedAt,
            releaseNotes = release.stringOrNull("body"),
            manifestPackage = metadataPackage,
        )
    }

    fun selectApk(assets: JSONArray): JSONObject? {
        var best: JSONObject? = null
        var bestScore = Int.MIN_VALUE
        for (i in 0 until assets.length()) {
            val asset = assets.optJSONObject(i) ?: continue
            val name = asset.optString("name")
            if (!name.endsWith(".apk", ignoreCase = true)) continue
            if (asset.optString("browser_download_url").isBlank()) continue
            val state = asset.optString("state").ifBlank { "uploaded" }
            if (state != "uploaded") continue

            var score = 0
            if (name.contains("release", ignoreCase = true)) score += 2
            if (name.contains("debug", ignoreCase = true)) score -= 2
            score += 1
            if (score > bestScore) {
                bestScore = score
                best = asset
            }
        }
        return best
    }

    fun prettify(name: String): String = name
        .replace('-', ' ')
        .replace('_', ' ')
        .trim()
        .replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }

    private fun JSONObject.stringOrNull(key: String): String? =
        optString(key).takeIf { it.isNotBlank() }
}
