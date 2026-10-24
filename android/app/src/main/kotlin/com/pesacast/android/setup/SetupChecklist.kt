package com.pesacast.android.setup

/** What the setup screen needs to know about the phone. Implemented on Android and faked in tests. */
interface DeviceState {
    val sdkInt: Int
    val manufacturer: String
    val smsGranted: Boolean
    val notificationsGranted: Boolean
    val ignoringBatteryOptimizations: Boolean
    val serviceRunning: Boolean
    val webhookEnabled: Boolean
    val wifiEnabled: Boolean
    /** Bluetooth hardware present and its permissions granted, so the BLE link to a desktop can run. */
    val bluetoothAvailable: Boolean
    /** The user said they changed the brand-specific autostart setting. */
    val oemStepAcknowledged: Boolean
    /** True if the last end-to-end test passed, false if it failed, null if never run. */
    val lastTestPassed: Boolean?
}

enum class StepId { Sms, RestrictedSettings, Notifications, Battery, OemBackground, Service, Delivery, EndToEndTest }

enum class StepStatus {
    /** Satisfied. */
    Done,
    /** Must be fixed for the phone to work at all. */
    Required,
    /** Not a blocker, but the phone will be less reliable without it. */
    Recommended,
    /** Cannot be detected by the app; the user confirms they did it. */
    Manual,
}

data class SetupStep(val id: StepId, val status: StepStatus)

enum class OemFamily { Transsion, Xiaomi, Oppo, Vivo, Samsung, Huawei }

object SetupChecklist {

    /** Steps in the order a person should do them. Steps that do not apply to this phone are left out. */
    fun evaluate(s: DeviceState): List<SetupStep> {
        val steps = mutableListOf<SetupStep>()

        steps += SetupStep(StepId.Sms, if (s.smsGranted) StepStatus.Done else StepStatus.Required)

        // Android 13+ can block the SMS permission for apps installed outside a store until the user
        // allows "restricted settings" in App info. Only worth showing while SMS is still missing.
        if (!s.smsGranted && s.sdkInt >= 33) {
            steps += SetupStep(StepId.RestrictedSettings, StepStatus.Recommended)
        }

        if (s.sdkInt >= 33) {
            steps += SetupStep(StepId.Notifications, if (s.notificationsGranted) StepStatus.Done else StepStatus.Recommended)
        }

        steps += SetupStep(StepId.Battery, if (s.ignoringBatteryOptimizations) StepStatus.Done else StepStatus.Recommended)

        if (oemFamily(s.manufacturer) != null) {
            steps += SetupStep(StepId.OemBackground, if (s.oemStepAcknowledged) StepStatus.Done else StepStatus.Manual)
        }

        steps += SetupStep(StepId.Service, if (s.serviceRunning) StepStatus.Done else StepStatus.Required)

        val hasDestination = s.webhookEnabled || s.wifiEnabled || s.bluetoothAvailable
        steps += SetupStep(StepId.Delivery, if (hasDestination) StepStatus.Done else StepStatus.Required)

        steps += SetupStep(
            StepId.EndToEndTest,
            when (s.lastTestPassed) {
                true -> StepStatus.Done
                else -> StepStatus.Recommended
            },
        )
        return steps
    }

    data class Summary(val done: Int, val total: Int, val ready: Boolean, val firstTodo: StepId?)

    /** [ready] means nothing required is outstanding; recommended steps only affect reliability. */
    fun summarize(steps: List<SetupStep>): Summary {
        val done = steps.count { it.status == StepStatus.Done }
        val ready = steps.none { it.status == StepStatus.Required }
        val firstTodo = steps.firstOrNull { it.status == StepStatus.Required }?.id
            ?: steps.firstOrNull { it.status != StepStatus.Done }?.id
        return Summary(done, steps.size, ready, firstTodo)
    }

    /** Which phone makers are known for killing background apps, matched on `Build.MANUFACTURER`. */
    fun oemFamily(manufacturer: String): OemFamily? {
        val m = manufacturer.lowercase()
        return when {
            listOf("tecno", "infinix", "itel", "transsion").any(m::contains) -> OemFamily.Transsion
            listOf("xiaomi", "redmi", "poco").any(m::contains) -> OemFamily.Xiaomi
            listOf("oppo", "realme", "oneplus").any(m::contains) -> OemFamily.Oppo
            listOf("vivo", "iqoo").any(m::contains) -> OemFamily.Vivo
            "samsung" in m -> OemFamily.Samsung
            listOf("huawei", "honor").any(m::contains) -> OemFamily.Huawei
            else -> null
        }
    }
}

/** What an end-to-end test did and whether it counts as passing. */
data class EndToEndSummary(val passed: Boolean, val lines: List<String>)

object EndToEnd {
    /**
     * [webhook] is the result of the webhook ping, or null if the webhook is not set up.
     * [desktopsReached] is how many desktop connections the test transaction was sent to.
     * [wifiOrBluetoothConfigured] says whether a desktop link is switched on at all.
     *
     * Passes when at least one destination worked and none that is configured failed. A desktop link
     * that is on but has no desktop connected right now is a warning, not a failure: the transaction
     * is queued for it.
     */
    fun summarize(
        webhook: WebhookOutcome?,
        desktopsReached: Int,
        wifiOrBluetoothConfigured: Boolean,
    ): EndToEndSummary {
        val lines = mutableListOf<String>()
        var anySuccess = false
        var anyFailure = false

        when (webhook) {
            null -> Unit
            is WebhookOutcome.Ok -> {
                anySuccess = true
                lines += "Webhook: accepted the test (HTTP ${webhook.code})."
            }
            is WebhookOutcome.Failed -> {
                anyFailure = true
                lines += "Webhook: ${webhook.message}"
            }
        }

        if (desktopsReached > 0) {
            anySuccess = true
            lines += "Desktop: test sent to $desktopsReached connected desktop(s)."
        } else if (wifiOrBluetoothConfigured) {
            lines += "Desktop: no desktop is connected right now. The test is queued for the next one."
        }

        if (webhook == null && !wifiOrBluetoothConfigured) {
            lines += "Nothing to test yet: turn on the webhook, WiFi or Bluetooth first."
        }
        return EndToEndSummary(passed = anySuccess && !anyFailure, lines = lines)
    }
}

sealed class WebhookOutcome {
    data class Ok(val code: Int) : WebhookOutcome()
    data class Failed(val message: String) : WebhookOutcome()
}
