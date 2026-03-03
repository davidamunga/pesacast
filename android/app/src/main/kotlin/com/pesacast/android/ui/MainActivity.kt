package com.pesacast.android.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.View
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

    // ── Permission launchers ──

    private val permLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        if (results.values.all { it }) onPermissionsGranted()
        // partial grants handled gracefully inside MirrorService
    }

    // MARK: - Lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupRecycler()
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
            isNestedScrollingEnabled = false
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
    }

    private fun observeState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { vm.bleState.collect(::renderBleState) }
                launch { vm.transactions.collect(::renderTransactions) }
            }
        }
    }

    // MARK: - Rendering

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
        listOf(binding.bleStatusDot, binding.bleCardDot).forEach { dot ->
            (dot.background as? android.graphics.drawable.GradientDrawable)?.setColor(color)
        }
        binding.bleStatusLabel.text = statusText
    }

    private fun renderTransactions(list: List<MpesaTransaction>) {
        txnAdapter.submitList(list)
        binding.emptyTransactions.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
        binding.recyclerTransactions.visibility = if (list.isEmpty()) View.GONE else View.VISIBLE
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

        // Bluetooth permissions (Android 12+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (!hasPermission(Manifest.permission.BLUETOOTH_CONNECT))
                needed += Manifest.permission.BLUETOOTH_CONNECT
            if (!hasPermission(Manifest.permission.BLUETOOTH_SCAN))
                needed += Manifest.permission.BLUETOOTH_SCAN
            if (!hasPermission(Manifest.permission.BLUETOOTH_ADVERTISE))
                needed += Manifest.permission.BLUETOOTH_ADVERTISE
        }

        // SMS
        if (!hasPermission(Manifest.permission.RECEIVE_SMS))
            needed += Manifest.permission.RECEIVE_SMS

        // Notification permission (Android 13+) — required to show foreground service notification
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
        // Delegate transport lifecycle to the foreground service so it survives backgrounding
        ContextCompat.startForegroundService(this, MirrorService.startIntent(this))
    }

    private fun hasPermission(perm: String) =
        ContextCompat.checkSelfPermission(this, perm) == PackageManager.PERMISSION_GRANTED
}
