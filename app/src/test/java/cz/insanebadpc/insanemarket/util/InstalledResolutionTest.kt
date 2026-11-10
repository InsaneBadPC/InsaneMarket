package cz.insanebadpc.insanemarket.util

import cz.insanebadpc.insanemarket.model.AppEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class InstalledResolutionTest {
    private fun entry(repo: String, publishedAt: String, name: String = repo) = AppEntry(
        repoName = repo,
        repoFullName = "InsaneBadPC/$repo",
        repoUrl = "https://github.com/InsaneBadPC/$repo",
        displayName = name,
        description = "",
        language = "Kotlin",
        latestTag = "v1",
        latestName = null,
        apkUrl = "https://example.com/$repo.apk",
        assetName = "app.apk",
        assetSize = 1L,
        assetDigest = null,
        publishedAt = publishedAt,
        releaseNotes = null,
    )

    @Test
    fun honoursRecordedPackageOwner() {
        val candidates = listOf(
            entry("songcraft-studio", "2026-01-01T00:00:00Z"),
            entry("songcraft-studio-temney-agent", "2026-10-01T00:00:00Z"),
        )
        val preferred = InstalledResolution.preferred(candidates, "InsaneBadPC/songcraft-studio")
        assertEquals("InsaneBadPC/songcraft-studio", preferred?.repoFullName)
    }

    @Test
    fun fallsBackToNewestRelease() {
        val candidates = listOf(
            entry("old", "2026-01-01T00:00:00Z"),
            entry("new", "2026-10-01T00:00:00Z"),
        )
        val preferred = InstalledResolution.preferred(candidates, null)
        assertEquals("InsaneBadPC/new", preferred?.repoFullName)
    }

    @Test
    fun emptyGroupHasNoOwner() {
        assertNull(InstalledResolution.preferred(emptyList(), null))
    }
}
