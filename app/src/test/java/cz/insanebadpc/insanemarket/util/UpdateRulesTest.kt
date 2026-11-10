package cz.insanebadpc.insanemarket.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateRulesTest {
    @Test
    fun noInstalledAppNeverShowsUpdate() {
        val decision = UpdateRules.decide(
            installedVersionCode = null,
            installedTag = null,
            knownTag = "v1",
            knownVersionCode = 10,
            latestTag = "v2",
        )
        assertFalse(decision.hasUpdate)
        assertEquals(null, decision.learnedTag)
    }

    @Test
    fun downloadedApkVersionCodeProvesOlderInstalledTag() {
        val decision = UpdateRules.decide(
            installedVersionCode = 12,
            installedTag = null,
            knownTag = "v1",
            knownVersionCode = 10,
            latestTag = "v2",
        )
        assertTrue(decision.hasUpdate)
        assertEquals("v1", decision.learnedTag)
    }

    @Test
    fun sameTagMeansNoUpdate() {
        val decision = UpdateRules.decide(
            installedVersionCode = 10,
            installedTag = null,
            knownTag = "v2",
            knownVersionCode = 10,
            latestTag = "v2",
        )
        assertFalse(decision.hasUpdate)
        assertEquals("v2", decision.learnedTag)
    }

    @Test
    fun recordedTagDrivesUpdateWhenNoVersionCode() {
        val decision = UpdateRules.decide(
            installedVersionCode = 5,
            installedTag = "v1",
            knownTag = null,
            knownVersionCode = null,
            latestTag = "v2",
        )
        assertTrue(decision.hasUpdate)
        assertEquals("v1", decision.learnedTag)
    }

    @Test
    fun newerInstalledVersionSuppressesStaleNagging() {
        val decision = UpdateRules.decide(
            installedVersionCode = 12,
            installedTag = "v1",
            knownTag = "v1",
            knownVersionCode = 10,
            latestTag = "v2",
        )
        assertFalse(decision.hasUpdate)
        assertEquals("v2", decision.learnedTag)
    }

    @Test
    fun withoutProofNoUpdateIsClaimed() {
        val decision = UpdateRules.decide(
            installedVersionCode = 12,
            installedTag = null,
            knownTag = null,
            knownVersionCode = null,
            latestTag = "v2",
        )
        assertFalse(decision.hasUpdate)
        assertEquals(null, decision.learnedTag)
    }
}
