package com.pesacast.android.setup

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.pesacast.android.R
import com.pesacast.android.databinding.ActivitySetupBinding
import com.pesacast.android.databinding.ItemSetupStepBinding
import com.pesacast.android.service.MirrorService
import com.pesacast.android.transport.TransportManager
import com.pesacast.android.ui.MainActivity
import com.pesacast.android.util.PreferencesManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The setup checklist (ADR-0015): shows what a phone still needs, in the order to do it, with a
 * button that opens the matching system screen. The decisions live in [SetupChecklist]; this class
 * only reads the phone, opens screens and shows the result.
 */
class SetupActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySetupBinding
    private val adapter = StepAdapter(::onAction)

    private val requestSms = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        refresh()
        // "Don't ask again" (or a restricted-settings block): the only route left is App info.
        if (!granted && !shouldShowRequestPermissionRationale(Manifest.permission.RECEIVE_SMS)) openAppInfo()
        if (granted) startService()
    }

    private val requestNotifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) { refresh() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySetupBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.recyclerSteps.layoutManager = LinearLayoutManager(this)
        binding.recyclerSteps.adapter = adapter
        binding.btnRunTest.setOnClickListener { runTest() }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        val state = AndroidDeviceState(this)
        val steps = SetupChecklist.evaluate(state)
        val summary = SetupChecklist.summarize(steps)
        binding.tvSetupSummary.text = getString(
            if (summary.ready) R.string.setup_summary_ready else R.string.setup_summary_blocked,
            summary.done, summary.total,
        )
        adapter.submitList(steps.map { rowFor(it, state) })
    }

    // MARK: - Text for each step

    private fun rowFor(step: SetupStep, state: DeviceState): Row {
        val family = SetupChecklist.oemFamily(state.manufacturer)
        val (title, body, action) = when (step.id) {
            StepId.Sms -> Triple(getString(R.string.setup_sms_title), getString(R.string.setup_sms_body), R.string.setup_sms_action)
            StepId.RestrictedSettings -> Triple(getString(R.string.setup_restricted_title), getString(R.string.setup_restricted_body), R.string.setup_restricted_action)
            StepId.Notifications -> Triple(getString(R.string.setup_notifications_title), getString(R.string.setup_notifications_body), R.string.setup_notifications_action)
            StepId.Battery -> Triple(getString(R.string.setup_battery_title), getString(R.string.setup_battery_body), R.string.setup_battery_action)
            StepId.OemBackground -> Triple(
                getString(R.string.setup_oem_title, state.manufacturer.replaceFirstChar { it.uppercase() }),
                oemText(family, state.manufacturer),
                R.string.setup_oem_action,
            )
            StepId.Service -> Triple(getString(R.string.setup_service_title), getString(R.string.setup_service_body), R.string.setup_service_action)
            StepId.Delivery -> Triple(getString(R.string.setup_delivery_title), getString(R.string.setup_delivery_body), R.string.setup_delivery_action)
            StepId.EndToEndTest -> Triple(getString(R.string.setup_test_title), getString(R.string.setup_test_body), R.string.setup_test_action)
        }
        // Nothing to press once it is done (an end-to-end test can be re-run from the button below).
        val showAction = step.status != StepStatus.Done
        return Row(step.id, step.status, title, body, if (showAction) getString(action) else null)
    }

    private fun oemText(family: OemFamily?, manufacturer: String): String {
        val name = manufacturer.replaceFirstChar { it.uppercase() }
        val res = when (family) {
            OemFamily.Transsion -> R.string.setup_oem_transsion
            OemFamily.Xiaomi -> R.string.setup_oem_xiaomi
            OemFamily.Oppo -> R.string.setup_oem_oppo
            OemFamily.Vivo -> R.string.setup_oem_vivo
            OemFamily.Samsung -> R.string.setup_oem_samsung
            OemFamily.Huawei -> R.string.setup_oem_huawei
            null -> return ""
        }
        return getString(res, name, getString(R.string.setup_menus_vary))
    }

    // MARK: - Actions

    private fun onAction(id: StepId) {
        when (id) {
            StepId.Sms -> requestSms.launch(Manifest.permission.RECEIVE_SMS)
            StepId.RestrictedSettings -> openAppInfo()
            StepId.Notifications ->
                if (Build.VERSION.SDK_INT >= 33) requestNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
            StepId.Battery -> openBatterySettings()
            StepId.OemBackground -> {
                PreferencesManager(this).setupOemAcknowledged = true
                refresh()
            }
            StepId.Service -> startService()
            StepId.Delivery -> openConnectTab()
            StepId.EndToEndTest -> runTest()
        }
    }

    private fun startService() {
        if (!AndroidDeviceState(this).smsGranted) {
            Toast.makeText(this, R.string.setup_need_sms_first, Toast.LENGTH_SHORT).show()
            return
        }
        ContextCompat.startForegroundService(this, MirrorService.startIntent(this))
        lifecycleScope.launch {
            delay(600) // the service sets its flag once it is created
            refresh()
        }
    }

    private fun openAppInfo() {
        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", packageName, null)
        })
    }

    /** The list screen needs no permission; the direct "ignore optimisations" dialog would. */
    private fun openBatterySettings() {
        try {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        } catch (_: ActivityNotFoundException) {
            openAppInfo()
        }
    }

    private fun openConnectTab() {
        startActivity(Intent(this, MainActivity::class.java).apply {
            putExtra(MainActivity.EXTRA_TAB, MainActivity.TAB_CONNECT)
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        })
    }

    private fun runTest() {
        binding.btnRunTest.isEnabled = false
        binding.tvTestResult.visibility = View.VISIBLE
        binding.tvTestResult.text = getString(R.string.setup_testing)
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { TransportManager.getInstance(applicationContext).runEndToEndTest() }
            binding.btnRunTest.isEnabled = true
            val verdict = getString(if (result.passed) R.string.setup_test_passed else R.string.setup_test_failed)
            binding.tvTestResult.text = (listOf(verdict) + result.lines).joinToString("\n")
            refresh()
        }
    }

    // MARK: - List

    data class Row(val id: StepId, val status: StepStatus, val title: String, val body: String, val action: String?)

    private class StepAdapter(private val onAction: (StepId) -> Unit) :
        ListAdapter<Row, StepAdapter.Holder>(object : DiffUtil.ItemCallback<Row>() {
            override fun areItemsTheSame(a: Row, b: Row) = a.id == b.id
            override fun areContentsTheSame(a: Row, b: Row) = a == b
        }) {

        class Holder(val binding: ItemSetupStepBinding) : RecyclerView.ViewHolder(binding.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            Holder(ItemSetupStepBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val row = getItem(position)
            val ctx = holder.binding.root.context
            holder.binding.tvStepTitle.text = row.title
            holder.binding.tvStepBody.text = row.body
            holder.binding.tvStepBody.visibility = if (row.body.isBlank()) View.GONE else View.VISIBLE
            val (stateText, colour) = when (row.status) {
                StepStatus.Done -> R.string.setup_state_done to R.color.status_connected
                StepStatus.Required -> R.string.setup_state_required to R.color.status_error
                StepStatus.Recommended -> R.string.setup_state_recommended to R.color.status_warning
                StepStatus.Manual -> R.string.setup_state_manual to R.color.status_warning
            }
            holder.binding.tvStepState.setText(stateText)
            (holder.binding.stepDot.background.mutate() as? android.graphics.drawable.GradientDrawable)
                ?.setColor(ContextCompat.getColor(ctx, colour))
            holder.binding.btnStepAction.apply {
                visibility = if (row.action == null) View.GONE else View.VISIBLE
                text = row.action
                setOnClickListener { onAction(row.id) }
            }
        }
    }
}
