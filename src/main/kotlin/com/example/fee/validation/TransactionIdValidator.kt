package com.example.fee.validation

import com.example.fee.contract.FeeRequest
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component

/**
 * `transaction_id` must be 1 to [MAX_LENGTH] ASCII letters, digits, `_` or `-` (400). It becomes part of the workflow
 * key `{transaction_id}:{payloadHash}`, the Virtual Object key and the `GET` path, so `:`, `/` and whitespace are out.
 *
 * `FeeService` also runs it on its own as the key pre-check, before any other validator, so it handles a missing id
 * itself instead of relying on [RequiredFieldsValidator].
 */
@Component
@Order(ValidationOrder.TRANSACTION_ID)
class TransactionIdValidator : TransactionValidator {

    override fun validate(request: FeeRequest): ValidationError? {
        val raw = request.transactionId
            ?: return ValidationError(FIELD, null, "Field '$FIELD' is required", ValidationError.Kind.INVALID)
        if (PATTERN.matches(raw)) return null
        return ValidationError(
            FIELD,
            raw,
            "Invalid $FIELD ${ValueDisplay.quoted(raw)}. Allowed: 1 to $MAX_LENGTH characters, " +
                "each a letter (A-Z, a-z), a digit, '_' or '-'",
            ValidationError.Kind.INVALID,
        )
    }

    companion object {
        const val MAX_LENGTH = 64
        private const val FIELD = "transaction_id"
        private val PATTERN = Regex("[A-Za-z0-9_-]{1,$MAX_LENGTH}")
    }
}
