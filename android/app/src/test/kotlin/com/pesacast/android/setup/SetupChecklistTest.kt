package com.pesacast.android.setup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private data class FakeState(
    override val sdkInt: Int = 34,
    override val manufacturer: String = "Google",
    override val smsGranted: Boolean = true,
    override val notificationsGranted: Boolean = true,
    override val ignoringBatteryOptimizations: Boolean = true,
    override val serviceRunning: Boolean = true,
    override val webhookEnabled: Boolean = true,
    override val wifiEnabled: Boolean = false,
    override val bluetoothAvailable: Boolean = false,
    override val oemStepAcknowledged: Boolean = false,
    override val lastTestPassed: Boolean? = true,
) : DeviceState

class SetupChecklistTest {

    private fun ids(state: DeviceState) = SetupChecklist.evaluate(state).map { it.id }
    private fun status(state: DeviceState, id: StepId) = SetupChecklist.evaluate(state).single { it.id == id }.status

    @Test
    fun `a fully set up phone is ready with everything done`() {
        val steps = SetupChecklist.evaluate(FakeState())
        assertTrue(steps.all { it.status == StepStatus.Done })
        val summary = SetupChecklist.summarize(steps)
        assertTrue(summary.ready)
        assertEquals(summary.total, summary.done)
        assertNull(summary.firstTodo)
    }

    @Test
    fun `without the sms permission the phone is not ready and sms is the first thing to fix`() {
        val steps = SetupChecklist.evaluate(FakeState(smsGranted = false))
        val summary = SetupChecklist.summarize(steps)
        assertFalse(summary.ready)
        assertEquals(StepId.Sms, summary.firstTodo)
    }

    @Test
    fun `the restricted settings hint appears only while sms is missing on android 13 and up`() {
        assertTrue(StepId.RestrictedSettings in ids(FakeState(smsGranted = false, sdkInt = 34)))
        assertFalse(StepId.RestrictedSettings in ids(FakeState(smsGranted = true, sdkInt = 34)))
        assertFalse(StepId.RestrictedSettings in ids(FakeState(smsGranted = false, sdkInt = 30)))
        assertEquals(StepStatus.Recommended, status(FakeState(smsGranted = false), StepId.RestrictedSettings))
    }

    @Test
    fun `the notification step exists only on android 13 and up and never blocks`() {
        assertFalse(StepId.Notifications in ids(FakeState(sdkInt = 31)))
        assertEquals(StepStatus.Recommended, status(FakeState(notificationsGranted = false), StepId.Notifications))
        assertTrue(SetupChecklist.summarize(SetupChecklist.evaluate(FakeState(notificationsGranted = false))).ready)
    }

    @Test
    fun `battery optimisation is recommended, not required`() {
        assertEquals(StepStatus.Recommended, status(FakeState(ignoringBatteryOptimizations = false), StepId.Battery))
        assertTrue(SetupChecklist.summarize(SetupChecklist.evaluate(FakeState(ignoringBatteryOptimizations = false))).ready)
    }

    @Test
    fun `a stopped service blocks readiness`() {
        assertFalse(SetupChecklist.summarize(SetupChecklist.evaluate(FakeState(serviceRunning = false))).ready)
    }

    @Test
    fun `at least one destination is required`() {
        val none = FakeState(webhookEnabled = false, wifiEnabled = false, bluetoothAvailable = false)
        assertEquals(StepStatus.Required, status(none, StepId.Delivery))
        for (state in listOf(none.copy(webhookEnabled = true), none.copy(wifiEnabled = true), none.copy(bluetoothAvailable = true))) {
            assertEquals(StepStatus.Done, status(state, StepId.Delivery))
        }
    }

    @Test
    fun `the end to end test is recommended until it has passed, including after a failure`() {
        assertEquals(StepStatus.Recommended, status(FakeState(lastTestPassed = null), StepId.EndToEndTest))
        assertEquals(StepStatus.Recommended, status(FakeState(lastTestPassed = false), StepId.EndToEndTest))
        assertEquals(StepStatus.Done, status(FakeState(lastTestPassed = true), StepId.EndToEndTest))
    }

    @Test
    fun `brand specific step appears for known makers and needs a manual confirmation`() {
        assertFalse(StepId.OemBackground in ids(FakeState(manufacturer = "Google")))
        assertEquals(StepStatus.Manual, status(FakeState(manufacturer = "TECNO"), StepId.OemBackground))
        assertEquals(StepStatus.Done, status(FakeState(manufacturer = "TECNO", oemStepAcknowledged = true), StepId.OemBackground))
        // A manual step is not a blocker.
        assertTrue(SetupChecklist.summarize(SetupChecklist.evaluate(FakeState(manufacturer = "TECNO"))).ready)
    }

    @Test
    fun `steps come in the order a person should do them`() {
        val order = ids(FakeState(smsGranted = false, manufacturer = "Infinix", sdkInt = 34))
        assertEquals(
            listOf(StepId.Sms, StepId.RestrictedSettings, StepId.Notifications, StepId.Battery, StepId.OemBackground,
                StepId.Service, StepId.Delivery, StepId.EndToEndTest),
            order,
        )
    }

    @Test
    fun `makers are recognised from the build manufacturer string`() {
        assertEquals(OemFamily.Transsion, SetupChecklist.oemFamily("TECNO"))
        assertEquals(OemFamily.Transsion, SetupChecklist.oemFamily("Infinix"))
        assertEquals(OemFamily.Transsion, SetupChecklist.oemFamily("itel"))
        assertEquals(OemFamily.Transsion, SetupChecklist.oemFamily("Transsion Holdings"))
        assertEquals(OemFamily.Xiaomi, SetupChecklist.oemFamily("Xiaomi"))
        assertEquals(OemFamily.Oppo, SetupChecklist.oemFamily("realme"))
        assertEquals(OemFamily.Vivo, SetupChecklist.oemFamily("vivo"))
        assertEquals(OemFamily.Samsung, SetupChecklist.oemFamily("samsung"))
        assertEquals(OemFamily.Huawei, SetupChecklist.oemFamily("HONOR"))
        assertNull(SetupChecklist.oemFamily("Google"))
        assertNull(SetupChecklist.oemFamily("HMD Global"))
        assertNull(SetupChecklist.oemFamily(""))
    }

    @Test
    fun `when nothing required is missing the first todo is the first recommended or manual step`() {
        val steps = SetupChecklist.evaluate(FakeState(ignoringBatteryOptimizations = false, lastTestPassed = null))
        val summary = SetupChecklist.summarize(steps)
        assertTrue(summary.ready)
        assertEquals(StepId.Battery, summary.firstTodo)
    }
}

class EndToEndTest {
    @Test
    fun `a webhook that accepts the test passes`() {
        val r = EndToEnd.summarize(WebhookOutcome.Ok(200), desktopsReached = 0, wifiOrBluetoothConfigured = false)
        assertTrue(r.passed)
        assertTrue(r.lines.single().contains("200"))
    }

    @Test
    fun `a failing webhook fails the test even if a desktop worked`() {
        val r = EndToEnd.summarize(WebhookOutcome.Failed("The server answered HTTP 500"), 1, true)
        assertFalse(r.passed)
        assertTrue(r.lines.any { it.contains("HTTP 500") })
    }

    @Test
    fun `a desktop that received the test passes without a webhook`() {
        val r = EndToEnd.summarize(null, desktopsReached = 2, wifiOrBluetoothConfigured = true)
        assertTrue(r.passed)
        assertTrue(r.lines.single().contains("2"))
    }

    @Test
    fun `a configured desktop link with nobody connected is a warning, not a pass on its own`() {
        val r = EndToEnd.summarize(null, desktopsReached = 0, wifiOrBluetoothConfigured = true)
        assertFalse("nothing was actually delivered", r.passed)
        assertTrue(r.lines.single().contains("queued"))
    }

    @Test
    fun `a working webhook passes even if the desktop link has nobody connected`() {
        val r = EndToEnd.summarize(WebhookOutcome.Ok(204), 0, true)
        assertTrue(r.passed)
        assertEquals(2, r.lines.size)
    }

    @Test
    fun `with nothing configured there is nothing to pass`() {
        val r = EndToEnd.summarize(null, 0, false)
        assertFalse(r.passed)
        assertTrue(r.lines.single().contains("Nothing to test"))
    }
}
