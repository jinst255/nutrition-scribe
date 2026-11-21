package com.nutritionscribe.app.data.update

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Updating from GitHub (11-20) needs two manifest lines. Without the install permission
 * Android refuses the install session outright. And the result receiver must stay
 * unexported: if it were exported, any app could send it a fake "needs your tap" with an
 * Intent of its own, and Nutrition Scribe would open it. Only the installer reaches it,
 * through the app's own PendingIntent.
 */
class UpdateManifestTest {
    private val manifest = File("src/main/AndroidManifest.xml").readText()

    @Test
    fun theAppMayAskToInstallItsUpdates() {
        assertTrue(manifest.contains("android.permission.REQUEST_INSTALL_PACKAGES"))
    }

    @Test
    fun theInstallResultReceiverIsNotExported() {
        val start = manifest.indexOf("android:name=\".data.update.UpdateResultReceiver\"")
        assertTrue("UpdateResultReceiver is declared", start >= 0)
        val tag = manifest.substring(manifest.lastIndexOf("<receiver", start), manifest.indexOf(">", start) + 1)
        assertTrue(tag, tag.contains("android:exported=\"false\""))
        assertFalse("no intent filter, or other apps could reach it", manifest.substring(start, manifest.indexOf("/>", start)).contains("intent-filter"))
    }
}
