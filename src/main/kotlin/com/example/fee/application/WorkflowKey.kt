package com.example.fee.application

import com.example.fee.contract.FeeRequest
import java.math.BigDecimal
import java.security.MessageDigest
import java.util.HexFormat

/**
 * The key of a `FeeWorkflow` run: `{transaction_id}:{payloadHash}` (D8). The same payload gets the same key, so Restate
 * runs it once and a resubmission attaches to it; a changed payload gets a new key, so a rejected request doesn't use up
 * its `transaction_id`. Built only after the `transaction_id` pre-check, which already rules out `:`.
 */
@JvmInline
value class WorkflowKey private constructor(val value: String) {

    override fun toString(): String = value

    companion object {
        private const val SEPARATOR = ':'

        fun of(transactionId: String, payloadHash: PayloadHash): WorkflowKey {
            require(transactionId.isNotEmpty() && SEPARATOR !in transactionId) {
                "A workflow key needs a non-empty transaction_id without '$SEPARATOR', got '$transactionId'"
            }
            return WorkflowKey("$transactionId$SEPARATOR$payloadHash")
        }
    }
}

/**
 * The SHA-256 of a [FeeRequest]'s canonical form, as 64 lowercase hex digits: the second half of the workflow key (D8)
 * and the hash the fee record keeps to tell one payload from another (D47).
 *
 * The canonical form is built here, not by a JSON mapper, so no mapper setting can change it: the fields in a fixed
 * order, each text as sent (case-sensitive, untrimmed, D37), a missing field distinct from an empty one, and the amount
 * normalized, so `1000`, `1000.00` and `1E+3` hash alike. Hashes are stored in workflow keys and fee records, so changing
 * the canonical form is a breaking change: a resubmitted payload would get a new key and, once charged, a 409 (D56).
 */
@JvmInline
value class PayloadHash(val value: String) {

    init {
        require(FORMAT.matches(value)) { "A payload hash is 64 lowercase hex digits, got '$value'" }
    }

    override fun toString(): String = value

    companion object {
        private val FORMAT = Regex("[0-9a-f]{64}")
        private const val VERSION = "fee-request/v1"

        fun of(request: FeeRequest): PayloadHash {
            val digest = MessageDigest.getInstance("SHA-256").digest(canonical(request).toByteArray(Charsets.UTF_8))
            return PayloadHash(HexFormat.of().formatHex(digest))
        }

        /** Each value is length-prefixed, so no content of one field can pass for another field. */
        internal fun canonical(request: FeeRequest): String = buildString {
            append(VERSION).append('\n')
            field("transaction_id", request.transactionId)
            field("amount", request.amount?.let(::normalized))
            field("asset", request.asset)
            field("asset_type", request.assetType)
            field("type", request.type)
            field("state", request.state)
            field("created_at", request.createdAt)
        }

        private fun StringBuilder.field(name: String, value: String?) {
            append(name).append('=')
            if (value == null) append('-') else append(value.length).append(':').append(value)
            append('\n')
        }

        /**
         * Unscaled value and exponent of the amount without trailing zeros: one form per numeric value, and bounded in
         * size, because the hash is taken before validation and `toPlainString()` of `1e999999999` has a billion digits.
         */
        private fun normalized(amount: BigDecimal): String {
            val stripped = amount.stripTrailingZeros()
            return "${stripped.unscaledValue()}E${-stripped.scale()}"
        }
    }
}
