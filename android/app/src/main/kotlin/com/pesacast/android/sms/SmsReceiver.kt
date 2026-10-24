package com.pesacast.android.sms

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.util.Log
import com.google.gson.Gson
import com.pesacast.android.transport.TransportManager
import com.pesacast.android.util.PreferencesManager
import com.pesacast.android.util.TransactionStore

private const val TAG = "SmsReceiver"
private const val MPESA_SENDER = "MPESA"

class SmsReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return

        val prefs = PreferencesManager(context)
        val transport = TransportManager.getInstance(context)
        val mirror = prefs.mirroringEnabled
        val webhook = transport.webhookSettings.enabled
        if (!mirror && !webhook) {
            Log.d(TAG, "Mirroring and webhook disabled — ignoring SMS")
            return
        }

        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
        if (messages.isNullOrEmpty()) return

        // Group multi-part SMS by display originating address, then join bodies
        val grouped = messages.groupBy { it.displayOriginatingAddress }

        for ((sender, parts) in grouped) {
            if (!sender.equals(MPESA_SENDER, ignoreCase = true)) continue

            val fullBody = parts.joinToString("") { it.displayMessageBody }
            Log.d(TAG, "M-PESA SMS received (${fullBody.length} chars)")

            val txn = MpesaParser.parse(fullBody) ?: continue

            TransactionStore.add(txn)
            if (mirror) transport.sendTransaction(txn)
            // Persisted before this returns; the sender delivers it from the service process.
            if (webhook) transport.webhook.enqueue(Gson().toJson(txn))
        }
    }
}
