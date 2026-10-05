package com.focuslauncher.app

import android.content.Context
import android.net.Uri

/**
 * A purely passive reminder of the most recent bank/UPI debit SMS — not a tracked transaction,
 * just a "you last paid ₹X" hint shown below the home screen's quick-add prompt so nothing gets
 * forgotten. Nothing here ever writes to the expense tracker; the user decides what's worth
 * logging via the quick-add prompt itself.
 */
object LastPaymentStore {

    data class LastPayment(val amount: Double, val counterpartName: String, val timestamp: Long)

    private const val PREFS = "last_payment"
    private const val KEY_AMOUNT = "amount"
    private const val KEY_COUNTERPART = "counterpart"
    private const val KEY_TIMESTAMP = "timestamp"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun get(context: Context): LastPayment? {
        val p = prefs(context)
        if (!p.contains(KEY_TIMESTAMP)) return null
        return LastPayment(
            amount = p.getFloat(KEY_AMOUNT, 0f).toDouble(),
            counterpartName = p.getString(KEY_COUNTERPART, null) ?: return null,
            timestamp = p.getLong(KEY_TIMESTAMP, 0L),
        )
    }

    /** Only replaces what's stored if [timestamp] is actually newer — a receiver callback and a
     * resume-time inbox scan can race, and the older one losing matters here. */
    fun recordIfNewer(context: Context, parsed: ParsedTransaction, timestamp: Long) {
        if (parsed.direction != TransactionDirection.DEBIT) return
        val p = prefs(context)
        if (timestamp <= p.getLong(KEY_TIMESTAMP, 0L)) return
        p.edit()
            .putFloat(KEY_AMOUNT, parsed.amount.toFloat())
            .putString(KEY_COUNTERPART, parsed.counterpartName)
            .putLong(KEY_TIMESTAMP, timestamp)
            .apply()
    }

    /** Looks at the single most recent inbox message that parses as a debit and records it if
     * newer than what's already stored — covers the case where the app opens without having
     * been running to catch the live broadcast (first install, permission just granted). */
    fun refreshFromInbox(context: Context) {
        if (!ExpenseRepository.hasSmsPermission(context)) return
        val uri = Uri.parse("content://sms/inbox")
        context.contentResolver.query(
            uri, arrayOf("body", "date"), null, null, "date DESC"
        )?.use { cursor ->
            val bodyIdx = cursor.getColumnIndexOrThrow("body")
            val dateIdx = cursor.getColumnIndexOrThrow("date")
            while (cursor.moveToNext()) {
                val body = cursor.getString(bodyIdx) ?: continue
                val date = cursor.getLong(dateIdx)
                val parsed = SmsTransactionParser.parse(body)
                if (parsed != null && parsed.direction == TransactionDirection.DEBIT) {
                    recordIfNewer(context, parsed, date)
                    return
                }
            }
        }
    }
}
