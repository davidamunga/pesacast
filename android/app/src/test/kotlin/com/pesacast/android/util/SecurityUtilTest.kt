package com.pesacast.android.util

import android.Manifest
import com.pesacast.android.webhook.SecretCipher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A reversible fake: "enc:" prefix. Real encryption is Keystore-backed and needs a device. */
private class FakeCipher(var broken: Boolean = false) : SecretCipher {
    override fun encrypt(plain: String) = "enc:$plain"
    override fun decrypt(encoded: String): String? =
        if (broken || !encoded.startsWith("enc:")) null else encoded.removePrefix("enc:")
}

class MigratingSecretTest {
    private val store = mutableMapOf<String, String>()
    private val cipher = FakeCipher()

    private fun secret() = MigratingSecret(
        cipher,
        get = { store[it] },
        put = { k, v -> if (v == null) store.remove(k) else store[k] = v },
        encryptedKey = "tok_enc",
        legacyPlaintextKey = "tok",
    )

    @Test
    fun `nothing stored reads as null`() {
        assertNull(secret().read())
    }

    @Test
    fun `a value is stored encrypted and read back`() {
        secret().write("abc123")
        assertEquals("enc:abc123", store["tok_enc"])
        assertFalse("plaintext must not be stored", store.containsValue("abc123"))
        assertEquals("abc123", secret().read())
    }

    @Test
    fun `an old plaintext value is migrated on first read and the plaintext removed`() {
        store["tok"] = "legacy-token"
        assertEquals("legacy-token", secret().read())
        assertEquals("enc:legacy-token", store["tok_enc"])
        assertFalse(store.containsKey("tok"))
        // A second read comes from the encrypted copy.
        assertEquals("legacy-token", secret().read())
    }

    @Test
    fun `writing clears a plaintext leftover`() {
        store["tok"] = "old"
        secret().write("new")
        assertFalse(store.containsKey("tok"))
        assertEquals("new", secret().read())
    }

    @Test
    fun `an undecryptable value reads as null and never falls back to plaintext`() {
        secret().write("abc123")
        store["tok"] = "stale-plaintext"
        cipher.broken = true
        assertNull(secret().read())
    }

    @Test
    fun `null or blank clears the secret`() {
        secret().write("abc")
        secret().write(null)
        assertNull(secret().read())
        secret().write("abc")
        secret().write("  ")
        assertNull(secret().read())
        assertTrue(store.isEmpty())
    }
}

class PermissionPlanTest {
    private val sms = Manifest.permission.RECEIVE_SMS
    private val btConnect = Manifest.permission.BLUETOOTH_CONNECT
    private val btAdvertise = Manifest.permission.BLUETOOTH_ADVERTISE
    private val notifications = Manifest.permission.POST_NOTIFICATIONS

    @Test
    fun `scan is never requested and bluetooth is only asked for on android 12 and up`() {
        assertEquals(listOf(sms, btConnect, btAdvertise, notifications), PermissionPlan.toRequest(emptySet(), 34))
        assertEquals(listOf(sms, btConnect, btAdvertise), PermissionPlan.toRequest(emptySet(), 31))
        assertEquals(listOf(sms), PermissionPlan.toRequest(emptySet(), 30))
        assertFalse(PermissionPlan.toRequest(emptySet(), 35).contains(Manifest.permission.BLUETOOTH_SCAN))
    }

    @Test
    fun `already granted permissions are not asked for again`() {
        assertEquals(listOf(btAdvertise), PermissionPlan.toRequest(setOf(sms, btConnect, notifications), 34))
        assertTrue(PermissionPlan.toRequest(setOf(sms, btConnect, btAdvertise, notifications), 34).isEmpty())
    }

    @Test
    fun `sms alone is enough to run the service, so denying bluetooth does not block webhook or wifi`() {
        val state = PermissionPlan.evaluate(setOf(sms), 34)
        assertTrue(state.canRunService)
        assertFalse(state.bluetoothGranted)
        assertFalse(state.notificationsGranted)
    }

    @Test
    fun `without sms the service cannot run even if everything else is granted`() {
        val state = PermissionPlan.evaluate(setOf(btConnect, btAdvertise, notifications), 34)
        assertFalse(state.canRunService)
        assertTrue(state.bluetoothGranted)
    }

    @Test
    fun `on old android versions bluetooth and notification runtime grants are not required`() {
        val state = PermissionPlan.evaluate(setOf(sms), 28)
        assertTrue(state.bluetoothGranted)
        assertTrue(state.notificationsGranted)
    }
}
