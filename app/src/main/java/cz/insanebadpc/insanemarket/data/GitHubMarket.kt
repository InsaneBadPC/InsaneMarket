package cz.insanebadpc.insanemarket.data

import android.util.Base64
import cz.insanebadpc.insanemarket.BuildConfig
import cz.insanebadpc.insanemarket.model.AppEntry
import cz.insanebadpc.insanemarket.model.RepoDetailedInfo
import cz.insanebadpc.insanemarket.model.RepoFileItem
import cz.insanebadpc.insanemarket.model.RepoReleaseAsset
import cz.insanebadpc.insanemarket.model.RepoReleaseInfo
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
import java.net.URLEncoder
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

    suspend fun scan(
        force: Boolean = true,
        externalRepos: Set<String> = emptySet(),
        manualRepos: Set<String> = emptySet(),
    ): ScanResult = withContext(Dispatchers.IO) {
        if (!force && externalRepos.isEmpty() && manualRepos.isEmpty()) {
            cache?.load(owner, CACHE_MAX_AGE_MS)?.let {
                return@withContext ScanResult(it.entries, null, null, true, 0)
            }
        }

        lastRate.set(null)
        val reposByFullName = LinkedHashMap<String, JSONObject>()
        for (repo in fetchRepos()) {
            val full = repo.optString("full_name")
            if (full.isNotBlank()) {
                reposByFullName[full.lowercase()] = repo
            }
        }

        val fatal = AtomicReference<ApiException?>(null)
        val firstFailure = AtomicReference<Throwable?>(null)
        val failed = AtomicInteger(0)
        val semaphore = Semaphore(MAX_PARALLEL_REQUESTS)

        val extraNames = (externalRepos + manualRepos)
            .mapNotNull(::parseRepoFullName)
            .filter { it.lowercase() !in reposByFullName }

        val extraRepoObjects = coroutineScope {
            extraNames.map { full ->
                async {
                    semaphore.withPermit {
                        try {
                            JSONObject(get("https://api.github.com/repos/$full").body)
                        } catch (_: Exception) {
                            null
                        }
                    }
                }
            }.awaitAll().filterNotNull()
        }
        for (extra in extraRepoObjects) {
            val full = extra.optString("full_name")
            if (full.isNotBlank()) {
                reposByFullName[full.lowercase()] = extra
            }
        }

        val externalLower = externalRepos.mapNotNull(::parseRepoFullName).map { it.lowercase() }.toSet()
        val manualLower = manualRepos.mapNotNull(::parseRepoFullName).map { it.lowercase() }.toSet()

        val entries = coroutineScope {
            reposByFullName.values.map { repo ->
                async {
                    semaphore.withPermit {
                        val fullLower = repo.optString("full_name").lowercase()
                        val isExt = fullLower in externalLower
                        val isMan = fullLower in manualLower
                        try {
                            fetchRepoEntry(repo, isExternal = isExt, isManual = isMan)
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
            compareByDescending<AppEntry> { it.userRating }
                .thenByDescending { it.publishedAt ?: "" }
                .thenBy { it.displayName.lowercase() }
        )
        if (failed.get() == 0) cache?.save(owner, sorted)
        val info = lastRate.get()
        ScanResult(sorted, info?.remaining, info?.resetEpoch, false, failed.get())
    }

    suspend fun fetchSingleRepo(
        urlOrFullName: String,
        isExternal: Boolean,
        isManual: Boolean,
    ): AppEntry = withContext(Dispatchers.IO) {
        val full = parseRepoFullName(urlOrFullName)
            ?: throw IllegalArgumentException("Zadej platnou GitHub URL (např. https://github.com/uzivatel/repo nebo uzivatel/repo).")
        val repo = JSONObject(get("https://api.github.com/repos/$full").body)
        fetchRepoEntry(repo, isExternal = isExternal, isManual = isManual, allowEmpty = true)
            ?: throw IllegalStateException("Repozitář $full se nepodařilo načíst.")
    }

    suspend fun searchRepositories(query: String): List<AppEntry> = withContext(Dispatchers.IO) {
        val q = query.trim()
        if (q.isBlank()) return@withContext emptyList()
        val encoded = URLEncoder.encode("$q in:name,description,readme", "UTF-8")
        val response = get("https://api.github.com/search/repositories?q=$encoded&per_page=30")
        val root = JSONObject(response.body)
        val items = root.optJSONArray("items") ?: JSONArray()
        val out = mutableListOf<AppEntry>()
        for (i in 0 until items.length()) {
            val repo = items.optJSONObject(i) ?: continue
            val entry = MarketParser.parseRepo(
                repo = repo,
                release = null,
                metadata = null,
                tree = null,
                readmeText = null,
                requireApkOnly = false,
                allowEmpty = true,
                isExternal = true,
                isManual = false,
            ) ?: continue
            val topicsArr = repo.optJSONArray("topics")
            if (topicsArr != null && topicsArr.length() > 0) {
                val topics = (0 until minOf(topicsArr.length(), 5))
                    .mapNotNull { idx -> topicsArr.optString(idx).takeIf { it.isNotBlank() } }
                if (topics.isNotEmpty()) {
                    entry.description = "${entry.description}\n🏷 ${topics.joinToString(", ")}"
                }
            }
            out.add(entry)
        }
        out
    }

    suspend fun fetchRepoDetails(repoFullName: String): RepoDetailedInfo = withContext(Dispatchers.IO) {
        val full = parseRepoFullName(repoFullName) ?: repoFullName
        val repo = JSONObject(get("https://api.github.com/repos/$full").body)
        val name = repo.optString("name", full.substringAfterLast('/'))
        val ownerObj = repo.optJSONObject("owner")
        val ownerLogin = ownerObj?.optString("login").orEmpty().ifBlank { full.substringBefore('/') }
        val ownerAvatar = ownerObj?.optString("avatar_url")?.takeIf { it.isNotBlank() }
        val defaultBranch = repo.optString("default_branch").ifBlank { "main" }
        val hasPages = repo.optBoolean("has_pages", false)
        val homepage = repo.optString("homepage").takeIf { it.isNotBlank() && it != "null" }

        val pagesUrl = when {
            hasPages -> {
                runCatching {
                    JSONObject(get("https://api.github.com/repos/$full/pages").body)
                        .optString("html_url")
                        .takeIf { it.isNotBlank() }
                }.getOrNull() ?: "https://${ownerLogin.lowercase()}.github.io/$name/"
            }
            !homepage.isNullOrBlank() -> homepage
            else -> null
        }

        val topicsArr = repo.optJSONArray("topics") ?: JSONArray()
        val topics = (0 until topicsArr.length()).mapNotNull { i ->
            topicsArr.optString(i).takeIf { it.isNotBlank() }
        }

        val releases = runCatching {
            val arr = JSONArray(get("https://api.github.com/repos/$full/releases?per_page=20").body)
            (0 until arr.length()).mapNotNull { i ->
                val rel = arr.optJSONObject(i) ?: return@mapNotNull null
                if (rel.optBoolean("draft", false)) return@mapNotNull null
                val assetsArr = rel.optJSONArray("assets") ?: JSONArray()
                val assets = (0 until assetsArr.length()).mapNotNull { j ->
                    val a = assetsArr.optJSONObject(j) ?: return@mapNotNull null
                    val dl = a.optString("browser_download_url")
                    if (dl.isBlank()) return@mapNotNull null
                    RepoReleaseAsset(
                        name = a.optString("name"),
                        size = a.optLong("size", 0L),
                        downloadUrl = dl,
                        apiUrl = a.optString("url").takeIf { it.isNotBlank() },
                        digest = a.optString("digest").takeIf { it.startsWith("sha256:", ignoreCase = true) },
                    )
                }
                RepoReleaseInfo(
                    tagName = rel.optString("tag_name"),
                    name = rel.optString("name").ifBlank { rel.optString("tag_name") },
                    publishedAt = rel.optString("published_at").takeIf { it.isNotBlank() },
                    prerelease = rel.optBoolean("prerelease", false),
                    body = rel.optString("body").takeIf { it.isNotBlank() && it != "null" },
                    htmlUrl = rel.optString("html_url").takeIf { it.isNotBlank() },
                    assets = assets,
                )
            }
        }.getOrDefault(emptyList())

        val readmeText = fetchReadmeText(full)

        RepoDetailedInfo(
            fullName = repo.optString("full_name").ifBlank { full },
            name = name,
            ownerLogin = ownerLogin,
            ownerAvatarUrl = ownerAvatar,
            description = repo.optString("description").takeIf { it.isNotBlank() && it != "null" }
                ?: "Bez popisu repozitáře.",
            htmlUrl = repo.optString("html_url").ifBlank { "https://github.com/$full" },
            language = repo.optString("language").takeIf { it.isNotBlank() && it != "null" },
            defaultBranch = defaultBranch,
            starsCount = repo.optInt("stargazers_count", 0),
            forksCount = repo.optInt("forks_count", 0),
            openIssuesCount = repo.optInt("open_issues_count", 0),
            isPrivate = repo.optBoolean("private", false),
            hasPages = hasPages || !homepage.isNullOrBlank(),
            pagesUrl = pagesUrl,
            homepageUrl = homepage,
            updatedAt = repo.optString("pushed_at").ifBlank { repo.optString("updated_at") }.takeIf { it.isNotBlank() },
            topics = topics,
            releases = releases,
            readmeText = readmeText,
        )
    }

    suspend fun fetchRepoDirectory(
        repoFullName: String,
        path: String = "",
        ref: String? = null,
    ): List<RepoFileItem> = withContext(Dispatchers.IO) {
        val cleanPath = path.trim('/')
        val encodedPath = if (cleanPath.isEmpty()) {
            ""
        } else {
            "/" + cleanPath.split('/').joinToString("/") {
                URLEncoder.encode(it, "UTF-8").replace("+", "%20")
            }
        }
        val query = if (!ref.isNullOrBlank()) "?ref=${URLEncoder.encode(ref, "UTF-8")}" else ""
        val response = get("https://api.github.com/repos/$repoFullName/contents$encodedPath$query")
        val array = JSONArray(response.body)
        val items = (0 until array.length()).mapNotNull { i ->
            val obj = array.optJSONObject(i) ?: return@mapNotNull null
            val name = obj.optString("name").ifBlank { return@mapNotNull null }
            val itemPath = obj.optString("path").ifBlank { name }
            val type = obj.optString("type").ifBlank { "file" }
            RepoFileItem(
                name = name,
                path = itemPath,
                type = type,
                size = obj.optLong("size", 0L),
                htmlUrl = obj.optString("html_url").takeIf { it.isNotBlank() && it != "null" },
                downloadUrl = obj.optString("download_url").takeIf { it.isNotBlank() && it != "null" },
            )
        }
        items.sortedWith(
            compareByDescending<RepoFileItem> { it.isDir }
                .thenBy { it.name.lowercase() }
        )
    }

    suspend fun resolveAssetDownloadUrl(entry: AppEntry): String? = withContext(Dispatchers.IO) {
        val apiUrl = entry.apkApiUrl
        val token = tokenProvider()?.trim()
        if (apiUrl.isNullOrBlank() || token.isNullOrBlank()) {
            return@withContext entry.apkUrl
        }
        if (!URL(apiUrl).host.equals("api.github.com", ignoreCase = true)) {
            return@withContext entry.apkUrl
        }
        try {
            if (apiUrl.contains("/releases/assets/")) {
                val connection = openApiConnection(apiUrl, accept = "application/octet-stream", followRedirects = false)
                try {
                    val code = connection.responseCode
                    if (code in 301..308) {
                        val location = connection.getHeaderField("Location")
                        if (!location.isNullOrBlank()) return@withContext location
                    }
                } finally {
                    connection.disconnect()
                }
            } else if (apiUrl.contains("/contents/")) {
                val obj = JSONObject(get(apiUrl).body)
                val downloadUrl = obj.optString("download_url")
                if (downloadUrl.isNotBlank() && downloadUrl != "null") {
                    return@withContext downloadUrl
                }
            }
        } catch (_: Exception) {
            // Fallback to standard apkUrl
        }
        entry.apkUrl
    }

    private fun fetchRepos(): List<JSONObject> {
        val byName = LinkedHashMap<String, JSONObject>()
        val token = tokenProvider()?.trim()

        // 1. If authenticated with a token, fetch /user/repos first so private repos owned by `owner` are included!
        if (!token.isNullOrBlank()) {
            try {
                fetchPagedRepos("https://api.github.com/user/repos?per_page=100&affiliation=owner,collaborator&sort=updated")
                    .forEach { repo ->
                        val repoOwner = repo.optJSONObject("owner")?.optString("login").orEmpty()
                        if (repoOwner.equals(owner, ignoreCase = true)) {
                            val full = repo.optString("full_name")
                            if (full.isNotBlank()) byName[full.lowercase()] = repo
                        }
                    }
            } catch (e: ApiException) {
                if (e.code == 401) throw e
            } catch (_: Exception) {
                // Fallback to public /users/$owner/repos below
            }
        }

        // 2. Also fetch public repos from /users/$owner/repos
        fetchPagedRepos("https://api.github.com/users/$owner/repos?per_page=100&type=owner&sort=updated")
            .forEach { repo ->
                val full = repo.optString("full_name")
                if (full.isNotBlank() && full.lowercase() !in byName) {
                    byName[full.lowercase()] = repo
                }
            }

        return byName.values.toList()
    }

    private fun fetchPagedRepos(initialUrl: String): List<JSONObject> {
        val out = mutableListOf<JSONObject>()
        var url: String? = initialUrl
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
                if (repo.optBoolean("archived") || repo.optBoolean("disabled")) continue
                if (repo.optString("name").isBlank()) continue
                out.add(repo)
            }
            url = nextLink(response.link)
            page++
        }
        return out
    }

    private fun fetchRepoEntry(
        repo: JSONObject,
        isExternal: Boolean = false,
        isManual: Boolean = false,
        allowEmpty: Boolean = false,
    ): AppEntry? {
        val full = repo.optString("full_name").ifBlank { return null }
        val defaultBranch = repo.optString("default_branch").ifBlank { "main" }

        // 1. Fetch releases list (includes latest AND prereleases + older releases with APKs)
        val release = try {
            val releasesJson = JSONArray(get("https://api.github.com/repos/$full/releases?per_page=10").body)
            MarketParser.selectReleaseWithApk(releasesJson)
        } catch (e: ApiException) {
            if (e.code != 404) throw e
            null
        } catch (_: Exception) {
            null
        }

        val assets = release?.optJSONArray("assets") ?: JSONArray()
        val metadata = fetchMetadata(assets)

        // 2. Fetch repository git tree to detect repo-level APKs, Web Apps, and Icons/Logos
        val tree = try {
            val treeObj = JSONObject(get("https://api.github.com/repos/$full/git/trees/$defaultBranch?recursive=1").body)
            treeObj.optJSONArray("tree")
        } catch (_: Exception) {
            null
        }

        return MarketParser.parseRepo(
            repo = repo,
            release = release,
            metadata = metadata,
            tree = tree,
            readmeText = null,
            requireApkOnly = false,
            allowEmpty = allowEmpty || isExternal || isManual,
            isExternal = isExternal,
            isManual = isManual,
        )
    }

    private fun fetchReadmeText(full: String): String? {
        return try {
            val obj = JSONObject(get("https://api.github.com/repos/$full/readme").body)
            val encoded = obj.optString("content")
            if (encoded.isNotBlank()) {
                String(Base64.decode(encoded.replace("\n", "").replace("\r", ""), Base64.DEFAULT), Charsets.UTF_8)
            } else {
                null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun fetchMetadata(assets: JSONArray): JSONObject? {
        val names = listOf("insanemarket.json", "market.json", "version.json")
        for (target in names) {
            for (i in 0 until assets.length()) {
                val asset = assets.optJSONObject(i) ?: continue
                val name = asset.optString("name")
                if (!name.equals(target, ignoreCase = true)) continue
                val url = asset.optString("browser_download_url")
                if (url.isBlank()) continue
                try {
                    return JSONObject(get(url).body)
                } catch (_: Exception) {
                    // Continue searching
                }
            }
        }
        return null
    }

    private fun openApiConnection(
        url: String,
        accept: String = "application/vnd.github+json",
        followRedirects: Boolean = true,
    ): HttpURLConnection = (URL(url).openConnection() as HttpURLConnection).apply {
        requestMethod = "GET"
        instanceFollowRedirects = followRedirects
        connectTimeout = 15_000
        readTimeout = 30_000
        setRequestProperty("Accept", accept)
        setRequestProperty("User-Agent", "InsaneMarket/${BuildConfig.VERSION_NAME}")
        setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
        val token = tokenProvider()?.trim()
        if (!token.isNullOrBlank() && URL(url).host.equals("api.github.com", ignoreCase = true)) {
            setRequestProperty("Authorization", "Bearer $token")
        }
    }

    private fun get(url: String): HttpResponse {
        val connection = openApiConnection(url)

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
                "GitHub API limit byl vyčerpán.$resetHint Přidej token v Nastavení.",
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

        fun parseRepoFullName(input: String): String? {
            val trimmed = input.trim().removeSuffix("/").removeSuffix(".git")
            if (trimmed.isBlank()) return null
            if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
                return try {
                    val u = URL(trimmed)
                    if (!u.host.equals("github.com", ignoreCase = true) &&
                        !u.host.equals("www.github.com", ignoreCase = true)
                    ) {
                        return null
                    }
                    val parts = u.path.trim('/').split('/')
                    if (parts.size >= 2 && parts[0].isNotBlank() && parts[1].isNotBlank()) {
                        "${parts[0]}/${parts[1]}"
                    } else {
                        null
                    }
                } catch (_: Exception) {
                    null
                }
            }
            val parts = trimmed.trim('/').split('/')
            if (parts.size == 2 && parts[0].isNotBlank() && parts[1].isNotBlank() && !parts[0].contains(' ')) {
                return "${parts[0]}/${parts[1]}"
            }
            return null
        }
    }
}
