package com.nutritionscribe.app.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * What broke (security review, 2026-11-04): on Android 12+, allowBackup="false"
 * does not stop phone-to-phone transfer. The encrypted API-key store was copied to
 * new phones, where it couldn't be decrypted and crashed the app. The manifest now
 * points at res/xml/data_extraction_rules.xml, which keeps that file out of
 * transfers and keeps cloud backup off.
 *
 * If one of these fails: someone renamed the secure store, edited the rules file,
 * or dropped android:dataExtractionRules from the manifest. The file name in the
 * rules must be SettingsRepository.PRODUCTION_SECURE_STORE + ".xml".
 */
class DataExtractionRulesTest {

    // Unit tests run with the module directory (app/) as the working directory.
    private val rulesFile = File("src/main/res/xml/data_extraction_rules.xml")
    private val manifest = File("src/main/AndroidManifest.xml")

    private fun section(name: String): Element {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(rulesFile)
        return doc.documentElement.getElementsByTagName(name).item(0) as Element
    }

    private fun excludes(section: Element): Set<Pair<String, String>> {
        val nodes = section.getElementsByTagName("exclude")
        return (0 until nodes.length).map { nodes.item(it) as Element }
            .map { it.getAttribute("domain") to it.getAttribute("path") }.toSet()
    }

    @Test
    fun `the manifest uses the rules file`() {
        assertTrue(manifest.readText().contains("android:dataExtractionRules=\"@xml/data_extraction_rules\""))
    }

    @Test
    fun `the encrypted key store never moves to another phone`() {
        val file = SettingsRepository.PRODUCTION_SECURE_STORE + ".xml"
        assertTrue("device-transfer must exclude $file",
            "sharedpref" to file in excludes(section("device-transfer")))
    }

    @Test
    fun `device transfer still carries the food log and settings`() {
        // Only the key store is left out; the rest moves as it always did.
        assertEquals(setOf("sharedpref" to SettingsRepository.PRODUCTION_SECURE_STORE + ".xml"),
            excludes(section("device-transfer")))
    }

    @Test
    fun `cloud backup stays off for every domain`() {
        val cloud = excludes(section("cloud-backup"))
        for (domain in listOf("root", "file", "database", "sharedpref", "external")) {
            assertTrue("cloud-backup must exclude all of $domain", domain to "." in cloud)
        }
    }
}
