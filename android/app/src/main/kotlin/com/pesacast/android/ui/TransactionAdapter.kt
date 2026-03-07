package com.pesacast.android.ui

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.pesacast.android.R
import com.pesacast.android.model.MpesaTransaction
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val TIME_FMT = DateTimeFormatter.ofPattern("MMM d 'at' hh:mm a")
    .withZone(ZoneId.of("Africa/Nairobi"))

class TransactionAdapter :
    ListAdapter<MpesaTransaction, TransactionAdapter.VH>(DIFF) {

    inner class VH(view: View) : RecyclerView.ViewHolder(view) {
        val divider:   View     = view.findViewById(R.id.itemDivider)
        val party:     TextView = view.findViewById(R.id.txnParty)
        val direction: TextView = view.findViewById(R.id.txnDirection)
        val amount:    TextView = view.findViewById(R.id.txnAmount)
        val ref:       TextView = view.findViewById(R.id.txnRef)
        val time:      TextView = view.findViewById(R.id.txnTime)
        val balance:   TextView = view.findViewById(R.id.txnBalance)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_transaction, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val txn = getItem(position)

        // Hide divider on first item to avoid a top border on the list
        holder.divider.visibility = if (position == 0) View.GONE else View.VISIBLE

        holder.party.text = txn.from

        val dirLabel = when (txn.direction) {
            "received"  -> "Received"
            "sent"      -> "Sent"
            "paid"      -> "Paid"
            "withdrawn" -> "Withdrawn"
            "airtime"   -> "Airtime"
            "fuliza"    -> "Fuliza Repayment"
            else        -> txn.direction.replaceFirstChar { it.uppercase() }
        }
        holder.direction.text = dirLabel

        // Badge: green for received, amber for fuliza repayment, red for all other outgoing
        when {
            txn.isReceived -> {
                holder.direction.setTextColor(Color.parseColor("#4ADE80"))
                holder.direction.setBackgroundResource(R.drawable.bg_badge_received)
                holder.amount.text = "+${txn.formattedAmount}"
                holder.amount.setTextColor(Color.parseColor("#4ADE80"))
            }
            txn.direction == "fuliza" -> {
                holder.direction.setTextColor(Color.parseColor("#FCD34D"))
                holder.direction.setBackgroundResource(R.drawable.bg_badge_sent)
                holder.amount.text = "−${txn.formattedAmount}"
                holder.amount.setTextColor(Color.parseColor("#FCD34D"))
            }
            else -> {
                holder.direction.setTextColor(Color.parseColor("#FCA5A5"))
                holder.direction.setBackgroundResource(R.drawable.bg_badge_sent)
                holder.amount.text = "−${txn.formattedAmount}"
                holder.amount.setTextColor(Color.parseColor("#FCA5A5"))
            }
        }

        holder.ref.text = "Ref: ${txn.ref}"
        holder.balance.text = "Bal: ${txn.formattedBalance}"
        holder.time.text = try {
            TIME_FMT.format(Instant.parse(txn.time))
        } catch (_: Exception) { txn.time }
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<MpesaTransaction>() {
            override fun areItemsTheSame(a: MpesaTransaction, b: MpesaTransaction) =
                a.ref == b.ref && a.time == b.time
            override fun areContentsTheSame(a: MpesaTransaction, b: MpesaTransaction) = a == b
        }
    }
}
