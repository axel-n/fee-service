package com.example.fee.validation.rule

import com.example.fee.contract.FeeRequest
import com.example.fee.validation.TransactionValidator
import com.example.fee.validation.ValidationError
import com.example.fee.validation.ValidationOrder
import com.example.fee.validation.ValueDisplay
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import java.math.BigDecimal

/**
 * `amount` must not exceed [MAX_AMOUNT] in the transaction's asset (422): `3000000000.01` is rejected, `3000000000`
 * passes. The bound is the same number for every asset, like the fixed fee amounts.
 *
 * Relies on [RequiredFieldsValidator] for presence and on [AmountValidator] for the lower bound, so a missing, zero or
 * negative amount passes here.
 */
@Component
@Order(ValidationOrder.MAX_AMOUNT)
class MaxAmountValidator : TransactionValidator {

    override fun validate(request: FeeRequest): ValidationError? {
        val amount = request.amount ?: return null
        if (amount <= MAX_AMOUNT) return null
        val shown = ValueDisplay.value(amount)
        return ValidationError(
            "amount",
            shown,
            "Amount ${ValueDisplay.shortened(shown)} is above the maximum. Allowed: up to ${MAX_AMOUNT.toPlainString()}",
            ValidationError.Kind.UNSUPPORTED,
        )
    }

    companion object {
        /** The largest amount accepted, inclusive: 3 billion. */
        val MAX_AMOUNT: BigDecimal = 3_000_000_000.toBigDecimal()
    }
}
