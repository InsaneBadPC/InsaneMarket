package cz.insanebadpc.insanemarket.data

import cz.insanebadpc.insanemarket.model.AppEntry
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL

class GitHubMarket(
    private var owner: String = "InsaneBadPC",
    private val tokenProvider: () -> String? = { null },
) {
    class ApiException(val code: Int, message: String) : IllegalStateException(message)

    fun setOwner(value: String) { owner = value.trim().ifBlank { "InsaneBadPC" } }
    fun owner(): String = owner

    fun scan(): List<AppEntry> {
        val repos = getJsonArray("https://api.github.com/users/$owner/repos?per_page=100&type=owner&sort=updated")
        val out = mutableListOf<AppEntry>()
        for (i in 0 until repos.length()) {
            val r = repos.optJSONObject(i) ?: continue
            if (r.optBoolean("archived") || r.optBoolean("fork") || r.optBoolean("private")) continue
            val name = r.optString("name")
            val full = r.optString("full_name")
            fetchAppRelease(r, name, full)?.let(out::add)
        }
        return out.sortedWith(compareByDescending<AppEntry> { it.publishedAt ?: "" }.thenBy { it.displayName.lowercase() })
    }

    private fun fetchAppRelease(repo: JSONObject, name: String, full: String): AppEntry? {
        val rel = try { getJson("https://api.github.com/repos/$full/releases/latest") } catch (_: Exception) { null } ?: return null
        val assets = rel.optJSONArray("assets") ?: JSONArray()
        var apkUrl: String? = null
        var assetName: String? = null
        var assetSize: Long? = null
        var metadata: JSONObject? = null
        for (i in 0 until assets.length()) {
            val a = assets.optJSONObject(i) ?: continue
            val n = a.optString("name")
            if (n.equals("insanemarket.json", true) || n.equals("market.json", true)) {
                metadata = try {
                    val u = a.optString("browser_download_url").ifBlank { a.optString("url") }
                    if (u.isNotBlank()) getJson(u) else null
                } catch (_: Exception) { null }
            }
            if (apkUrl == null && n.endsWith(".apk", true)) {
                apkUrl = a.optString("browser_download_url").ifBlank { a.optString("url") }
                assetName = n
                assetSize = a.optLong("size").takeIf { it > 0 }
            }
        }
        if (apkUrl.isNullOrBlank()) return null
        val desc = metadata?.optString("description")?.ifBlank { null }
            ?: rel.optString("body").ifBlank { repo.optString("description") }.ifBlank { "Aplikace z GitHub release." }
        val display = metadata?.optString("displayName")?.ifBlank { metadata.optString("name") }?.ifBlank { null } ?: prettify(name)
        val manifestPackage = metadata?.optString("packageName")?.ifBlank { metadata.optString("package") }?.ifBlank { null }
        return AppEntry(
            repoName = name,
            repoFullName = full,
            repoUrl = repo.optString("html_url"),
            displayName = display,
            description = desc.trim(),
            language = repo.optString("language").ifBlank { null },
            latestTag = rel.optString("tag_name").ifBlank { null },
            latestName = rel.optString("name").ifBlank { null },
            apkUrl = apkUrl,
            assetName = assetName,
            assetSize = assetSize,
            publishedAt = rel.optString("published_at").ifBlank { rel.optString("created_at") },
            releaseNotes = rel.optString("body").ifBlank { null },
            manifestPackage = manifestPackage,
            packageName = manifestPackage,
            status = "Dostupná aplikace"
        )
    }

    private fun prettify(name: String) = name.replace('-', ' ').replace('_', ' ').trim().replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }

    private fun getJson(url: String) = JSONObject(get(url))
    private fun getJsonArray(url: String) = JSONArray(get(url))

    private fun get(url: String): String {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "InsaneMarket/0.3")
            setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            tokenProvider()?.takeIf { it.isNotBlank() }?.let { setRequestProperty("Authorization", "Bearer $it") }
            connectTimeout = 15_000
            readTimeout = 25_000
        }
        val code = conn.responseCode
        val remaining = conn.getHeaderField("X-RateLimit-Remaining")
        val reset = conn.getHeaderField("X-RateLimit-Reset")
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream ?: conn.inputStream
        val text = stream.bufferedReader().use(BufferedReader::readText)
        if (code !in 200..299) {
            val lower = text.lowercase()
            val rateLimited = code == 403 && (remaining == "0" || lower.contains("rate limit") || lower.contains("abuse"))
            if (rateLimited) {
                val resetHint = reset?.toLongOrNull()?.let { " Reset cca ${java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date(it * 1000))}." } ?: ""
                throw ApiException(code, "GitHub API limit byl vyčerpán.$resetHint Přidej GitHub token v Nastavení pro vyšší limit.")
            }
            if (code == 401) throw ApiException(code, "GitHub token je neplatný nebo nemá přístup. Zkontroluj token v Nastavení.")
            if (code == 404) throw ApiException(code, "GitHub účet '${owner}' nebo požadovaný zdroj nebyl nalezen.")
            throw ApiException(code, "GitHub API chyba $code: ${text.take(180)}")
        }
        return text
    }
}
