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
import com.pesacast.android.R
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
    ) { results ->
        if (results.values.all { it }) onPermissionsGranted()
    }

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
            if (token.isNotEmpty()) copyToClipboard(token)
        }
        binding.btnShareToken.setOnClickListener {
            val token = vm.wifiToken.value
            if (token.isNotEmpty()) shareText(token)
        }
        binding.btnSharePairingInfo.setOnClickListener { sharePairingInfo() }
        binding.btnRegenerateToken.setOnClickListener {
            vm.regenerateWifiToken()
            Toast.makeText(this, "Token regenerated — re-pair the desktop app", Toast.LENGTH_LONG).show()
        }

        binding.tvWifiPort.text = vm.prefs.wifiPort.toString()
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

    // MARK: - Rendering

    /**
     * Drives the app-bar summary badge and the transactions-tab disconnect banner.
     * Both are controlled by the same combined state so they stay in sync.
     */
    private fun renderBadgeAndBanner(state: TransportState) {
        val (dotColor, badgeText) = when (state) {
            is TransportState.Disconnected -> Color.GRAY to getString(R.string.status_disconnected)
            is TransportState.Connecting   -> Color.parseColor("#FFA000") to getString(R.string.status_active)
            is TransportState.Connected    -> Color.parseColor("#2E7D32") to resources.getQuantityString(
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

        // Amber dot in the banner
        val bannerDotColor = Color.parseColor("#FFA000")
        binding.bannerNotConnected.findViewWithTag<View?>("bannerDot")?.let {
            (it.background as? android.graphics.drawable.GradientDrawable)?.setColor(bannerDotColor)
        }
    }

    private fun renderBleState(state: TransportState) {
        val (color, statusText) = when (state) {
            is TransportState.Disconnected ->
                Color.GRAY to getString(R.string.status_disconnected)
            is TransportState.Connecting ->
                Color.parseColor("#FFA000") to getString(R.string.status_ble_advertising)
            is TransportState.Connected ->
                Color.parseColor("#2E7D32") to resources.getQuantityString(
                    R.plurals.status_connected_devices, state.deviceCount, state.deviceCount
                )
        }
        (binding.bleCardDot.background as? android.graphics.drawable.GradientDrawable)?.setColor(color)
        binding.bleCardStatusLabel.text = statusText
    }

    private fun renderWifiState(state: TransportState) {
        val (color, statusText) = when (state) {
            is TransportState.Disconnected ->
                Color.GRAY to getString(R.string.status_disconnected)
            is TransportState.Connecting ->
                Color.parseColor("#FFA000") to getString(R.string.status_wifi_listening)
            is TransportState.Connected ->
                Color.parseColor("#2E7D32") to resources.getQuantityString(
                    R.plurals.status_connected_devices, state.deviceCount, state.deviceCount
                )
        }
        (binding.wifiStatusDot.background as? android.graphics.drawable.GradientDrawable)?.setColor(color)
        binding.wifiStatusLabel.text = statusText
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

    private fun copyToClipboard(text: String) {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("PesaCast", text))
        Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show()
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
            appendLine("PesaCast WiFi Pairing")
            if (ip.isNotEmpty()) appendLine("IP: $ip")
            appendLine("Port: $port")
            if (token.isNotEmpty()) appendLine("Token: $token")
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

    private fun requestAllPermissions() {
        val needed = mutableListOf<String>()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (!hasPermission(Manifest.permission.BLUETOOTH_CONNECT))
                needed += Manifest.permission.BLUETOOTH_CONNECT
            if (!hasPermission(Manifest.permission.BLUETOOTH_SCAN))
                needed += Manifest.permission.BLUETOOTH_SCAN
            if (!hasPermission(Manifest.permission.BLUETOOTH_ADVERTISE))
                needed += Manifest.permission.BLUETOOTH_ADVERTISE
        }

        if (!hasPermission(Manifest.permission.RECEIVE_SMS))
            needed += Manifest.permission.RECEIVE_SMS

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            !hasPermission(Manifest.permission.POST_NOTIFICATIONS)
        ) {
            needed += Manifest.permission.POST_NOTIFICATIONS
        }

        if (needed.isNotEmpty()) {
            permLauncher.launch(needed.toTypedArray())
        } else {
            onPermissionsGranted()
        }
    }

    private fun onPermissionsGranted() {
        ContextCompat.startForegroundService(this, MirrorService.startIntent(this))
    }

    private fun hasPermission(perm: String) =
        ContextCompat.checkSelfPermission(this, perm) == PackageManager.PERMISSION_GRANTED
}
