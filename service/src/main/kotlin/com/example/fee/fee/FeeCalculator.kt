package com.example.fee.fee

import com.example.fee.domain.Transaction
import com.example.fee.domain.TransactionType
import org.springframework.stereotype.Component

/**
 * Prices a transaction with the one [FeeRule] of its type. The rules are indexed once, when the bean is built, and
 * the index is checked there: a duplicate rule, a missing rule, or a rule for [TransactionType.UNKNOWN] fails startup,
 * so a new type can't ship without its rule.
 *
 * Pure: the workflow calls [calculate] inside CALCULATE's journaled step, so the quote is journaled.
 */
@Component
class FeeCalculator(rules: List<FeeRule>) {

    private val rulesByType: Map<TransactionType, FeeRule> = index(rules)

    /** Whether a rule prices [type]; `TransactionTypeValidator` rejects every type this returns `false` for. */
    fun supports(type: TransactionType): Boolean = type in rulesByType

    /**
     * The fee of [transaction], from the rule of its type. A type without a rule is a bug, since validation rejects it
     * first, so it throws [IllegalArgumentException] instead of returning a business result.
     */
    fun calculate(transaction: Transaction): FeeQuote {
        val rule = requireNotNull(rulesByType[transaction.type]) {
            "No fee rule for transaction type ${transaction.type}; validation should have rejected it"
        }
        return rule.calculate(transaction)
    }

    private companion object {

        fun index(rules: List<FeeRule>): Map<TransactionType, FeeRule> {
            val byType = rules.groupBy { it.type }

            check(TransactionType.UNKNOWN !in byType) {
                "A fee rule can't price ${TransactionType.UNKNOWN}: ${names(byType.getValue(TransactionType.UNKNOWN))}"
            }

            val duplicates = byType.filterValues { it.size > 1 }
            check(duplicates.isEmpty()) {
                "Each transaction type needs exactly one fee rule, but these have more: " +
                    duplicates.entries.joinToString("; ") { (type, typeRules) -> "$type (${names(typeRules)})" }
            }

            val missing = TransactionType.entries.filter { it != TransactionType.UNKNOWN && it !in byType }
            check(missing.isEmpty()) {
                "Every transaction type needs a fee rule, but these have none: ${missing.joinToString(", ")}"
            }

            return byType.mapValues { (_, typeRules) -> typeRules.single() }
        }

        private fun names(rules: List<FeeRule>): String = rules.joinToString(", ") { it::class.simpleName ?: "anonymous rule" }
    }
}
