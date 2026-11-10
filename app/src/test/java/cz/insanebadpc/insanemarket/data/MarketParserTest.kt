package cz.insanebadpc.insanemarket.data

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
}
