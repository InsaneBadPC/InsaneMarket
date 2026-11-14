package cz.insanebadpc.insanemarket.data

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MarketParserTest {
    private fun repo() = JSONObject()
        .put("name", "-achy")
        .put("full_name", "InsaneBadPC/-achy")
        .put("html_url", "https://github.com/InsaneBadPC/-achy")
        .put("language", "Kotlin")
        .put("description", "Repo description")

    private fun release(assets: JSONArray) = JSONObject()
        .put("tag_name", "v1.0.28")
        .put("name", "Release 1.0.28")
        .put("published_at", "2026-11-01T10:00:00Z")
        .put("body", "Release notes")
        .put("assets", assets)

    @Test
    fun prefersReleaseApkOverDebugApk() {
        val assets = JSONArray()
            .put(
                JSONObject()
                    .put("name", "app-debug.apk")
                    .put("browser_download_url", "https://example.com/debug.apk")
                    .put("size", 100)
                    .put("state", "uploaded")
            )
            .put(
                JSONObject()
                    .put("name", "app-release.apk")
                    .put("browser_download_url", "https://example.com/release.apk")
                    .put("size", 200)
                    .put("digest", "sha256:abc123")
                    .put("state", "uploaded")
            )

        val entry = MarketParser.parse(repo(), release(assets), null)

        requireNotNull(entry)
        assertEquals("app-release.apk", entry.assetName)
        assertEquals("https://example.com/release.apk", entry.apkUrl)
        assertEquals(200L, entry.assetSize)
        assertEquals("sha256:abc123", entry.assetDigest)
        assertEquals("v1.0.28", entry.latestTag)
        assertEquals("Achy", entry.displayName)
        assertEquals("Release notes", entry.description)
    }

    @Test
    fun metadataOverridesDisplayNameAndPackage() {
        val assets = JSONArray().put(
            JSONObject()
                .put("name", "app-release.apk")
                .put("browser_download_url", "https://example.com/release.apk")
                .put("size", 200)
                .put("state", "uploaded")
        )
        val metadata = JSONObject()
            .put("displayName", "Wacky Chess")
            .put("packageName", "cz.insanebadpc.wackychess")
            .put("description", "Metadata description")

        val entry = MarketParser.parse(repo(), release(assets), metadata)

        requireNotNull(entry)
        assertEquals("Wacky Chess", entry.displayName)
        assertEquals("cz.insanebadpc.wackychess", entry.manifestPackage)
        assertEquals("Metadata description", entry.description)
    }

    @Test
    fun repositoryWithoutApkIsNotAnApp() {
        val assets = JSONArray().put(
            JSONObject()
                .put("name", "source.zip")
                .put("browser_download_url", "https://example.com/source.zip")
                .put("state", "uploaded")
        )
        assertNull(MarketParser.parse(repo(), release(assets), null))
    }

    @Test
    fun skipsAssetsThatAreNotUploaded() {
        val assets = JSONArray().put(
            JSONObject()
                .put("name", "app-release.apk")
                .put("browser_download_url", "https://example.com/release.apk")
                .put("state", "new")
        )
        assertNull(MarketParser.parse(repo(), release(assets), null))
    }

    @Test
    fun detectsTreeApkWebAppAndIconFromRepositoryTree() {
        val r = repo().put("has_pages", true).put("private", true)
        val tree = JSONArray()
            .put(JSONObject().put("type", "blob").put("path", "SecretsInsane.apk").put("size", 9352197L))
            .put(JSONObject().put("type", "blob").put("path", "web/index.html").put("size", 1024L))
            .put(JSONObject().put("type", "blob").put("path", "icon.png").put("size", 4096L))

        val entry = MarketParser.parseRepo(
            repo = r,
            release = null,
            metadata = null,
            tree = tree,
        )

        assertNotNull(entry)
        requireNotNull(entry)
        assertTrue(entry.hasApk)
        assertTrue(entry.hasWebApp)
        assertTrue(entry.isPrivate)
        assertEquals("SecretsInsane.apk", entry.assetName)
        assertEquals("https://insanebadpc.github.io/-achy/", entry.webUrl)
        assertEquals("https://raw.githubusercontent.com/InsaneBadPC/-achy/main/icon.png", entry.iconUrl)
    }

    @Test
    fun pureWebAppRepoLoadsIntoWebSection() {
        val r = repo().put("has_pages", true)
        val entry = MarketParser.parseRepo(
            repo = r,
            release = null,
            metadata = null,
            tree = null,
        )

        requireNotNull(entry)
        assertEquals(false, entry.hasApk)
        assertEquals(true, entry.hasWebApp)
        assertEquals("https://insanebadpc.github.io/-achy/", entry.webUrl)
    }

    @Test
    fun mangledDiacriticRepoUsesReleaseOrAssetName() {
        val chessRelease = JSONObject()
            .put("tag_name", "v1.0.28")
            .put("name", "Wacky Chess Mayhem v1.0.28")
            .put("assets", JSONArray().put(
                JSONObject()
                    .put("name", "WackyChess-v1.0.28.apk")
                    .put("browser_download_url", "https://example.com/WackyChess.apk")
                    .put("state", "uploaded")
            ))
        val chess = MarketParser.parse(repo(), chessRelease, null)
        requireNotNull(chess)
        assertEquals("Wacky Chess Mayhem", chess.displayName)

        val dochazkaRepo = JSONObject()
            .put("name", "Doch-zka")
            .put("full_name", "InsaneBadPC/Doch-zka")
            .put("html_url", "https://github.com/InsaneBadPC/Doch-zka")
        val dochazkaRelease = JSONObject()
            .put("tag_name", "v1.0.26")
            .put("name", "Sestavení v1.0.26 (Web & Android APK)")
            .put("assets", JSONArray().put(
                JSONObject()
                    .put("name", "Dochazka-v1.0.26.apk")
                    .put("browser_download_url", "https://example.com/Dochazka.apk")
                    .put("state", "uploaded")
            ))
        val dochazka = MarketParser.parse(dochazkaRepo, dochazkaRelease, null)
        requireNotNull(dochazka)
        assertEquals("Dochazka", dochazka.displayName)
    }
}
