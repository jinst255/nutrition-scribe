package com.nutritionscribe.app.data

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * What broke (found 11-19 building the watch app): the phone's WearMessageListenerService
 * declared MESSAGE_RECEIVED with no <data> element. Wear OS only delivers messages to a
 * listener whose filter names the wear scheme (and a host), so everything the watch
 * sent could be dropped before the phone app saw it, which fits "the relay has never
 * worked". Data events for the watch need the same. This reads the manifest so a
 * filter edited back to the bare action fails here, not on someone's wrist.
 */
class WatchListenerManifestTest {
    private val manifest = File("src/main/AndroidManifest.xml").readText()

    private fun filterOf(service: String): String {
        val start = manifest.indexOf("android:name=\"$service\"")
        assertTrue("$service is declared", start >= 0)
        return manifest.substring(start, manifest.indexOf("</service>", start))
    }

    @Test
    fun `the phone's watch listener can receive watch messages`() {
        val f = filterOf(".data.WearMessageListenerService")
        assertTrue(f.contains("com.google.android.gms.wearable.MESSAGE_RECEIVED"))
        assertTrue("needs <data android:scheme=\"wear\" …>", Regex("""<data[^>]*android:scheme="wear"""").containsMatchIn(f))
        assertTrue("needs a host", Regex("""<data[^>]*android:host="\*"""").containsMatchIn(f))
    }
}
