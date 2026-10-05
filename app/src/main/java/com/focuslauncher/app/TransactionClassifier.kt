package com.focuslauncher.app

import android.content.Context
import org.json.JSONObject

/**
 * Retags one already-existing transaction from the user's own free-text note (the dashboard's
 * per-row "what was this actually?" correction). Every transaction is created manually now, so
 * this is the only remaining classification path — there's no automatic guess to fall back on.
 * The model's output is always a draft, re-validated against the real enums regardless of what
 * the grammar already guarantees.
 */
object TransactionClassifier {
    data class Guess(val kind: TransactionKind, val category: ExpenseCategory?)

    private const val MAX_TOKENS = 30

    /** Null means "couldn't classify right now" (model not downloaded, failed to load, bad
     * output) — callers should leave the transaction untouched. */
    fun classify(context: Context, transaction: ExpenseRepository.Transaction, note: String): Guess? {
        if (!LlamaModelLoader.ensureLoaded(context)) return null
        val grammar = LlamaModelLoader.readGrammar(context, "classification_schema.gbnf")
        val raw = LlamaEngine.generate(buildPrompt(transaction, note), MAX_TOKENS, grammar) ?: return null
        return parseAndValidate(raw)
    }

    private fun buildPrompt(transaction: ExpenseRepository.Transaction, note: String): String {
        return """
            You retag one transaction in a personal expense tracker based on the user's own
            words. Respond with ONLY a single JSON object matching the required schema — no
            other text.

            Valid kinds: EXPENSE (ordinary spend), INCOME (salary, refund, allowance).
            Valid categories (EXPENSE only, else null): FOOD, TRANSPORT, ESSENTIALS, EXTRAS,
            SUBSCRIPTION. Coffee, meals, snacks, groceries are FOOD, not EXTRAS.

            Transaction: ${transaction.counterpartName}, ₹${"%.0f".format(transaction.amount)},
            currently tagged [${transactionTag(transaction.kind, transaction.category)}].

            The user says what it actually was: "$note"
        """.trimIndent()
    }

    private fun parseAndValidate(raw: String): Guess? {
        return try {
            val jsonText = raw.substring(raw.indexOf('{'), raw.lastIndexOf('}') + 1)
            val obj = JSONObject(jsonText)
            val kind = TransactionKind.fromStorage(obj.optString("kind").takeIf { it.isNotBlank() })
            // The prompt tells the model category only applies to EXPENSE, but a small model
            // doesn't always follow that — enforced here rather than trusted, same as every
            // other field.
            val category = if (kind != TransactionKind.EXPENSE) {
                null
            } else {
                obj.optString("category")
                    .takeIf { it.isNotBlank() && !it.equals("null", ignoreCase = true) }
                    ?.let { ExpenseCategory.fromStorage(it) }
            }
            Guess(kind, category)
        } catch (e: Exception) {
            null
        }
    }
}
