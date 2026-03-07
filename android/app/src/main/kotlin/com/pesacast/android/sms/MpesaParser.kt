package com.pesacast.android.sms

import android.util.Log
import com.pesacast.android.model.MpesaTransaction
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.time.temporal.ChronoField
import java.util.Locale

private const val TAG = "MpesaParser"

/**
 * Parses M-PESA SMS bodies into [MpesaTransaction] objects.
 *
 * Handles the following transaction types:
 * - Received money (from person or business)
 * - Sent money (to person)
 * - Buy Goods / Till payment
 * - Paybill payment
 * - Cash withdrawal from agent
 * - Airtime purchase
 */
object MpesaParser {

    private const val AMOUNT = """([\d,]+\.\d{2})"""
    private const val REF    = """([A-Z0-9]{10,12})"""
    private const val DATE   = """(\d{1,2}/\d{1,2}/\d{2,4})"""
    private const val TIME_  = """(\d{1,2}:\d{2} [AP]M)"""
    private const val BAL    = """New M-PESA balance is Ksh$AMOUNT"""
    // Separator before the balance line: Safaricom uses either spaces ("PM  New") or
    // a period ("PM.New") depending on message variant. [.\s]+ covers both.
    private const val SEP    = """[.\s]+"""
    // "Confirmed.You" (no space) and "Confirmed. You" (one space) are both observed.
    // \s* inside CONFIRMED + no leading space in the caller handles both.
    private const val CONFIRMED = """Confirmed\.\s*"""

    // ── Pattern 1: Received money from person ──
    // "UBS12312898S Confirmed.You have received Ksh15.00 from AMUSH 0712345678 on 27/2/26 at 12:12 PM  New M-PESA balance is Ksh369.16."
    private val RECEIVED = Regex(
        """$REF ${CONFIRMED}You have received Ksh$AMOUNT from (.+?) \d+ on $DATE at $TIME_$SEP$BAL""",
        RegexOption.IGNORE_CASE
    )

    // ── Pattern 2: Sent money to person ──
    // "RKA12345XY Confirmed.Ksh500.00 sent to JOHN DOE 0712345678 on 1/1/24 at 10:55 AM  New M-PESA balance is Ksh...  Transaction cost, Ksh7.00."
    private val SENT = Regex(
        """$REF ${CONFIRMED}Ksh$AMOUNT sent to (.+?) \d+ on $DATE at $TIME_$SEP$BAL(?:[.\s]*Transaction cost,\s*Ksh$AMOUNT)?""",
        RegexOption.IGNORE_CASE
    )

    // ── Pattern 3: Buy Goods / Till ──
    // "RKA12345XY Confirmed.Ksh200.00 paid to SHOPNAME on 1/1/24 at 10:55 AM  New M-PESA balance is Ksh...  Transaction cost, Ksh7.00."
    private val BUY_GOODS = Regex(
        """$REF ${CONFIRMED}Ksh$AMOUNT paid to (.+?) on $DATE at $TIME_$SEP$BAL(?:[.\s]*Transaction cost,\s*Ksh$AMOUNT)?""",
        RegexOption.IGNORE_CASE
    )

    // ── Pattern 4: Paybill ──
    // "RKA12345XY Confirmed.Ksh100.00 sent to PAYNAME for account ACC123 on 1/1/24 at 10:55 AM  New M-PESA balance is Ksh...  Transaction cost, Ksh7.00."
    private val PAYBILL = Regex(
        """$REF ${CONFIRMED}Ksh$AMOUNT sent to (.+?) for account .+? on $DATE at $TIME_$SEP$BAL(?:[.\s]*Transaction cost,\s*Ksh$AMOUNT)?""",
        RegexOption.IGNORE_CASE
    )

    // ── Pattern 5: Withdrawal from agent ──
    // "RKA12345XY Confirmed on 1/1/24 at 10:55 AM. Withdraw Ksh1,000.00 from 12345 - AGENT NAME.New M-PESA balance is Ksh..."
    private val WITHDRAW = Regex(
        """$REF Confirmed on $DATE at $TIME_\. Withdraw Ksh$AMOUNT from \d+ - (.+?)$SEP$BAL""",
        RegexOption.IGNORE_CASE
    )

    // ── Pattern 6: Airtime ──
    // "RKA12345XY Confirmed.Ksh50.00 of airtime for 0712345678 on 1/1/24 at 10:55 AM  New M-PESA balance is Ksh..."
    private val AIRTIME = Regex(
        """$REF ${CONFIRMED}Ksh$AMOUNT of airtime for (\d+) on $DATE at $TIME_$SEP$BAL""",
        RegexOption.IGNORE_CASE
    )

    // ── Pattern 7: Received from business (no phone number) ──
    // "RKA12345XY Confirmed.You have received Ksh300.00 from SOME BUSINESS on 1/1/24 at 10:55 AM  New M-PESA balance is Ksh..."
    private val RECEIVED_BIZ = Regex(
        """$REF ${CONFIRMED}You have received Ksh$AMOUNT from (.+?) on $DATE at $TIME_$SEP$BAL""",
        RegexOption.IGNORE_CASE
    )

    // ── Pattern 8: Fuliza M-PESA repayment ──
    // "SGV0I11MXK Confirmed. Ksh 0.59 from your M-PESA has been used to fully pay your outstanding Fuliza M-PESA. Available Fuliza M-PESA limit is Ksh 1500.00. M-PESA balance is Ksh999.41."
    // Note: no date/time in message; balance field uses "M-PESA balance" not "New M-PESA balance"; Ksh may have a space before the amount.
    private val FULIZA = Regex(
        """$REF ${CONFIRMED}Ksh\s*$AMOUNT from your M-PESA has been used to (?:fully|partially) pay your outstanding Fuliza M-PESA\..*?M-PESA balance is Ksh$AMOUNT""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
    )

    fun parse(smsBody: String): MpesaTransaction? {
        val body = smsBody.trim()

        RECEIVED.find(body)?.groupValues?.let { g ->
            return build(g[1], "received", g[2], g[3].trim(), g[4], g[5], g[6])
        }

        SENT.find(body)?.groupValues?.let { g ->
            // Groups: ref, amount, party, date, time, balance, [transaction_cost]
            return build(g[1], "sent", g[2], g[3].trim(), g[4], g[5], g[6], g[7].ifEmpty { null })
        }

        PAYBILL.find(body)?.groupValues?.let { g ->
            return build(g[1], "paid", g[2], g[3].trim(), g[4], g[5], g[6], g[7].ifEmpty { null })
        }

        BUY_GOODS.find(body)?.groupValues?.let { g ->
            return build(g[1], "paid", g[2], g[3].trim(), g[4], g[5], g[6], g[7].ifEmpty { null })
        }

        WITHDRAW.find(body)?.groupValues?.let { g ->
            // Groups: ref, date, time, amount, party, balance
            return build(g[1], "withdrawn", g[4], g[5].trim(), g[2], g[3], g[6])
        }

        AIRTIME.find(body)?.groupValues?.let { g ->
            return build(g[1], "airtime", g[2], "Airtime ${g[3]}", g[4], g[5], g[6])
        }

        RECEIVED_BIZ.find(body)?.groupValues?.let { g ->
            return build(g[1], "received", g[2], g[3].trim(), g[4], g[5], g[6])
        }

        FULIZA.find(body)?.groupValues?.let { g ->
            // Groups: ref, repayment_amount, balance (no date/time — use current instant)
            val amount  = g[2].replace(",", "").toDoubleOrNull() ?: return@let
            val balance = g[3].replace(",", "").toDoubleOrNull() ?: return@let
            return MpesaTransaction(
                direction = "fuliza",
                amount    = amount,
                from      = "Fuliza M-PESA",
                ref       = g[1],
                time      = java.time.Instant.now().toString(),
                balance   = balance
            )
        }

        Log.d(TAG, "No pattern matched SMS: ${body.take(200)}")
        return null
    }

    private fun build(
        ref: String,
        direction: String,
        rawAmount: String,
        party: String,
        date: String,
        time: String,
        rawBalance: String,
        rawTransactionCost: String? = null
    ): MpesaTransaction? {
        val amount          = rawAmount.replace(",", "").toDoubleOrNull()          ?: return null
        val balance         = rawBalance.replace(",", "").toDoubleOrNull()         ?: return null
        val transactionCost = rawTransactionCost?.replace(",", "")?.toDoubleOrNull()
        val iso             = parseDateTime(date, time)
        return MpesaTransaction(
            direction       = direction,
            amount          = amount,
            from            = party,
            ref             = ref,
            time            = iso,
            balance         = balance,
            transactionCost = transactionCost
        )
    }

    /**
     * Converts "1/1/24 10:55 AM" → ISO-8601 with Nairobi offset (+03:00).
     * Falls back to current instant on parse failure.
     */
    private fun parseDateTime(date: String, time: String): String {
        return try {
            val formatter = DateTimeFormatterBuilder()
                .appendPattern("d/M/")
                .appendValueReduced(ChronoField.YEAR, 2, 4, 2000)
                .appendPattern(" h:mm a")
                .toFormatter(Locale.US)
            val ldt = java.time.LocalDateTime.parse("$date $time", formatter)
            val zdt = ldt.atZone(java.time.ZoneId.of("Africa/Nairobi"))
            DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(zdt)
        } catch (e: Exception) {
            Log.w(TAG, "Date parse failed for '$date $time': ${e.message}")
            java.time.Instant.now().toString()
        }
    }
}
