package com.pesacast.android.model

import com.google.gson.annotations.SerializedName
import java.text.NumberFormat
import java.util.Locale

/** Shared service UUID — must match the macOS BluetoothServer SERVICE_UUID constant. */
const val SERVICE_UUID = "E7A12345-B09E-4B3C-83A6-00112233AABB"

data class MpesaTransaction(
    @SerializedName("type")      val type: String = "mpesa_txn",
    @SerializedName("direction") val direction: String,   // "received" | "sent" | "paid" | "withdrawn"
    @SerializedName("amount")    val amount: Double,
    @SerializedName("currency")  val currency: String = "KES",
    @SerializedName("from")      val from: String,        // sender name or merchant
    @SerializedName("ref")       val ref: String,
    @SerializedName("time")      val time: String,        // ISO-8601
    @SerializedName("balance")   val balance: Double
) {
    val isReceived: Boolean get() = direction == "received"

    val formattedAmount: String
        get() {
            val nf = NumberFormat.getNumberInstance(Locale.US).apply {
                minimumFractionDigits = 2
                maximumFractionDigits = 2
            }
            return "$currency ${nf.format(amount)}"
        }

    val formattedBalance: String
        get() {
            val nf = NumberFormat.getNumberInstance(Locale.US).apply {
                minimumFractionDigits = 2
                maximumFractionDigits = 2
            }
            return "$currency ${nf.format(balance)}"
        }

    companion object {
        fun makeTest() = MpesaTransaction(
            direction = "received",
            amount = 500.00,
            from = "Test Sender",
            ref = "TEST001XYZ",
            time = java.time.Instant.now().toString(),
            balance = 1000.00
        )
    }
}
