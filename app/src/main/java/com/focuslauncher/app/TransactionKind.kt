package com.focuslauncher.app

/**
 * What a transaction actually represents, beyond its raw debit/credit direction. A plain
 * category (Food, Transport, ...) only applies to EXPENSE.
 */
enum class TransactionKind(val label: String) {
    EXPENSE("Expense"),
    INCOME("Income"),
    UNKNOWN("Unclassified");

    companion object {
        fun fromStorage(value: String?): TransactionKind =
            entries.firstOrNull { it.name == value } ?: UNKNOWN
    }
}

/** Who/what resolved a transaction's kind/category. */
enum class ResolvedBy {
    USER, UNKNOWN;

    companion object {
        fun fromStorage(value: String?): ResolvedBy =
            entries.firstOrNull { it.name == value } ?: UNKNOWN
    }
}

/** The single short tag shown for a transaction: the category for an EXPENSE, the kind for
 * anything else. */
fun transactionTag(kind: TransactionKind, category: ExpenseCategory?): String = when (kind) {
    TransactionKind.EXPENSE -> category?.label?.lowercase() ?: "expense"
    TransactionKind.INCOME -> "income"
    TransactionKind.UNKNOWN -> "unsorted"
}
