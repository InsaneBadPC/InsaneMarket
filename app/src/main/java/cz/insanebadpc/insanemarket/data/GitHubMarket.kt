package cz.insanebadpc.insanemarket.data

import cz.insanebadpc.insanemarket.BuildConfig
import cz.insanebadpc.insanemarket.model.AppEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

data class ScanResult(
    val entries: List<AppEntry>,
    val rateRemaining: Int?,
    val rateResetEpoch: Long?,
    val fromCache: Boolean,
    val failedRepos: Int,
)

class GitHubMarket(
    owner: String = DEFAULT_OWNER,
    private val tokenProvider: () -> String? = { null },
    private val cache: ScanCache? = null,
) {
    class ApiException(
        val code: Int,
        message: String,
        val rateResetEpoch: Long? = null,
        val rateRemaining: Int? = null,
    ) : IllegalStateException(message)

    private data class RateInfo(val remaining: Int?, val resetEpoch: Long?)
    private data class HttpResponse(val body: String, val link: String?)

    private val lastRate = AtomicReference<RateInfo?>(null)
    private var owner: String = owner.trim().ifBlank { DEFAULT_OWNER }

    fun setOwner(value: String) {
        owner = value.trim().ifBlank { DEFAULT_OWNER }
    }

    fun owner(): String = owner

    suspend fun scan(force: Boolean = true): ScanResult = withContext(Dispatchers.IO) {
        if (!force) {
            cache?.load(owner, CACHE_MAX_AGE_MS)?.let {
                return@withContext ScanResult(it.entries, null, null, true, 0)
            }
        }

        lastRate.set(null)
        val repos = fetchRepos()
        val fatal = AtomicReference<ApiException?>(null)
        val firstFailure = AtomicReference<Throwable?>(null)
        val failed = AtomicInteger(0)
        val semaphore = Semaphore(MAX_PARALLEL_REQUESTS)

        val entries = coroutineScope {
            repos.map { repo ->
                async {
                    semaphore.withPermit {
                        try {
                            fetchAppRelease(repo)
                        } catch (e: ApiException) {
                            if (e.code == 401 || e.code == 403 || e.code == 429 || e.rateRemaining == 0) {
                                fatal.compareAndSet(null, e)
                            } else {
                                failed.incrementAndGet()
                                firstFailure.compareAndSet(null, e)
                            }
                            null
                        } catch (e: Exception) {
                            failed.incrementAndGet()
                            firstFailure.compareAndSet(null, e)
                            null
                        }
                    }
                }
            }.awaitAll().filterNotNull()
        }

        fatal.get()?.let { throw it }
        if (entries.isEmpty()) firstFailure.get()?.let { throw it }

        val sorted = entries.sortedWith(
            compareByDescending<AppEntry> { it.publishedAt ?: "" }.thenBy { it.displayName.lowercase() }
        )
        if (failed.get() == 0) cache?.save(owner, sorted)
        val info = lastRate.get()
        ScanResult(sorted, info?.remaining, info?.resetEpoch, false, failed.get())
    }

    private fun fetchRepos(): List<JSONObject> {
        val out = mutableListOf<JSONObject>()
        var url: String? = "https://api.github.com/users/$owner/repos?per_page=100&type=owner&sort=updated"
        var page = 0
        while (page < MAX_REPO_PAGES) {
            val current = url ?: break
            if (current.isBlank()) break
            val response = get(current)
            val array = try {
                JSONArray(response.body)
            } catch (_: Exception) {
                throw ApiException(500, "GitHub vrátil nečitelný seznam repozitářů.")
            }
            for (i in 0 until array.length()) {
                val repo = array.optJSONObject(i) ?: continue
                if (repo.optBoolean("archived") || repo.optBoolean("fork") || repo.optBoolean("private")) continue
                if (repo.optBoolean("disabled")) continue
                if (repo.optString("name").isBlank()) continue
                out.add(repo)
            }
            url = nextLink(response.link)
            page++
        }
        return out
    }

    private fun fetchAppRelease(repo: JSONObject): AppEntry? {
        val full = repo.optString("full_name").ifBlank { return null }
        val response = try {
            get("https://api.github.com/repos/$full/releases/latest")
        } catch (e: ApiException) {
            if (e.code == 404) return null
            throw e
        }
        val release = try {
            JSONObject(response.body)
        } catch (_: Exception) {
            return null
        }
        val metadata = fetchMetadata(release.optJSONArray("assets") ?: JSONArray())
        return MarketParser.parse(repo, release, metadata)
    }

    private fun fetchMetadata(assets: JSONArray): JSONObject? {
        for (i in 0 until assets.length()) {
            val asset = assets.optJSONObject(i) ?: continue
            val name = asset.optString("name")
            if (!name.equals("insanemarket.json", ignoreCase = true) &&
                !name.equals("market.json", ignoreCase = true)
            ) continue
            val url = asset.optString("browser_download_url")
            if (url.isBlank()) continue
            return try {
                JSONObject(get(url).body)
            } catch (_: Exception) {
                null
            }
        }
        return null
    }

    private fun get(url: String): HttpResponse {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            instanceFollowRedirects = true
            connectTimeout = 15_000
            readTimeout = 30_000
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "InsaneMarket/${BuildConfig.VERSION_NAME}")
            setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            val token = tokenProvider()?.trim()
            if (!token.isNullOrBlank() && URL(url).host.equals("api.github.com", ignoreCase = true)) {
                setRequestProperty("Authorization", "Bearer $token")
            }
        }

        val remaining = connection.getHeaderField("X-RateLimit-Remaining")?.toIntOrNull()
        val reset = connection.getHeaderField("X-RateLimit-Reset")?.toLongOrNull()
        val link = connection.getHeaderField("Link")
        val code: Int
        val text: String
        try {
            code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
        } finally {
            connection.disconnect()
        }
        if (remaining != null || reset != null) lastRate.set(RateInfo(remaining, reset))
        if (code !in 200..299) {
            throw errorFor(code, text, remaining, reset)
        }
        return HttpResponse(text, link)
    }

    private fun errorFor(code: Int, body: String, remaining: Int?, reset: Long?): ApiException {
        val lower = body.lowercase()
        val rateLimited = (code == 403 || code == 429) &&
            (remaining == 0 || lower.contains("rate limit") || lower.contains("abuse") || lower.contains("secondary rate"))
        if (rateLimited) {
            val resetHint = reset?.let {
                " Obnoví se v ${SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(it * 1000))}."
            } ?: ""
            return ApiException(
                code,
                "GitHub API limit byl vyčerpán.$resetHint Přidej token v Nastavení (stačí read-only).",
                reset,
                remaining,
            )
        }
        return when (code) {
            401 -> ApiException(code, "GitHub token je neplatný nebo vypršel. Zkontroluj ho v Nastavení.")
            403 -> ApiException(code, "GitHub přístup zamítnut (403). Zkontroluj oprávnění tokenu.", reset, remaining)
            404 -> ApiException(code, "GitHub účet '$owner' nebo požadovaný repozitář nebyl nalezen.")
            429 -> ApiException(code, "Příliš mnoho požadavků na GitHub. Zkus to za chvíli.", reset, remaining)
            else -> ApiException(code, "GitHub API chyba $code: ${body.take(180)}")
        }
    }

    private fun nextLink(link: String?): String? {
        if (link.isNullOrBlank()) return null
        val match = Regex("<([^>]+)>\\s*;\\s*rel=\"next\"").find(link) ?: return null
        return match.groupValues[1]
    }

    companion object {
        const val DEFAULT_OWNER = "InsaneBadPC"
        private const val CACHE_MAX_AGE_MS = 10 * 60 * 1000L
        private const val MAX_PARALLEL_REQUESTS = 6
        private const val MAX_REPO_PAGES = 5
    }
}
