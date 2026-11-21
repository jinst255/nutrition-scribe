package com.nutritionscribe.shared.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The update check (11-20): which GitHub release a phone moves to. A wrong pick here
 * would either never offer an update, offer the watch APK to the phone, or download
 * from somewhere that isn't this repo's releases.
 */
class AppUpdatesTest {

    private val base = AppUpdates.DOWNLOAD_PREFIX

    private fun asset(name: String, size: Long = 52_000_000, url: String = "$base" + "v-x/$name", digest: String? = "sha256:" + "ab".repeat(32)) =
        """{"name":"$name","size":$size,"browser_download_url":"$url"${digest?.let { ",\"digest\":\"$it\"" } ?: ""}}"""

    private fun release(tag: String, vararg assets: String, draft: Boolean = false, body: String = "Notes") =
        """{"tag_name":"$tag","draft":$draft,"prerelease":true,"body":"$body","assets":[${assets.joinToString(",")}]}"""

    @Test
    fun picksThePhoneApkNotTheWatchOne() {
        val text = "[" + release("v1.9-test",
            asset("NutritionScribe-Watch-1.9-test.apk", url = base + "v1.9-test/NutritionScribe-Watch-1.9-test.apk"),
            asset("NutritionScribe-1.9-test.apk", url = base + "v1.9-test/NutritionScribe-1.9-test.apk")) + "]"
        val r = AppUpdates.parse(text).single()
        assertEquals("1.9-test", r.version)
        assertEquals(base + "v1.9-test/NutritionScribe-1.9-test.apk", r.apkUrl)
        assertEquals("ab".repeat(32), r.sha256)
    }

    @Test
    fun aReleaseWithOnlyAWatchApkOffersNothing() {
        val text = "[" + release("v2.0-test", asset("NutritionScribe-Watch-2.0-test.apk", url = base + "v2.0-test/NutritionScribe-Watch-2.0-test.apk")) + "]"
        assertTrue(AppUpdates.parse(text).isEmpty())
    }

    @Test
    fun refusesDownloadsFromAnywhereButThisReposReleases() {
        val elsewhere = asset("NutritionScribe-3.0.apk", url = "https://example.com/NutritionScribe-3.0.apk")
        val plainHttp = asset("NutritionScribe-3.0.apk", url = "http://github.com/jinst255/nutrition-scribe/releases/download/v3.0/NutritionScribe-3.0.apk")
        val otherRepo = asset("NutritionScribe-3.0.apk", url = "https://github.com/someone/nutrition-scribe/releases/download/v3.0/NutritionScribe-3.0.apk")
        val climbsOut = asset("NutritionScribe-3.0.apk", url = base + "../../x/NutritionScribe-3.0.apk")
        val renamed = asset("NutritionScribe-3.0.apk", url = base + "v3.0/other.apk")
        for (a in listOf(elsewhere, plainHttp, otherRepo, climbsOut, renamed)) {
            assertTrue(a, AppUpdates.parse("[" + release("v3.0", a) + "]").isEmpty())
        }
    }

    @Test
    fun skipsDraftsAndSillySizes() {
        val ok = asset("NutritionScribe-3.0.apk", url = base + "v3.0/NutritionScribe-3.0.apk")
        assertTrue(AppUpdates.parse("[" + release("v3.0", ok, draft = true) + "]").isEmpty())
        assertTrue(AppUpdates.parse("[" + release("v3.0", asset("NutritionScribe-3.0.apk", size = 0, url = base + "v3.0/NutritionScribe-3.0.apk")) + "]").isEmpty())
        assertTrue(AppUpdates.parse("[" + release("v3.0", asset("NutritionScribe-3.0.apk", size = AppUpdates.MAX_APK_BYTES + 1, url = base + "v3.0/NutritionScribe-3.0.apk")) + "]").isEmpty())
    }

    @Test
    fun aMissingOrOddDigestMeansNoChecksumNotAFailure() {
        val none = asset("NutritionScribe-3.0.apk", url = base + "v3.0/NutritionScribe-3.0.apk", digest = null)
        val odd = asset("NutritionScribe-3.0.apk", url = base + "v3.0/NutritionScribe-3.0.apk", digest = "md5:abc")
        assertNull(AppUpdates.parse("[" + release("v3.0", none) + "]").single().sha256)
        assertNull(AppUpdates.parse("[" + release("v3.0", odd) + "]").single().sha256)
    }

    @Test
    fun badTextGivesNoReleasesInsteadOfCrashing() {
        for (t in listOf("", "null", "{}", "{\"message\":\"API rate limit exceeded\"}", "[1,2]", "[{\"tag_name\":5}]", "<html>")) {
            assertTrue(t, AppUpdates.parse(t).isEmpty())
        }
    }

    @Test
    fun versionsCompareByTheirNumbers() {
        assertTrue(AppUpdates.compare("1.10-test", "1.9-test")!! > 0)
        assertTrue(AppUpdates.compare("1.3.1-test", "1.3-test")!! > 0)
        assertTrue(AppUpdates.compare("2.0", "1.99-test")!! > 0)
        assertEquals(0, AppUpdates.compare("1.9-test", "1.9-test"))
        assertEquals(0, AppUpdates.compare("1.9", "1.9-test"))
        assertEquals(0, AppUpdates.compare("1.9.0", "1.9"))
        assertEquals(0, AppUpdates.compare("v1.9-test", "1.9-test"))
        assertNull(AppUpdates.compare("test", "1.9"))
        assertNull(AppUpdates.compare("1.9", ""))
    }

    @Test
    fun newestOnlyOffersSomethingAboveThisPhone() {
        fun r(v: String) = AppUpdates.Release(v, "v$v", "", base + "v$v/NutritionScribe-$v.apk", 1, null)
        val list = listOf(r("1.8-test"), r("1.10-test"), r("1.9-test"))
        assertEquals("1.10-test", AppUpdates.newest(list, "1.9-test")?.version)
        assertNull(AppUpdates.newest(list, "1.10-test"))
        assertNull(AppUpdates.newest(list, "1.11-test"))
        assertNull(AppUpdates.newest(emptyList(), "1.9-test"))
        assertNull(AppUpdates.newest(list, "debug-build"))
    }

    @Test
    fun looksAgainTwiceADayAtMost() {
        val now = 1_800_000_000_000L
        assertTrue(AppUpdates.dueToCheck(null, now))
        assertFalse(AppUpdates.dueToCheck(now - 60_000, now))
        assertTrue(AppUpdates.dueToCheck(now - AppUpdates.CHECK_EVERY_MS, now))
        assertTrue(AppUpdates.dueToCheck(now + 60_000, now)) // the clock went back
    }

    @Test
    fun notesBecomePlainLines() {
        val notes = "## Changes\n\n- **Updates** from GitHub\n* Watch `shortcuts`\n\n\n### Fixed\n- Eggs"
        assertEquals(listOf("Changes", "Updates from GitHub", "Watch shortcuts", "Fixed", "Eggs"), AppUpdates.noteLines(notes))
        assertEquals(2, AppUpdates.noteLines(notes, max = 2).size)
    }

    /*
     * Seen on the emulator 11-20: the 1.9-test notes open with "### Install" steps
     * (download the APK, allow installs), so What's new in the app showed how to install
     * the update instead of what's in it. The sheet takes the "New in" section.
     */
    @Test
    fun whatsNewShowsTheNewInSectionNotTheInstallSteps() {
        val notes = "**A beta build.** A food log.\n\n### Install\n1. Download the APK.\n2. Tap it.\n\n### New in 1.9\n- Watch app\n- Store foods\n\nShake to report."
        assertEquals(listOf("Watch app", "Store foods", "Shake to report."), AppUpdates.noteLines(notes))
        val noNewSection = "Intro\n### Install\n1. Download it\n### Fixed\n- Eggs"
        assertEquals(listOf("Intro", "Fixed", "Eggs"), AppUpdates.noteLines(noNewSection))
    }
}
