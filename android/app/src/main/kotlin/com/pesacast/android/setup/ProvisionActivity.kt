package com.pesacast.android.setup

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.pesacast.android.R
import com.pesacast.android.databinding.ActivityProvisionBinding
import com.pesacast.android.transport.TransportManager
import com.pesacast.android.webhook.WebhookSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Lets `adb` (or another app) offer a webhook configuration, for example
 *
 *     adb shell am start -n com.pesacast.android/.setup.ProvisionActivity \
 *         --es webhook_url https://example.com/hook --es webhook_token ... --es webhook_secret ...
 *
 * This activity has to be exported so `adb` can start it, which means any app can start it too.
 * What makes that safe (ADR-0015) is that **nothing is applied until the user taps Save and test**
 * on a screen that shows the destination host, only `https` is accepted, the webhook is only
 * switched on after a successful test, there is no intent filter (so no web page or link can open
 * it), and the incoming extras are dropped as soon as they are read. Do not weaken any of these.
 */
class ProvisionActivity : AppCompatActivity() {

    private lateinit var binding: ActivityProvisionBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityProvisionBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val request = ProvisionRequest.parse(
            intent.getStringExtra(EXTRA_URL),
            intent.getStringExtra(EXTRA_TOKEN),
            intent.getStringExtra(EXTRA_SECRET),
        )
        // The values now live only in `request`; do not keep a copy in the activity's intent.
        intent = Intent()

        binding.btnCancel.setOnClickListener { finish() }
        when (request) {
            is ProvisionRequest.Invalid -> showInvalid(request)
            is ProvisionRequest.Valid -> showConfirmation(request)
        }
    }

    private fun showInvalid(request: ProvisionRequest.Invalid) {
        binding.tvHeadline.setText(R.string.provision_invalid_title)
        binding.tvHost.text = request.reason
        binding.tvUrl.setText(R.string.provision_invalid_note)
        binding.tvDetails.visibility = View.GONE
        binding.tvWarning.visibility = View.GONE
        binding.btnConfirm.visibility = View.GONE
        binding.btnCancel.setText(R.string.provision_close)
    }

    private fun showConfirmation(request: ProvisionRequest.Valid) {
        val settings = WebhookSettings(this)
        binding.tvHeadline.setText(R.string.provision_headline)
        binding.tvHost.text = request.displayHost
        binding.tvUrl.text = request.url
        binding.tvDetails.text = buildString {
            append(
                getString(
                    when {
                        request.token != null && request.secret != null -> R.string.provision_details_with_both
                        request.token != null -> R.string.provision_details_token
                        request.secret != null -> R.string.provision_details_secret
                        else -> R.string.provision_details_none
                    }
                )
            )
            val current = settings.url
            if (current.isNotBlank() && current != request.url) {
                append("\n\n").append(getString(R.string.provision_replaces, current))
            }
            if (request.isPunycode) append("\n\n").append(getString(R.string.provision_punycode))
        }
        binding.tvWarning.setText(R.string.provision_warning)
        binding.btnConfirm.setOnClickListener { apply(request) }
    }

    private fun apply(request: ProvisionRequest.Valid) {
        binding.btnConfirm.isEnabled = false
        binding.btnCancel.isEnabled = false
        binding.tvResult.visibility = View.VISIBLE
        binding.tvResult.setText(R.string.provision_testing)

        val appContext = applicationContext
        lifecycleScope.launch {
            val outcome = withContext(Dispatchers.IO) {
                val transport = TransportManager.getInstance(appContext)
                Provisioner.apply(request, AndroidProvisionStore(transport.webhookSettings, transport), transport::sendWebhookTest)
            }
            binding.btnConfirm.visibility = View.GONE
            binding.btnCancel.isEnabled = true
            binding.btnCancel.setText(R.string.provision_close)
            binding.tvResult.text = when (outcome) {
                is ProvisionOutcome.Enabled -> getString(R.string.provision_enabled, outcome.httpCode)
                is ProvisionOutcome.SavedNotEnabled -> getString(R.string.provision_not_enabled, outcome.message)
            }
        }
    }

    private class AndroidProvisionStore(
        private val settings: WebhookSettings,
        private val transport: TransportManager,
    ) : ProvisionStore {
        override fun setEnabled(enabled: Boolean) { settings.enabled = enabled }
        override fun save(url: String, token: String, secret: String) {
            settings.url = url
            settings.token = token
            settings.secret = secret
        }
        override fun markVerified() = settings.markVerified()
        override fun wakeSender() = transport.webhook.kick()
    }

    companion object {
        const val EXTRA_URL = "webhook_url"
        const val EXTRA_TOKEN = "webhook_token"
        const val EXTRA_SECRET = "webhook_secret"
    }
}
