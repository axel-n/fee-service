package com.example.fee.validation.rule

import com.example.fee.contract.FeeRequest
import com.example.fee.validation.TransactionValidator
import com.example.fee.validation.ValidationError
import com.example.fee.validation.ValidationOrder
import com.example.fee.validation.ValueDisplay
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component

/**
 * `amount` must be greater than zero (400). Its decimals are a rule of the asset and are checked later by the 422
 * `AmountScaleValidator`, its upper bound by the 422 `MaxAmountValidator`. Relies on [RequiredFieldsValidator] for presence.
 */
@Component
@Order(ValidationOrder.AMOUNT)
class AmountValidator : TransactionValidator {
    override fun validate(request: FeeRequest): ValidationError? {
        val amount = request.amount ?: return null
        if (amount.signum() > 0) return null
        val shown = ValueDisplay.value(amount)
        return ValidationError(
            "amount",
            shown,
            "Invalid amount ${ValueDisplay.quoted(shown)}. Allowed: a number greater than 0",
            ValidationError.Kind.INVALID,
        )
    }
}
