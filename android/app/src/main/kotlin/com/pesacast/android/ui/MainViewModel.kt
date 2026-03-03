package com.pesacast.android.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.pesacast.android.model.MpesaTransaction
import com.pesacast.android.transport.TransportManager
import com.pesacast.android.transport.TransportState
import com.pesacast.android.util.PreferencesManager
import com.pesacast.android.util.TransactionStore
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

class MainViewModel(app: Application) : AndroidViewModel(app) {

    val prefs = PreferencesManager(app)
    val transport = TransportManager.getInstance(app)

    val bleState: StateFlow<TransportState> = transport.ble.state
        .stateIn(viewModelScope, SharingStarted.Eagerly, TransportState.Disconnected)

    val transactions: StateFlow<List<MpesaTransaction>> = TransactionStore.transactions
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    // MARK: - Lifecycle

    fun restartWithNewSettings() {
        transport.restartWithNewSettings()
    }

    // MARK: - Testing

    fun sendTest() {
        transport.sendTransaction(MpesaTransaction.makeTest())
    }
}
