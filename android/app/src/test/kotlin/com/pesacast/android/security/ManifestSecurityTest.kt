package com.pesacast.android.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/** Guards the manifest properties established by the Android security audit (ADR-0014). */
class ManifestSecurityTest {

    private val ns = "http://schemas.android.com/apk/res/android"

    private val manifest: Element by lazy {
        val file = listOf("src/main/AndroidManifest.xml", "app/src/main/AndroidManifest.xml")
            .map(::File).firstOrNull { it.exists() } ?: error("AndroidManifest.xml not found")
        DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            .newDocumentBuilder().parse(file).documentElement
    }

    private fun attr(e: Element, name: String): String = e.getAttributeNS(ns, name)

    private fun elements(tag: String): List<Element> =
        manifest.getElementsByTagName(tag).let { list -> (0 until list.length).map { list.item(it) as Element } }

    private val declaredPermissions get() = elements("uses-permission").map { attr(it, "name") }.toSet()

    @Test
    fun `backup and device transfer are disabled`() {
        val app = elements("application").single()
        assertEquals("false", attr(app, "allowBackup"))
        assertFalse(attr(app, "dataExtractionRules").isEmpty())
        assertFalse(attr(app, "fullBackupContent").isEmpty())
    }

    @Test
    fun `extraction rules exclude every domain from both backup and transfer`() {
        val rules = listOf("src/main/res/xml", "app/src/main/res/xml").map(::File).first { it.exists() }
        val extraction = File(rules, "data_extraction_rules.xml").readText()
        for (section in listOf("cloud-backup", "device-transfer")) {
            val block = extraction.substringAfter("<$section>").substringBefore("</$section>")
            for (domain in listOf("root", "file", "database", "sharedpref", "external")) {
                assertTrue("$section must exclude $domain", block.contains("""<exclude domain="$domain" />"""))
            }
            assertFalse("$section must not include anything", block.contains("<include"))
        }
        val legacy = File(rules, "backup_rules.xml").readText()
        assertFalse(legacy.contains("<include"))
        assertTrue(legacy.contains("""<exclude domain="sharedpref" />"""))
    }

    @Test
    fun `unused sensitive permissions are not declared`() {
        for (p in listOf("android.permission.READ_SMS", "android.permission.BLUETOOTH_SCAN", "android.permission.ACCESS_WIFI_STATE")) {
            assertFalse("$p is unused and must not be declared", p in declaredPermissions)
        }
    }

    @Test
    fun `sms permission and the permissions the app needs are still declared`() {
        for (p in listOf(
            "android.permission.RECEIVE_SMS",
            "android.permission.POST_NOTIFICATIONS",
            "android.permission.INTERNET",
            "android.permission.BLUETOOTH_CONNECT",
            "android.permission.BLUETOOTH_ADVERTISE",
            "android.permission.FOREGROUND_SERVICE_CONNECTED_DEVICE",
            // A runtime-denied Bluetooth permission must not stop the foreground service on Android 14+.
            "android.permission.CHANGE_WIFI_MULTICAST_STATE",
        )) assertTrue("$p must be declared", p in declaredPermissions)
    }

    @Test
    fun `the sms receiver only accepts broadcasts from the system`() {
        val receiver = elements("receiver").single { attr(it, "name") == ".sms.SmsReceiver" }
        assertEquals("android.permission.BROADCAST_SMS", attr(receiver, "permission"))
    }

    @Test
    fun `only the launcher and the provisioning screen are reachable from other apps without a permission`() {
        val exported = (elements("activity") + elements("service") + elements("receiver") + elements("provider"))
            .filter { attr(it, "exported") == "true" && attr(it, "permission").isEmpty() }
            .map { attr(it, "name") }
            .toSet()
        // The system still delivers BOOT_COMPLETED to a receiver that is not exported.
        assertEquals(setOf(".ui.MainActivity", ".setup.ProvisionActivity"), exported)
        val boot = elements("receiver").single { attr(it, "name") == ".service.BootReceiver" }
        assertEquals("false", attr(boot, "exported"))
    }

    @Test
    fun `the provisioning screen cannot be opened by a link or a web page`() {
        val provision = elements("activity").single { attr(it, "name") == ".setup.ProvisionActivity" }
        assertEquals("no intent filter: only an explicit component start can reach it", 0, provision.getElementsByTagName("intent-filter").length)
        assertEquals("true", attr(provision, "excludeFromRecents"))
        assertEquals("true", attr(provision, "noHistory"))
    }

    @Test
    fun `bluetooth hardware is optional so a phone without it can use the webhook or wifi`() {
        val feature = elements("uses-feature").single { attr(it, "name") == "android.hardware.bluetooth" }
        assertEquals("false", attr(feature, "required"))
    }

    @Test
    fun `the foreground service is private`() {
        val service = elements("service").single { attr(it, "name") == ".service.MirrorService" }
        assertEquals("false", attr(service, "exported"))
        assertNotNull(attr(service, "foregroundServiceType").takeIf { it.isNotEmpty() })
    }

    @Test
    fun `there are no content providers or custom permissions`() {
        assertTrue(elements("provider").isEmpty())
        assertTrue(elements("permission").isEmpty())
    }
}
