package com.focuslauncher.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony

/**
 * Fires the moment a new SMS arrives. All this does now is parse it and, if it looks like a
 * debit, update the passive "last paid" reminder — no classification, no WorkManager, nothing
 * written to the expense tracker itself. That's fast enough to do directly here.
 */
class SmsReceivedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        if (!ExpenseRepository.isEnabled(context)) return
        val now = System.currentTimeMillis()
        for (message in Telephony.Sms.Intents.getMessagesFromIntent(intent) ?: return) {
            val parsed = SmsTransactionParser.parse(message.messageBody ?: continue) ?: continue
            LastPaymentStore.recordIfNewer(context, parsed, now)
        }
    }
}
