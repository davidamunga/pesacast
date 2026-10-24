package com.pesacast.android.ui

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.pesacast.android.util.PermissionPlan
import com.pesacast.android.R
import com.pesacast.android.setup.AndroidDeviceState
import com.pesacast.android.setup.SetupActivity
import com.pesacast.android.setup.SetupChecklist
import com.pesacast.android.databinding.ActivityMainBinding
import com.pesacast.android.model.MpesaTransaction
import com.pesacast.android.service.MirrorService
import com.pesacast.android.transport.TransportState
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val vm: MainViewModel by viewModels()
    private val txnAdapter = TransactionAdapter()

    private val permLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ -> handlePermissionResult() }

    /** Avoids stacking dialogs if results arrive while one is already showing. */
    private var permissionDialog: androidx.appcompat.app.AlertDialog? = null

    // MARK: - Lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupRecycler()
        setupBottomNav()
        setupControls()
        observeState()
        requestAllPermissions()
    }

    override fun onDestroy() {
        super.onDestroy()
        // Transport lifecycle is owned by MirrorService — do not stop it here.
    }

    // MARK: - Setup

    private fun setupRecycler() {
        binding.recyclerTransactions.apply {
            adapter = txnAdapter
            layoutManager = LinearLayoutManager(this@MainActivity)
        }
    }

    private fun setupBottomNav() {
        // Active indicator: green_600 at ~10% opacity. ColorStateList required by the API.
        val indicatorColor = ContextCompat.getColor(this, R.color.green_600)
        val alpha = (0.10f * 255).toInt()
        val indicatorWithAlpha = android.graphics.Color.argb(
            alpha,
            android.graphics.Color.red(indicatorColor),
            android.graphics.Color.green(indicatorColor),
            android.graphics.Color.blue(indicatorColor)
        )
        binding.bottomNav.itemActiveIndicatorColor =
            android.content.res.ColorStateList.valueOf(indicatorWithAlpha)

        binding.bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_transactions -> {
                    binding.tabTransactions.visibility = View.VISIBLE
                    binding.tabConnect.visibility = View.GONE
                    true
                }
                R.id.nav_connect -> {
                    binding.tabTransactions.visibility = View.GONE
                    binding.tabConnect.visibility = View.VISIBLE
                    true
                }
                else -> false
            }
        }
        // Banner/empty state "Connect" buttons both navigate to the Connect tab
        binding.btnBannerConnect.setOnClickListener {
            binding.bottomNav.selectedItemId = R.id.nav_connect
        }
        binding.btnConnectFromEmpty.setOnClickListener {
            binding.bottomNav.selectedItemId = R.id.nav_connect
        }
    }

    private fun setupControls() {
        binding.switchMirroring.isChecked = vm.prefs.mirroringEnabled
        binding.switchMirroring.setOnCheckedChangeListener { _, checked ->
            vm.prefs.mirroringEnabled = checked
        }
        binding.btnTest.setOnClickListener {
            vibrate()
            vm.sendTest()
        }

        val versionName = packageManager.getPackageInfo(packageName, 0).versionName
        binding.tvVersion.text = "v$versionName"

        setupWifiControls()
        setupWebhookControls()
        binding.btnOpenSetup.setOnClickListener { startActivity(Intent(this, SetupActivity::class.java)) }
    }

    private fun setupWifiControls() {
        binding.switchWifi.setOnCheckedChangeListener(null)
        binding.switchWifi.isChecked = vm.prefs.wifiEnabled
        updateWifiSectionsVisibility(vm.prefs.wifiEnabled)

        binding.switchWifi.setOnCheckedChangeListener { _, checked ->
            vm.setWifiEnabled(checked)
            updateWifiSectionsVisibility(checked)
        }

        binding.btnCopyIp.setOnClickListener {
            val ip = vm.localIp.value
            if (ip.isNotEmpty()) copyToClipboard(ip)
        }
        binding.btnShareIp.setOnClickListener {
            val ip = vm.localIp.value
            if (ip.isNotEmpty()) shareText(ip)
        }
        binding.btnCopyToken.setOnClickListener {
            val token = vm.wifiToken.value
            if (token.isNotEmpty()) copyToClipboard(token, sensitive = true)
        }
        binding.btnShareToken.setOnClickListener {
            val token = vm.wifiToken.value
            if (token.isNotEmpty()) shareText(token)
        }
        binding.btnSharePairingInfo.setOnClickListener { sharePairingInfo() }
        binding.btnRegenerateToken.setOnClickListener {
            vm.regenerateWifiToken()
            Toast.makeText(this, R.string.toast_token_regenerated, Toast.LENGTH_LONG).show()
        }

        binding.tvWifiPort.text = vm.prefs.wifiPort.toString()
    }

    private fun setupWebhookControls() {
        val settings = vm.webhookSettings
        binding.etWebhookUrl.setText(settings.url)
        binding.etWebhookToken.setText(settings.token)
        binding.etWebhookSecret.setText(settings.secret)

        if (settings.credentialsUnreadable()) showWebhookMessage(getString(R.string.webhook_credentials_unreadable))
        binding.switchWebhook.isChecked = vm.webhookEnabled.value
        binding.switchWebhook.setOnCheckedChangeListener { _, checked ->
            if (!vm.setWebhookEnabled(checked)) {
                binding.switchWebhook.isChecked = false
                if (checked) {
                    val noUrl = binding.etWebhookUrl.text.isNullOrBlank()
                    showWebhookMessage(getString(if (noUrl) R.string.webhook_needs_url else R.string.webhook_needs_test))
                }
            }
        }
        binding.btnWebhookTest.setOnClickListener {
            vm.testWebhook(
                binding.etWebhookUrl.text?.toString().orEmpty(),
                binding.etWebhookToken.text?.toString().orEmpty(),
                binding.etWebhookSecret.text?.toString().orEmpty(),
            )
        }
    }

    private fun renderSetupSummary() {
        val summary = SetupChecklist.summarize(SetupChecklist.evaluate(AndroidDeviceState(this)))
        binding.tvSetupSummary.text = getString(
            if (summary.ready) R.string.setup_summary_ready else R.string.setup_summary_blocked,
            summary.done, summary.total,
        )
    }

    private fun showWebhookMessage(message: String?) {
        binding.tvWebhookMessage.text = message
        binding.tvWebhookMessage.visibility = if (message.isNullOrEmpty()) View.GONE else View.VISIBLE
    }

    private fun renderWebhookTest(state: MainViewModel.WebhookTestUi) {
        when (state) {
            MainViewModel.WebhookTestUi.Idle -> Unit
            MainViewModel.WebhookTestUi.Running -> {
                binding.btnWebhookTest.isEnabled = false
                showWebhookMessage(getString(R.string.webhook_testing))
            }
            is MainViewModel.WebhookTestUi.Done -> {
                binding.btnWebhookTest.isEnabled = true
                showWebhookMessage(
                    when (val r = state.result) {
                        is com.pesacast.android.webhook.TestResult.Success ->
                            getString(R.string.webhook_test_ok, r.httpCode)
                        is com.pesacast.android.webhook.TestResult.Failure -> r.message
                    }
                )
            }
        }
    }

    private fun renderWebhookStatus(status: com.pesacast.android.webhook.WebhookStatus, enabled: Boolean) {
        if (binding.switchWebhook.isChecked != enabled) binding.switchWebhook.isChecked = enabled
        binding.tvWebhookStatus.text = when {
            !enabled -> getString(R.string.webhook_status_off)
            status.paused -> getString(R.string.webhook_status_paused, status.lastError.orEmpty())
            status.pending > 0 && status.lastError != null ->
                getString(R.string.webhook_status_error, status.lastError)
            status.pending > 0 -> getString(R.string.webhook_status_waiting, status.pending)
            else -> getString(R.string.webhook_status_idle)
        }
    }

    private fun updateWifiSectionsVisibility(enabled: Boolean) {
        binding.wifiDetailSection.visibility = if (enabled) View.VISIBLE else View.GONE
        if (enabled) vm.refreshLocalIp()
    }

    private fun observeState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { vm.combinedState.collect(::renderBadgeAndBanner) }
                launch { vm.bleState.collect(::renderBleState) }
                launch { vm.wifiState.collect(::renderWifiState) }
                launch { vm.wifiError.collect(::renderWifiError) }
                launch { vm.webhookTest.collect(::renderWebhookTest) }
                launch {
                    kotlinx.coroutines.flow.combine(vm.webhookStatus, vm.webhookEnabled) { s, e -> s to e }
                        .collect { (s, e) -> renderWebhookStatus(s, e) }
                }
                launch { vm.transactions.collect(::renderTransactions) }
                launch { vm.wifiToken.collect { token -> binding.tvWifiToken.text = token } }
                launch {
                    vm.localIp.collect { ip ->
                        binding.tvLocalIp.text = ip.ifEmpty { getString(R.string.ip_unavailable) }
                    }
                }
            }
        }
    }

    private fun color(resId: Int) = ContextCompat.getColor(this, resId)

    // MARK: - Rendering

    /**
     * Drives the app-bar summary badge and the transactions-tab disconnect banner.
     * Both are controlled by the same combined state so they stay in sync.
     */
    private fun renderBadgeAndBanner(state: TransportState) {
        val (dotColor, badgeText) = when (state) {
            is TransportState.Disconnected -> color(R.color.status_idle) to getString(R.string.status_disconnected)
            is TransportState.Connecting   -> color(R.color.status_warning) to getString(R.string.status_active)
            is TransportState.Connected    -> color(R.color.status_connected) to resources.getQuantityString(
                R.plurals.status_connected_devices, state.deviceCount, state.deviceCount
            )
        }
        (binding.bleStatusDot.background as? android.graphics.drawable.GradientDrawable)?.setColor(dotColor)
        binding.bleStatusLabel.text = badgeText

        // Banner: visible only when completely disconnected (not advertising/listening counts as inactive)
        val bannerVisible = state is TransportState.Disconnected
        val bannerVis = if (bannerVisible) View.VISIBLE else View.GONE
        binding.bannerNotConnected.visibility = bannerVis
        binding.bannerDivider.visibility = bannerVis

        // Empty state body text and CTA adapt to connection status
        val isActive = state !is TransportState.Disconnected
        binding.tvEmptyBody.text = getString(
            if (isActive) R.string.no_transactions_connected else R.string.no_transactions_disconnected
        )
        binding.btnConnectFromEmpty.visibility = if (isActive) View.GONE else View.VISIBLE
    }

    private fun renderBleState(state: TransportState) {
        val (color, statusText) = when (state) {
            is TransportState.Disconnected ->
                color(R.color.status_idle) to getString(R.string.status_disconnected)
            is TransportState.Connecting ->
                color(R.color.status_warning) to getString(R.string.status_ble_advertising)
            is TransportState.Connected ->
                color(R.color.status_connected) to resources.getQuantityString(
                    R.plurals.status_connected_devices, state.deviceCount, state.deviceCount
                )
        }
        (binding.bleCardDot.background as? android.graphics.drawable.GradientDrawable)?.setColor(color)
        binding.bleCardStatusLabel.text = statusText
    }

    private fun renderWifiState(state: TransportState) {
        if (state is TransportState.Disconnected && vm.wifiError.value != null) return
        val (color, statusText) = when (state) {
            is TransportState.Disconnected ->
                color(R.color.status_idle) to getString(R.string.status_disconnected)
            is TransportState.Connecting ->
                color(R.color.status_warning) to getString(R.string.status_wifi_listening)
            is TransportState.Connected ->
                color(R.color.status_connected) to resources.getQuantityString(
                    R.plurals.status_connected_devices, state.deviceCount, state.deviceCount
                )
        }
        (binding.wifiStatusDot.background as? android.graphics.drawable.GradientDrawable)?.setColor(color)
        binding.wifiStatusLabel.text = statusText
    }

    private fun renderWifiError(error: String?) {
        if (error == null) {
            renderWifiState(vm.wifiState.value)
            return
        }
        (binding.wifiStatusDot.background as? android.graphics.drawable.GradientDrawable)
            ?.setColor(color(R.color.status_error))
        binding.wifiStatusLabel.text = getString(R.string.status_wifi_error)
    }

    private fun renderTransactions(list: List<MpesaTransaction>) {
        txnAdapter.submitList(list)
        val hasItems = list.isNotEmpty()
        binding.recyclerTransactions.visibility = if (hasItems) View.VISIBLE else View.GONE
        binding.emptyState.visibility = if (hasItems) View.GONE else View.VISIBLE

        // Update the Transactions tab badge count
        val badge = binding.bottomNav.getOrCreateBadge(R.id.nav_transactions)
        badge.backgroundColor = ContextCompat.getColor(this, R.color.green_600)
        badge.badgeTextColor = Color.WHITE
        badge.maxCharacterCount = 3
        badge.isVisible = hasItems
        if (hasItems) badge.number = list.size
    }

    // MARK: - Clipboard & Share

    /** [sensitive] hides the copied text from the clipboard preview and from keyboards' suggestions. */
    private fun copyToClipboard(text: String, sensitive: Boolean = false) {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("PesaCast", text)
        if (sensitive) {
            clip.description.extras = android.os.PersistableBundle().apply {
                // ClipDescription.EXTRA_IS_SENSITIVE on Android 13+; the same key is read on 12 by some keyboards.
                putBoolean("android.content.extra.IS_SENSITIVE", true)
            }
        }
        cm.setPrimaryClip(clip)
        Toast.makeText(this, R.string.toast_copied, Toast.LENGTH_SHORT).show()
    }

    private fun shareText(text: String) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
        startActivity(Intent.createChooser(intent, null))
    }

    private fun sharePairingInfo() {
        val ip    = vm.localIp.value
        val token = vm.wifiToken.value
        val port  = vm.prefs.wifiPort

        val text = buildString {
            appendLine(getString(R.string.pairing_info_title))
            if (ip.isNotEmpty()) appendLine(getString(R.string.pairing_info_ip, ip))
            appendLine(getString(R.string.pairing_info_port, port))
            if (token.isNotEmpty()) appendLine(getString(R.string.pairing_info_token, token))
        }.trim()

        shareText(text)
    }

    // MARK: - Haptics

    private fun vibrate() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val manager = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            manager.defaultVibrator.vibrate(
                VibrationEffect.createOneShot(60, VibrationEffect.DEFAULT_AMPLITUDE)
            )
        } else {
            @Suppress("DEPRECATION")
            val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createOneShot(60, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(60)
            }
        }
    }

    // MARK: - Permissions

    private val hasBluetooth: Boolean
        get() = packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH)

    private fun grantedPermissions(): Set<String> =
        (PermissionPlan.smsPermissions() + PermissionPlan.bluetoothPermissions(Build.VERSION.SDK_INT) +
            PermissionPlan.notificationPermissions(Build.VERSION.SDK_INT))
            .filter(::hasPermission).toSet()

    /** Asks for whatever is still missing in one prompt; each denial is handled on its own afterwards. */
    private fun requestAllPermissions() {
        val needed = PermissionPlan.toRequest(grantedPermissions(), Build.VERSION.SDK_INT, hasBluetooth)
        if (needed.isEmpty()) onPermissionsGranted() else permLauncher.launch(needed.toTypedArray())
    }

    private fun handlePermissionResult() {
        val state = PermissionPlan.evaluate(grantedPermissions(), Build.VERSION.SDK_INT, hasBluetooth)
        // SMS alone is enough to run the service: webhook and WiFi delivery must not depend on Bluetooth.
        if (state.canRunService) onPermissionsGranted()

        if (!state.smsGranted) {
            // After a request, "no rationale" means the user chose not to be asked again.
            val permanent = !shouldShowRequestPermissionRationale(Manifest.permission.RECEIVE_SMS)
            showPermissionDialog(
                message = R.string.permission_sms_message,
                permanent = permanent,
                retry = { permLauncher.launch(PermissionPlan.smsPermissions().toTypedArray()) },
            )
        } else if (!state.bluetoothGranted) {
            Toast.makeText(this, R.string.permission_bluetooth_denied, Toast.LENGTH_LONG).show()
        }
    }

    private fun showPermissionDialog(message: Int, permanent: Boolean, retry: () -> Unit) {
        if (permissionDialog?.isShowing == true) return
        val builder = com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle(R.string.permission_needed_title)
            .setMessage(message)
            .setNegativeButton(R.string.permission_not_now, null)
        if (permanent) {
            builder.setPositiveButton(R.string.permission_open_settings) { _, _ ->
                startActivity(
                    Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = android.net.Uri.fromParts("package", packageName, null)
                    }
                )
            }
        } else {
            builder.setPositiveButton(R.string.permission_allow) { _, _ -> retry() }
        }
        permissionDialog = builder.show()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // Only a fixed, known value is accepted: nothing from the extra reaches the UI or storage.
        if (intent.getStringExtra(EXTRA_TAB) == TAB_CONNECT) binding.bottomNav.selectedItemId = R.id.nav_connect
    }

    override fun onResume() {
        super.onResume()
        renderSetupSummary()
        if (vm.prefs.consumeWifiTokenResetNotice()) {
            Toast.makeText(this, R.string.wifi_token_reset_notice, Toast.LENGTH_LONG).show()
            vm.refreshWifiToken()
        }
        // The user may have granted a permission in Settings while the app was in the background.
        if (PermissionPlan.evaluate(grantedPermissions(), Build.VERSION.SDK_INT, hasBluetooth).canRunService) onPermissionsGranted()
    }

    private fun onPermissionsGranted() {
        ContextCompat.startForegroundService(this, MirrorService.startIntent(this))
    }

    private fun hasPermission(perm: String) =
        ContextCompat.checkSelfPermission(this, perm) == PackageManager.PERMISSION_GRANTED

    companion object {
        /** Internal hand-off from the setup screen: open this tab. */
        const val EXTRA_TAB = "com.pesacast.android.extra.TAB"
        const val TAB_CONNECT = "connect"
    }
}
