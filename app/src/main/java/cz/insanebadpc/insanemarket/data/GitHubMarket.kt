package cz.insanebadpc.insanemarket.data

import cz.insanebadpc.insanemarket.model.AppEntry
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL

class GitHubMarket(private val owner: String = "InsaneBadPC", private val tokenProvider: () -> String? = { null }) {

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
        return out.sortedByDescending { it.publishedAt ?: "" }
    }

    private fun fetchAppRelease(repo: JSONObject, name: String, full: String): AppEntry? {
        val rel = try { getJson("https://api.github.com/repos/$full/releases/latest") } catch (_: Exception) { null } ?: return null
        val assets = rel.optJSONArray("assets") ?: JSONArray()
        var apkUrl: String? = null; var assetName: String? = null
        for (i in 0 until assets.length()) {
            val a = assets.optJSONObject(i) ?: continue
            val n = a.optString("name")
            if (n.endsWith(".apk", true)) {
                apkUrl = a.optString("browser_download_url").ifBlank { a.optString("url") }
                assetName = n
                break
            }
        }
        if (apkUrl == null) return null
        val desc = rel.optString("body").ifBlank { repo.optString("description") }.ifBlank { "Aplikace z GitHub release." }
        return AppEntry(
            repoName = name,
            repoFullName = full,
            repoUrl = repo.optString("html_url"),
            displayName = prettify(name),
            description = desc.take(500),
            language = repo.optString("language").ifBlank { null },
            latestTag = rel.optString("tag_name").ifBlank { null },
            latestName = rel.optString("name").ifBlank { null },
            apkUrl = apkUrl,
            assetName = assetName,
            publishedAt = rel.optString("published_at").ifBlank { rel.optString("created_at") },
            releaseNotes = rel.optString("body").ifBlank { null },
            status = "Dostupná aplikace"
        )
    }

    private fun prettify(name: String) = name.replace('-', ' ').replace('_', ' ').trim().replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }

    private fun getJson(url: String) = JSONObject(get(url))
    private fun getJsonArray(url: String) = JSONArray(get(url))

    private fun get(url: String): String {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "InsaneMarket/0.1")
            setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            tokenProvider()?.takeIf { it.isBlank().not() }?.let { setRequestProperty("Authorization", "Bearer $it") }
            connectTimeout = 15_000
            readTimeout = 20_000
        }
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream ?: conn.inputStream
        val text = stream.bufferedReader().use(BufferedReader::readText)
        if (code !in 200..299) throw IllegalStateException("GitHub API $code: ${text.take(200)}")
        return text
    }
}
