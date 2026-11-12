package com.example.fee.application

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
