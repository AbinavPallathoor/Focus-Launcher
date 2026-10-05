package com.focuslauncher.app

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.database.sqlite.SQLiteDatabase
import androidx.core.content.ContextCompat
import java.util.Calendar
import java.util.concurrent.TimeUnit

/**
 * A manually-logged personal expense tracker: every transaction is entered by the user, either
 * through the home screen's quick-add prompt or the dashboard's free-text command bar, both
 * interpreted by an on-device LLM into amount/kind/category. Nothing is read automatically from
 * SMS into this table — the separate, purely informational "last paid" reminder (see
 * [LastPaymentStore]) is the only thing SMS still feeds.
 */
object ExpenseRepository {

    data class Transaction(
        val id: Long,
        val amount: Double,
        val counterpartName: String,
        val direction: TransactionDirection,
        val timestamp: Long,
        val kind: TransactionKind,
        val category: ExpenseCategory?,
        val resolvedBy: ResolvedBy,
    )

    data class DayTotal(val dayStartMillis: Long, val total: Double)

    private const val PREFS = "expense_tracker"
    private const val KEY_ENABLED = "enabled"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    fun hasSmsPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_SMS) ==
            PackageManager.PERMISSION_GRANTED

    fun hasReceiveSmsPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECEIVE_SMS) ==
            PackageManager.PERMISSION_GRANTED

    // A manually-described ADD's own "kind" guess from the model is reliable; deriving direction
    // straight from kind (rather than asking the model for it directly) sidesteps a field that
    // proved unreliable in testing — greedy decoding kept producing CREDIT for plainly-spent
    // money ("add 100 for coffee") even after the prompt spelled out the rule.
    private fun TransactionKind.canonicalDirection(): TransactionDirection = when (this) {
        TransactionKind.EXPENSE, TransactionKind.UNKNOWN -> TransactionDirection.DEBIT
        TransactionKind.INCOME -> TransactionDirection.CREDIT
    }

    private fun insertManualTransaction(
        db: SQLiteDatabase, rawNote: String, counterpartName: String,
        amount: Double, kind: TransactionKind, category: ExpenseCategory?,
    ) {
        val values = ContentValues().apply {
            put("sms_id", "manual-${System.currentTimeMillis()}-${(0..999999).random()}")
            put("amount", amount)
            put("counterpart_name", counterpartName.trim().uppercase())
            put("direction", kind.canonicalDirection().name)
            put("raw_body", rawNote)
            put("timestamp", System.currentTimeMillis())
            put("kind", kind.name)
            put("category", category?.name)
            put("confidence", 1.0)
            put("resolved_by", ResolvedBy.USER.name)
        }
        db.insert("transactions", null, values)
    }

    /**
     * The home screen's quick-add prompt: one free-text description of a purchase, interpreted
     * by the on-device model and inserted directly — no follow-up, no confirmation step. Silently
     * does nothing if the model can't interpret it as an addition. Blocking (real LLM inference)
     * — callers must invoke this off the main thread.
     */
    fun addTransactionFromDescription(context: Context, description: String) {
        val db = ExpenseDbHelper(context).writableDatabase
        try {
            val result = DashboardCommandInterpreter.interpret(context, description, emptyList()) ?: return
            if (result.action != DashboardCommandInterpreter.Action.ADD) return
            insertManualTransaction(db, description, result.counterpartName, result.amount, result.kind, result.category)
        } finally {
            db.close()
        }
    }

    private fun getTransactionById(db: SQLiteDatabase, transactionId: Long): Transaction? {
        db.rawQuery(
            """SELECT id, amount, counterpart_name, direction, timestamp, kind, category, resolved_by
               FROM transactions WHERE id = ?""",
            arrayOf(transactionId.toString())
        ).use { cursor ->
            if (!cursor.moveToFirst()) return null
            return Transaction(
                id = cursor.getLong(0),
                amount = cursor.getDouble(1),
                counterpartName = cursor.getString(2),
                direction = TransactionDirection.valueOf(cursor.getString(3)),
                timestamp = cursor.getLong(4),
                kind = TransactionKind.fromStorage(cursor.getString(5)),
                category = ExpenseCategory.fromStorage(cursor.getString(6)),
                resolvedBy = ResolvedBy.fromStorage(cursor.getString(7)),
            )
        }
    }

    /**
     * The user's free-text note on why a transaction's tag was wrong (e.g. "this was actually a
     * gift, not a subscription") — re-runs the classifier trusting the note, applies the
     * corrected guess. Blocking (real LLM inference) — callers must invoke this off the main
     * thread.
     */
    fun correctTransaction(context: Context, transactionId: Long, note: String) {
        val db = ExpenseDbHelper(context).writableDatabase
        try {
            val transaction = getTransactionById(db, transactionId) ?: return
            val guess = TransactionClassifier.classify(context, transaction, note) ?: return
            db.execSQL(
                "UPDATE transactions SET kind = ?, category = ?, confidence = 1.0, resolved_by = ? WHERE id = ?",
                arrayOf(guess.kind.name, guess.category?.name, ResolvedBy.USER.name, transactionId.toString())
            )
        } finally {
            db.close()
        }
    }

    /** Direct edit, no model involved — the user picked an exact new name from the long-press
     * menu rather than describing a correction in free text. */
    fun renameTransaction(context: Context, transactionId: Long, newName: String) {
        val name = newName.trim().uppercase()
        if (name.isEmpty()) return
        val db = ExpenseDbHelper(context).writableDatabase
        try {
            db.execSQL("UPDATE transactions SET counterpart_name = ? WHERE id = ?", arrayOf(name, transactionId.toString()))
        } finally {
            db.close()
        }
    }

    /** Direct edit, no model involved — an exact replacement amount from the long-press menu. */
    fun modifyTransactionAmount(context: Context, transactionId: Long, newAmount: Double) {
        if (newAmount <= 0.0) return
        val db = ExpenseDbHelper(context).writableDatabase
        try {
            db.execSQL("UPDATE transactions SET amount = ? WHERE id = ?", arrayOf(newAmount, transactionId.toString()))
        } finally {
            db.close()
        }
    }

    fun deleteTransaction(context: Context, transactionId: Long) {
        val db = ExpenseDbHelper(context).writableDatabase
        try {
            db.delete("transactions", "id = ?", arrayOf(transactionId.toString()))
        } finally {
            db.close()
        }
    }

    /**
     * The dashboard's free-text command bar — one instruction that can add a transaction (e.g.
     * cash), delete an existing one, or retag one, all via the same on-device model. Interprets
     * against the dashboard's own most-recent transactions so "remove the bookstore one" can
     * resolve to a real row. A command the model can't map to anything actionable is silently a
     * no-op. Blocking (LLM inference) — callers must invoke this off the main thread.
     */
    fun runDashboardCommand(context: Context, command: String) {
        val db = ExpenseDbHelper(context).writableDatabase
        try {
            val recent = mutableListOf<Transaction>()
            db.rawQuery(
                """SELECT id, amount, counterpart_name, direction, timestamp, kind, category, resolved_by
                   FROM transactions ORDER BY timestamp DESC LIMIT 20""",
                null
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    recent.add(
                        Transaction(
                            id = cursor.getLong(0),
                            amount = cursor.getDouble(1),
                            counterpartName = cursor.getString(2),
                            direction = TransactionDirection.valueOf(cursor.getString(3)),
                            timestamp = cursor.getLong(4),
                            kind = TransactionKind.fromStorage(cursor.getString(5)),
                            category = ExpenseCategory.fromStorage(cursor.getString(6)),
                            resolvedBy = ResolvedBy.fromStorage(cursor.getString(7)),
                        )
                    )
                }
            }
            val result = DashboardCommandInterpreter.interpret(context, command, recent) ?: return
            when (result.action) {
                DashboardCommandInterpreter.Action.ADD -> {
                    insertManualTransaction(
                        db, "Added manually via dashboard command: \"$command\"",
                        result.counterpartName, result.amount, result.kind, result.category,
                    )
                }
                DashboardCommandInterpreter.Action.REMOVE -> {
                    val target = recent.getOrNull(result.targetIndex - 1) ?: return
                    db.delete("transactions", "id = ?", arrayOf(target.id.toString()))
                }
                DashboardCommandInterpreter.Action.MODIFY -> {
                    val target = recent.getOrNull(result.targetIndex - 1) ?: return
                    db.execSQL(
                        "UPDATE transactions SET kind = ?, category = ?, confidence = 1.0, resolved_by = ? WHERE id = ?",
                        arrayOf(result.kind.name, result.category?.name, ResolvedBy.USER.name, target.id.toString())
                    )
                }
                DashboardCommandInterpreter.Action.NONE -> Unit
            }
        } finally {
            db.close()
        }
    }

    private fun startOfToday(): Long {
        val cal = Calendar.getInstance()
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    private fun startOfMonth(): Long {
        val cal = Calendar.getInstance()
        cal.set(Calendar.DAY_OF_MONTH, 1)
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    private const val SPEND_KINDS_CLAUSE = "direction = 'DEBIT' AND kind = 'EXPENSE'"

    fun getTodayTotal(context: Context): Double {
        val db = ExpenseDbHelper(context).readableDatabase
        try {
            val dayStart = startOfToday()
            val dayEnd = dayStart + TimeUnit.DAYS.toMillis(1)
            db.rawQuery(
                "SELECT COALESCE(SUM(amount), 0) FROM transactions WHERE $SPEND_KINDS_CLAUSE AND timestamp >= ? AND timestamp < ?",
                arrayOf(dayStart.toString(), dayEnd.toString())
            ).use { cursor -> cursor.moveToFirst(); return cursor.getDouble(0) }
        } finally {
            db.close()
        }
    }

    fun getMonthTotal(context: Context): Double {
        val db = ExpenseDbHelper(context).readableDatabase
        try {
            db.rawQuery(
                "SELECT COALESCE(SUM(amount), 0) FROM transactions WHERE $SPEND_KINDS_CLAUSE AND timestamp >= ?",
                arrayOf(startOfMonth().toString())
            ).use { cursor -> cursor.moveToFirst(); return cursor.getDouble(0) }
        } finally {
            db.close()
        }
    }

    /** One total per day for the last [days] days (oldest first), for the spending line graph. */
    fun getDailyTotals(context: Context, days: Int): List<DayTotal> {
        val db = ExpenseDbHelper(context).readableDatabase
        try {
            val todayStart = startOfToday()
            val oneDay = TimeUnit.DAYS.toMillis(1)
            val results = mutableListOf<DayTotal>()
            for (i in (days - 1) downTo 0) {
                val dayStart = todayStart - i * oneDay
                val dayEnd = dayStart + oneDay
                db.rawQuery(
                    "SELECT COALESCE(SUM(amount), 0) FROM transactions WHERE $SPEND_KINDS_CLAUSE AND timestamp >= ? AND timestamp < ?",
                    arrayOf(dayStart.toString(), dayEnd.toString())
                ).use { cursor ->
                    cursor.moveToFirst()
                    results.add(DayTotal(dayStart, cursor.getDouble(0)))
                }
            }
            return results
        } finally {
            db.close()
        }
    }

    fun getCategoryTotalsMonth(context: Context): Map<ExpenseCategory, Double> {
        val db = ExpenseDbHelper(context).readableDatabase
        try {
            val totals = linkedMapOf<ExpenseCategory, Double>()
            ExpenseCategory.entries.forEach { totals[it] = 0.0 }
            db.rawQuery(
                """SELECT category, SUM(amount) FROM transactions
                   WHERE direction = 'DEBIT' AND kind = 'EXPENSE' AND category IS NOT NULL AND timestamp >= ?
                   GROUP BY category""",
                arrayOf(startOfMonth().toString())
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    ExpenseCategory.fromStorage(cursor.getString(0))?.let { totals[it] = cursor.getDouble(1) }
                }
            }
            return totals
        } finally {
            db.close()
        }
    }

    fun getRecentTransactions(context: Context, limit: Int = 20): List<Transaction> {
        val db = ExpenseDbHelper(context).readableDatabase
        try {
            val result = mutableListOf<Transaction>()
            db.rawQuery(
                """SELECT id, amount, counterpart_name, direction, timestamp, kind, category, resolved_by
                   FROM transactions ORDER BY timestamp DESC LIMIT ?""",
                arrayOf(limit.toString())
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    result.add(
                        Transaction(
                            id = cursor.getLong(0),
                            amount = cursor.getDouble(1),
                            counterpartName = cursor.getString(2),
                            direction = TransactionDirection.valueOf(cursor.getString(3)),
                            timestamp = cursor.getLong(4),
                            kind = TransactionKind.fromStorage(cursor.getString(5)),
                            category = ExpenseCategory.fromStorage(cursor.getString(6)),
                            resolvedBy = ResolvedBy.fromStorage(cursor.getString(7)),
                        )
                    )
                }
            }
            return result
        } finally {
            db.close()
        }
    }
}
