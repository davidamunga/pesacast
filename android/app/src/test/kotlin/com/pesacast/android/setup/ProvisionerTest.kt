package com.pesacast.android.setup

import com.pesacast.android.webhook.TestResult
import com.pesacast.android.webhook.WebhookConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private class RecordingStore : ProvisionStore {
    val events = mutableListOf<String>()
    override fun setEnabled(enabled: Boolean) { events += "enabled=$enabled" }
    override fun save(url: String, token: String, secret: String) { events += "save($url,$token,$secret)" }
    override fun markVerified() { events += "verified" }
    override fun wakeSender() { events += "wake" }
}

class ProvisionerTest {
    private val request = ProvisionRequest.parse("https://example.com/hook", "tok", "sec") as ProvisionRequest.Valid

    @Test
    fun `a successful test saves, verifies and only then enables, in that order`() {
        val store = RecordingStore()
        var tested: WebhookConfig? = null
        val outcome = Provisioner.apply(request, store) { cfg ->
            store.events += "test"
            tested = cfg
            TestResult.Success(200)
        }
        assertEquals(ProvisionOutcome.Enabled(200), outcome)
        assertEquals(
            listOf("enabled=false", "save(https://example.com/hook,tok,sec)", "test", "verified", "enabled=true", "wake"),
            store.events,
        )
        assertEquals("https://example.com/hook", tested!!.url)
        assertEquals("tok", tested!!.token)
        assertEquals("sec", tested!!.secret)
    }

    @Test
    fun `a failed test keeps the webhook off and never marks the values verified`() {
        val store = RecordingStore()
        val outcome = Provisioner.apply(request, store) { TestResult.Failure("The server answered HTTP 500") }
        assertEquals(ProvisionOutcome.SavedNotEnabled("The server answered HTTP 500"), outcome)
        assertTrue("enabled=true" !in store.events)
        assertTrue("verified" !in store.events)
        assertTrue("wake" !in store.events)
        assertEquals("enabled=false", store.events.first())
    }

    @Test
    fun `the webhook is switched off before anything is stored or contacted`() {
        val store = RecordingStore()
        Provisioner.apply(request, store) {
            assertEquals("the first thing recorded must be the switch-off", "enabled=false", store.events.first())
            assertTrue(store.events.any { it.startsWith("save(") })
            TestResult.Failure("x")
        }
    }

    @Test
    fun `missing credentials are stored as empty so an old token cannot linger`() {
        val bare = ProvisionRequest.parse("https://example.com/hook", null, null) as ProvisionRequest.Valid
        val store = RecordingStore()
        Provisioner.apply(bare, store) { TestResult.Success(204) }
        assertTrue(store.events.contains("save(https://example.com/hook,,)"))
    }
}
