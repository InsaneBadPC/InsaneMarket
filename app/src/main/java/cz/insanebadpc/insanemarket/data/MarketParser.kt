package cz.insanebadpc.insanemarket.data

import cz.insanebadpc.insanemarket.model.AppEntry
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

/**
 * Čistá přeměna GitHub JSONu na [AppEntry]. Záměrně bez Android závislostí,
 * aby se dala testovat jednotkovými testy.
 */
object MarketParser {
    private const val DEFAULT_DESCRIPTION = "Aplikace z GitHub repozitáře."

    private val ICON_EXACT_PRIORITY = listOf(
        "icon.png",
        "logo.png",
        "icon.webp",
        "logo.webp",
        "icon.jpg",
        "logo.jpg",
        "app-icon.png",
        "assets/icon.png",
        "assets/logo.png",
        "assets/images/icon.png",
        "img/logo.png",
        "public/icon.png",
        "public/logo.png",
        "web/public/icon.png",
        "web/public/logo.png",
        "fastlane/metadata/android/en-US/images/icon.png",
        "assets/images/splash-icon.png",
        "assets/images/favicon.png",
    )

    private val ICON_SUFFIX_PRIORITY = listOf(
        "mipmap-xxxhdpi/ic_launcher.png",
        "mipmap-xxxhdpi/ic_launcher_round.png",
        "mipmap-xxhdpi/ic_launcher.png",
        "mipmap-xxhdpi/ic_launcher_round.png",
        "mipmap-xhdpi/ic_launcher.png",
        "mipmap-xhdpi/ic_launcher_round.png",
        "mipmap-hdpi/ic_launcher.png",
        "mipmap-hdpi/ic_launcher_round.png",
        "mipmap-mdpi/ic_launcher.png",
        "mipmap-xxxhdpi/ic_launcher.webp",
        "mipmap-xxhdpi/ic_launcher.webp",
        "mipmap-xhdpi/ic_launcher.webp",
        "mipmap-hdpi/ic_launcher.webp",
        "mipmap-mdpi/ic_launcher.webp",
        "apple-touch-icon.png",
        "/icon.png",
        "/logo.png",
        "/favicon.png",
    )

    private val WEB_TREE_FILES = setOf(
        "index.html",
        "web/index.html",
        "public/index.html",
        "docs/index.html",
        "vercel.json",
        "netlify.toml",
    )

    fun parse(repo: JSONObject, release: JSONObject, metadata: JSONObject?): AppEntry? =
        parseRepo(
            repo = repo,
            release = release,
            metadata = metadata,
            tree = null,
            readmeText = null,
            requireApkOnly = true,
        )

    fun parseRepo(
        repo: JSONObject,
        release: JSONObject?,
        metadata: JSONObject?,
        tree: JSONArray? = null,
        readmeText: String? = null,
        requireApkOnly: Boolean = false,
        allowEmpty: Boolean = false,
        isExternal: Boolean = false,
        isManual: Boolean = false,
    ): AppEntry? {
        val name = repo.optString("name").ifBlank { return null }
        val full = repo.optString("full_name").ifBlank { return null }
        val defaultBranch = repo.stringOrNull("default_branch") ?: "main"
        val isPrivate = repo.optBoolean("private", false)

        val releaseAssets = release?.optJSONArray("assets") ?: JSONArray()
        val releaseApk = selectApk(releaseAssets)
        val treeApk = if (releaseApk == null && tree != null) {
            selectTreeApk(tree, full, defaultBranch)
        } else {
            null
        }

        val apkUrl = releaseApk?.optString("browser_download_url")?.takeIf { it.isNotBlank() }
            ?: treeApk?.downloadUrl
            ?: metadata?.stringOrNull("apkUrl")?.takeIf { !requireApkOnly }
        val apkApiUrl = releaseApk?.stringOrNull("url") ?: treeApk?.apiUrl
        val assetName = releaseApk?.stringOrNull("name") ?: treeApk?.name
        val assetSize = releaseApk?.optLong("size")?.takeIf { it > 0 } ?: treeApk?.size?.takeIf { it > 0 }
        val digest = releaseApk?.stringOrNull("digest")?.takeIf { it.startsWith("sha256:", ignoreCase = true) }

        val webUrl = detectWebUrl(repo, metadata, tree, readmeText)

        if (requireApkOnly && apkUrl.isNullOrBlank()) return null
        if (!allowEmpty && apkUrl.isNullOrBlank() && webUrl.isNullOrBlank()) return null

        val metadataPackage = metadata?.stringOrNull("packageName") ?: metadata?.stringOrNull("package")
        val display = metadata?.stringOrNull("displayName")
            ?: metadata?.stringOrNull("name")
            ?: prettify(name)

        val rawDescription = metadata?.stringOrNull("description")
            ?: release?.stringOrNull("body")
            ?: repo.stringOrNull("description")
            ?: DEFAULT_DESCRIPTION
        val description = cleanDescription(rawDescription, repo.stringOrNull("description"))

        val publishedAt = release?.stringOrNull("published_at")
            ?: release?.stringOrNull("created_at")
            ?: repo.stringOrNull("pushed_at")
            ?: repo.stringOrNull("updated_at")

        val latestTag = release?.stringOrNull("tag_name")
            ?: metadata?.stringOrNull("version")?.let { if (it.startsWith("v")) it else "v$it" }
            ?: if (treeApk != null) defaultBranch else null

        val latestName = release?.stringOrNull("name")
            ?: metadata?.stringOrNull("version")

        val iconUrl = detectIconUrl(repo, releaseAssets, metadata, tree, full, defaultBranch, isExternal)

        return AppEntry(
            repoName = name,
            repoFullName = full,
            repoUrl = repo.optString("html_url").ifBlank { "https://github.com/$full" },
            displayName = display,
            description = description.trim().ifBlank { DEFAULT_DESCRIPTION },
            language = repo.stringOrNull("language"),
            latestTag = latestTag,
            latestName = latestName,
            apkUrl = apkUrl,
            apkApiUrl = apkApiUrl,
            assetName = assetName,
            assetSize = assetSize,
            assetDigest = digest,
            publishedAt = publishedAt,
            releaseNotes = release?.stringOrNull("body") ?: metadata?.stringOrNull("notes"),
            manifestPackage = metadataPackage,
            webUrl = webUrl,
            iconUrl = iconUrl,
            isPrivate = isPrivate,
            isExternal = isExternal,
            isManual = isManual,
            starsCount = repo.optInt("stargazers_count", 0),
            forksCount = repo.optInt("forks_count", 0),
            defaultBranch = defaultBranch,
        )
    }

    data class TreeApkInfo(
        val name: String,
        val path: String,
        val size: Long,
        val downloadUrl: String,
        val apiUrl: String,
    )

    fun selectApk(assets: JSONArray): JSONObject? {
        var best: JSONObject? = null
        var bestScore = Int.MIN_VALUE
        for (i in 0 until assets.length()) {
            val asset = assets.optJSONObject(i) ?: continue
            val name = asset.optString("name")
            if (!name.endsWith(".apk", ignoreCase = true)) continue
            if (asset.optString("browser_download_url").isBlank() && asset.optString("url").isBlank()) continue
            val state = asset.optString("state").ifBlank { "uploaded" }
            if (state != "uploaded") continue

            var score = 0
            if (name.contains("release", ignoreCase = true)) score += 2
            if (name.contains("debug", ignoreCase = true)) score -= 2
            if (!name.startsWith("app-", ignoreCase = true)) score += 1
            score += 1
            if (score > bestScore) {
                bestScore = score
                best = asset
            }
        }
        return best
    }

    fun selectReleaseWithApk(releases: JSONArray): JSONObject? {
        var firstNonDraft: JSONObject? = null
        var bestWithApk: JSONObject? = null
        for (i in 0 until releases.length()) {
            val rel = releases.optJSONObject(i) ?: continue
            if (rel.optBoolean("draft", false)) continue
            if (firstNonDraft == null) firstNonDraft = rel
            val assets = rel.optJSONArray("assets") ?: JSONArray()
            if (selectApk(assets) != null) {
                if (!rel.optBoolean("prerelease", false)) {
                    return rel
                }
                if (bestWithApk == null) {
                    bestWithApk = rel
                }
            }
        }
        return bestWithApk ?: firstNonDraft
    }

    fun selectTreeApk(tree: JSONArray, repoFullName: String, defaultBranch: String): TreeApkInfo? {
        var bestPath: String? = null
        var bestSize = 0L
        var bestScore = Int.MIN_VALUE

        for (i in 0 until tree.length()) {
            val item = tree.optJSONObject(i) ?: continue
            if (item.optString("type") != "blob") continue
            val path = item.optString("path")
            if (!path.endsWith(".apk", ignoreCase = true)) continue
            val name = path.substringAfterLast('/')
            var score = 0
            if (!path.contains('/')) score += 5 // root APK preferred
            if (name.contains("release", ignoreCase = true)) score += 3
            if (name.contains("debug", ignoreCase = true)) score -= 2
            if (score > bestScore) {
                bestScore = score
                bestPath = path
                bestSize = item.optLong("size", 0L)
            }
        }

        val chosen = bestPath ?: return null
        val encodedPath = chosen.split('/').joinToString("/") {
            URLEncoder.encode(it, "UTF-8").replace("+", "%20")
        }
        val rawUrl = "https://raw.githubusercontent.com/$repoFullName/$defaultBranch/$encodedPath"
        val apiUrl = "https://api.github.com/repos/$repoFullName/contents/$encodedPath?ref=$defaultBranch"
        return TreeApkInfo(
            name = chosen.substringAfterLast('/'),
            path = chosen,
            size = bestSize,
            downloadUrl = rawUrl,
            apiUrl = apiUrl,
        )
    }

    fun detectWebUrl(
        repo: JSONObject,
        metadata: JSONObject?,
        tree: JSONArray?,
        readmeText: String?,
    ): String? {
        metadata?.stringOrNull("webUrl")?.takeIf { it.startsWith("http") }?.let { return it }
        metadata?.stringOrNull("homepage")?.takeIf { it.startsWith("http") }?.let { return it }
        repo.stringOrNull("homepage")?.takeIf { it.startsWith("http") }?.let { return it }

        val full = repo.optString("full_name")
        val owner = full.substringBefore('/').lowercase()
        val name = repo.optString("name")
        val defaultPagesUrl = if (owner.isNotBlank() && name.isNotBlank()) {
            "https://$owner.github.io/$name/"
        } else {
            null
        }

        if (repo.optBoolean("has_pages", false) && defaultPagesUrl != null) {
            return defaultPagesUrl
        }

        // Check README for deployed web app URLs (Vercel, Netlify, GitHub Pages, Cloudflare Pages)
        if (!readmeText.isNullOrBlank()) {
            val urlRegex = Regex("""https://[a-zA-Z0-9._-]+\.(?:github\.io|vercel\.app|netlify\.app|pages\.dev|web\.app|firebaseapp\.com)(?:/[^\s)"'>\]]*)?""")
            urlRegex.find(readmeText)?.value?.let { return it }
        }

        // Check if repository tree contains a web application
        if (tree != null) {
            var hasWebFiles = false
            for (i in 0 until tree.length()) {
                val item = tree.optJSONObject(i) ?: continue
                val path = item.optString("path")
                if (path in WEB_TREE_FILES) {
                    hasWebFiles = true
                    break
                }
            }
            if (hasWebFiles && defaultPagesUrl != null) {
                return defaultPagesUrl
            }
        }
        return null
    }

    fun detectIconUrl(
        repo: JSONObject,
        releaseAssets: JSONArray,
        metadata: JSONObject?,
        tree: JSONArray?,
        repoFullName: String,
        defaultBranch: String,
        isExternal: Boolean,
    ): String? {
        val metaIcon = metadata?.stringOrNull("iconUrl")
            ?: metadata?.stringOrNull("icon")
            ?: metadata?.stringOrNull("logo")
        if (!metaIcon.isNullOrBlank()) {
            return if (metaIcon.startsWith("http")) metaIcon
            else "https://raw.githubusercontent.com/$repoFullName/$defaultBranch/${metaIcon.removePrefix("/")}"
        }

        // 1. Check release assets for an icon/logo image
        for (i in 0 until releaseAssets.length()) {
            val asset = releaseAssets.optJSONObject(i) ?: continue
            val name = asset.optString("name").lowercase()
            if (name in listOf("icon.png", "logo.png", "icon.webp", "logo.webp", "icon.jpg", "logo.jpg", "app-icon.png")) {
                asset.stringOrNull("browser_download_url")?.let { return it }
            }
        }

        // 2. Check repository git tree for custom logo/icon or Android launcher raster icon
        if (tree != null) {
            val paths = mutableListOf<String>()
            for (i in 0 until tree.length()) {
                val item = tree.optJSONObject(i) ?: continue
                if (item.optString("type") != "blob") continue
                val p = item.optString("path")
                if (p.endsWith(".png", ignoreCase = true) ||
                    p.endsWith(".webp", ignoreCase = true) ||
                    p.endsWith(".jpg", ignoreCase = true)
                ) {
                    paths.add(p)
                }
            }

            for (exact in ICON_EXACT_PRIORITY) {
                val match = paths.firstOrNull { it.equals(exact, ignoreCase = true) }
                if (match != null) {
                    return rawFileUrl(repoFullName, defaultBranch, match)
                }
            }

            for (suffix in ICON_SUFFIX_PRIORITY) {
                val match = paths.firstOrNull { it.endsWith(suffix, ignoreCase = true) }
                if (match != null) {
                    return rawFileUrl(repoFullName, defaultBranch, match)
                }
            }
        }

        // 3. Fallback to owner avatar if available and external
        if (isExternal) {
            repo.optJSONObject("owner")?.stringOrNull("avatar_url")?.let { return it }
        }
        return null
    }

    private fun rawFileUrl(repoFullName: String, branch: String, path: String): String {
        val encoded = path.split('/').joinToString("/") {
            URLEncoder.encode(it, "UTF-8").replace("+", "%20")
        }
        return "https://raw.githubusercontent.com/$repoFullName/$branch/$encoded"
    }

    fun cleanDescription(raw: String, repoDescription: String?): String {
        val trimmed = raw.trim()
        if (trimmed.isBlank()) return repoDescription?.trim().orEmpty().ifBlank { DEFAULT_DESCRIPTION }
        // Clean markdown headings, bullets, and links for clean display
        val cleanedLines = trimmed.lineSequence()
            .map { line ->
                line.trim()
                    .removePrefix("### ")
                    .removePrefix("## ")
                    .removePrefix("# ")
                    .removePrefix("- ")
                    .removePrefix("* ")
                    .replace(Regex("""\*\*([^*]+)\*\*"""), "$1")
                    .replace(Regex("""`([^`]+)`"""), "$1")
                    .trim()
            }
            .filter { line ->
                line.isNotBlank() &&
                    !line.startsWith("Full Changelog", ignoreCase = true) &&
                    !line.startsWith("What's Changed", ignoreCase = true) &&
                    !line.startsWith("http://") &&
                    !line.startsWith("https://")
            }
            .toList()
        return cleanedLines.joinToString("\n").ifBlank {
            repoDescription?.trim().orEmpty().ifBlank { trimmed }
        }
    }

    fun prettify(name: String): String = name
        .replace('-', ' ')
        .replace('_', ' ')
        .trim()
        .replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }

    private fun JSONObject.stringOrNull(key: String): String? =
        optString(key).takeIf { it.isNotBlank() && it != "null" }
}
