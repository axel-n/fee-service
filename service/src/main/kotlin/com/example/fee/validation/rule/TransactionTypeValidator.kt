package com.example.fee.validation.rule

import com.example.fee.contract.FeeRequest
import com.example.fee.domain.TransactionType
import com.example.fee.fee.FeeCalculator
import com.example.fee.validation.TransactionValidator
import com.example.fee.validation.UnsupportedValue
import com.example.fee.validation.ValidationError
import com.example.fee.validation.ValidationOrder
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component

/**
 * `type` must be a [TransactionType] that has a fee rule, as [FeeCalculator.supports] says (422). So CALCULATE never
 * meets a type without a rule. Relies on [RequiredFieldsValidator] for presence.
 */
@Component
@Order(ValidationOrder.TRANSACTION_TYPE)
class TransactionTypeValidator(
    private val feeCalculator: FeeCalculator,
) : TransactionValidator {
    private val supported: List<String> = TransactionType.entries.filter(feeCalculator::supports).map { it.value }

    override fun validate(request: FeeRequest): ValidationError? {
        val raw = request.type ?: return null
        if (feeCalculator.supports(TransactionType.fromValue(raw))) return null
        return UnsupportedValue.error("type", raw, supported)
    }
}
