package com.pesacast.android.util

import com.pesacast.android.model.MpesaTransaction
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** In-memory store for recent transactions — backed by StateFlow for UI observation. */
object TransactionStore {

    private const val MAX_SIZE = 100

    private val _transactions = MutableStateFlow<List<MpesaTransaction>>(emptyList())
    val transactions: StateFlow<List<MpesaTransaction>> = _transactions.asStateFlow()

    fun add(txn: MpesaTransaction) {
        val updated = buildList {
            add(txn)
            addAll(_transactions.value)
        }.take(MAX_SIZE)
        _transactions.value = updated
    }
}
