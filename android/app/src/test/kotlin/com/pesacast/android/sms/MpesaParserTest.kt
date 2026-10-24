package com.pesacast.android.sms

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Sample bodies for `received` and `fuliza` are copied from the examples in [MpesaParser]; the
 * others follow the formats documented there. Real Safaricom wording changes over time, so when a
 * live message fails to parse, add it here first.
 */
class MpesaParserTest {

    private fun parse(body: String) = MpesaParser.parse(body)

    // ── received ──

    @Test
    fun `received from person`() {
        val t = parse(
            "UBS12312898S Confirmed.You have received Ksh15.00 from AMUSH 0712345678 on 27/2/26 at 12:12 PM  New M-PESA balance is Ksh369.16."
        )!!
        assertEquals("received", t.direction)
        assertEquals(15.00, t.amount, 0.0)
        assertEquals("AMUSH", t.from)
        assertEquals("UBS12312898S", t.ref)
        assertEquals(369.16, t.balance, 0.0)
        assertNull(t.transactionCost)
        assertEquals("2026-02-27T12:12:00+03:00", t.time)
    }

    @Test
    fun `received from business without phone number`() {
        val t = parse(
            "RKA12345XY Confirmed.You have received Ksh300.00 from SOME BUSINESS on 1/1/24 at 10:55 AM  New M-PESA balance is Ksh1,000.50."
        )!!
        assertEquals("received", t.direction)
        assertEquals(300.00, t.amount, 0.0)
        assertEquals("SOME BUSINESS", t.from)
        assertEquals(1000.50, t.balance, 0.0)
    }

    @Test
    fun `received with period instead of spaces before balance`() {
        val t = parse(
            "UBS12312898S Confirmed.You have received Ksh15.00 from AMUSH 0712345678 on 27/2/26 at 12:12 PM.New M-PESA balance is Ksh369.16."
        )!!
        assertEquals(369.16, t.balance, 0.0)
    }

    @Test
    fun `received with space after Confirmed`() {
        val t = parse(
            "UBS12312898S Confirmed. You have received Ksh15.00 from AMUSH 0712345678 on 27/2/26 at 12:12 PM  New M-PESA balance is Ksh369.16."
        )
        assertNotNull(t)
    }

    // ── sent ──

    @Test
    fun `sent to person with transaction cost`() {
        val t = parse(
            "RKA12345XY Confirmed.Ksh500.00 sent to JOHN DOE 0712345678 on 1/1/24 at 10:55 AM  New M-PESA balance is Ksh1,200.00.  Transaction cost, Ksh7.00."
        )!!
        assertEquals("sent", t.direction)
        assertEquals(500.00, t.amount, 0.0)
        assertEquals("JOHN DOE", t.from)
        assertEquals(1200.00, t.balance, 0.0)
        assertEquals(7.00, t.transactionCost!!, 0.0)
    }

    @Test
    fun `thousands separators are removed from amounts`() {
        val t = parse(
            "RKA12345XY Confirmed.Ksh12,500.00 sent to JANE DOE 0712345678 on 1/1/24 at 10:55 AM  New M-PESA balance is Ksh1,234,567.89.  Transaction cost, Ksh1,000.00."
        )!!
        assertEquals(12500.00, t.amount, 0.0)
        assertEquals(1234567.89, t.balance, 0.0)
        assertEquals(1000.00, t.transactionCost!!, 0.0)
    }

    // ── paid (till and paybill) ──

    @Test
    fun `buy goods payment`() {
        val t = parse(
            "RKA12345XY Confirmed.Ksh200.00 paid to SHOPNAME on 1/1/24 at 10:55 AM  New M-PESA balance is Ksh800.00.  Transaction cost, Ksh0.00."
        )!!
        assertEquals("paid", t.direction)
        assertEquals("SHOPNAME", t.from)
        assertEquals(200.00, t.amount, 0.0)
        assertEquals(0.0, t.transactionCost!!, 0.0)
    }

    @Test
    fun `paybill payment is paid not sent`() {
        val t = parse(
            "RKA12345XY Confirmed.Ksh100.00 sent to PAYNAME for account ACC ABC on 1/1/24 at 10:55 AM  New M-PESA balance is Ksh900.00.  Transaction cost, Ksh7.00."
        )!!
        assertEquals("paid", t.direction)
        assertEquals("PAYNAME", t.from)
    }

    @Test
    fun `paybill with numeric account number is still paid`() {
        // Regression guard: the SENT pattern ("sent to <name> <digits> on") must not swallow
        // "sent to PAYNAME for account ACC123 on".
        val t = parse(
            "RKA12345XY Confirmed.Ksh100.00 sent to PAYNAME for account ACC123 on 1/1/24 at 10:55 AM  New M-PESA balance is Ksh900.00.  Transaction cost, Ksh7.00."
        )!!
        assertEquals("paid", t.direction)
        assertEquals("PAYNAME", t.from)
    }

    @Test
    fun `paybill with purely numeric account is still paid`() {
        val t = parse(
            "RKA12345XY Confirmed.Ksh100.00 sent to KPLC PREPAID for account 14212345678 on 1/1/24 at 10:55 AM  New M-PESA balance is Ksh900.00.  Transaction cost, Ksh0.00."
        )!!
        assertEquals("paid", t.direction)
        assertEquals("KPLC PREPAID", t.from)
    }

    // ── withdraw / airtime / fuliza ──

    @Test
    fun `withdrawal from agent`() {
        val t = parse(
            "RKA12345XY Confirmed on 1/1/24 at 10:55 AM. Withdraw Ksh1,000.00 from 12345 - AGENT NAME.New M-PESA balance is Ksh500.00."
        )!!
        assertEquals("withdrawn", t.direction)
        assertEquals(1000.00, t.amount, 0.0)
        assertEquals("AGENT NAME", t.from)
        assertEquals(500.00, t.balance, 0.0)
    }

    @Test
    fun `airtime purchase`() {
        val t = parse(
            "RKA12345XY Confirmed.Ksh50.00 of airtime for 0712345678 on 1/1/24 at 10:55 AM  New M-PESA balance is Ksh450.00."
        )!!
        assertEquals("airtime", t.direction)
        assertEquals(50.00, t.amount, 0.0)
        assertEquals("Airtime 0712345678", t.from)
    }

    @Test
    fun `fuliza repayment`() {
        val t = parse(
            "SGV0I11MXK Confirmed. Ksh 0.59 from your M-PESA has been used to fully pay your outstanding Fuliza M-PESA. Available Fuliza M-PESA limit is Ksh 1500.00. M-PESA balance is Ksh999.41."
        )!!
        assertEquals("fuliza", t.direction)
        assertEquals(0.59, t.amount, 0.0)
        assertEquals(999.41, t.balance, 0.0)
        assertEquals("Fuliza M-PESA", t.from)
        assertEquals("SGV0I11MXK", t.ref)
    }

    @Test
    fun `fuliza partial repayment`() {
        val t = parse(
            "SGV0I11MXK Confirmed. Ksh 10.00 from your M-PESA has been used to partially pay your outstanding Fuliza M-PESA. Available Fuliza M-PESA limit is Ksh 500.00. M-PESA balance is Ksh0.00."
        )
        assertEquals("fuliza", t!!.direction)
    }

    // ── dates and times ──

    @Test
    fun `afternoon times use 24 hour offset in nairobi`() {
        val t = parse(
            "RKA12345XY Confirmed.You have received Ksh1.00 from X Y on 5/11/25 at 11:59 PM  New M-PESA balance is Ksh1.00."
        )!!
        assertEquals("2025-11-05T23:59:00+03:00", t.time)
    }

    @Test
    fun `midnight and noon`() {
        val midnight = parse(
            "RKA12345XY Confirmed.You have received Ksh1.00 from X Y on 5/11/25 at 12:00 AM  New M-PESA balance is Ksh1.00."
        )!!
        val noon = parse(
            "RKA12345XY Confirmed.You have received Ksh1.00 from X Y on 5/11/25 at 12:00 PM  New M-PESA balance is Ksh1.00."
        )!!
        assertEquals("2025-11-05T00:00:00+03:00", midnight.time)
        assertEquals("2025-11-05T12:00:00+03:00", noon.time)
    }

    @Test
    fun `four digit year is accepted`() {
        val t = parse(
            "RKA12345XY Confirmed.You have received Ksh1.00 from X Y on 5/11/2025 at 9:05 AM  New M-PESA balance is Ksh1.00."
        )!!
        assertEquals("2025-11-05T09:05:00+03:00", t.time)
    }

    // ── robustness ──

    @Test
    fun `surrounding whitespace is ignored`() {
        val t = parse(
            "\n  UBS12312898S Confirmed.You have received Ksh15.00 from AMUSH 0712345678 on 27/2/26 at 12:12 PM  New M-PESA balance is Ksh369.16.  \n"
        )
        assertNotNull(t)
    }

    @Test
    fun `matching is case insensitive`() {
        val t = parse(
            "ubs12312898s confirmed.you have received ksh15.00 from AMUSH 0712345678 on 27/2/26 at 12:12 pm  new m-pesa balance is ksh369.16."
        )
        assertNotNull(t)
    }

    @Test
    fun `unrelated messages return null`() {
        assertNull(parse("Hi, are we still meeting at 5?"))
        assertNull(parse(""))
        assertNull(parse("Your Safaricom data bundle expires tomorrow."))
    }

    @Test
    fun `promotional or otp messages that mention mpesa return null`() {
        assertNull(parse("Dial *334# to access M-PESA services. Enjoy Fuliza M-PESA today!"))
        assertNull(parse("Your M-PESA PIN was changed successfully."))
    }
}
