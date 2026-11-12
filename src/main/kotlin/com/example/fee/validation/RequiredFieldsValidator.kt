package com.example.fee.validation

import com.example.fee.contract.FeeRequest
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component

/**
 * Every field of the contract must be present, and a text field must not be blank (400). Runs first, so the later
 * checks can rely on the fields being there. Reports the first missing field in contract order.
 */
@Component
@Order(ValidationOrder.REQUIRED_FIELDS)
class RequiredFieldsValidator : TransactionValidator {

    override fun validate(request: FeeRequest): ValidationError? {
        if (request.transactionId.isNullOrBlank()) return missing("transaction_id", request.transactionId)
        if (request.amount == null) return missing("amount", null)
        return textFields(request).firstNotNullOfOrNull { (field, raw) ->
            if (raw.isNullOrBlank()) missing(field, raw) else null
        }
    }

    private fun textFields(request: FeeRequest): List<Pair<String, String?>> = listOf(
        "asset" to request.asset,
        "asset_type" to request.assetType,
        "type" to request.type,
        "state" to request.state,
        "created_at" to request.createdAt,
    )

    private fun missing(field: String, raw: String?): ValidationError {
        val message = if (raw == null) {
            "Field '$field' is required"
        } else {
            "Field '$field' is required but was blank: ${ValueDisplay.quoted(raw)}"
        }
        return ValidationError(field, raw, message, ValidationError.Kind.INVALID)
    }
}
