package com.example.fee.validation

import com.example.fee.contract.FeeRequest
import com.example.fee.domain.TransactionState
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component

/**
 * `state` must be a supported [TransactionState]: only `SETTLED - PENDING FEE` is processed (D12) (422).
 * Relies on [RequiredFieldsValidator] for presence.
 */
@Component
@Order(ValidationOrder.STATE)
class StateValidator : TransactionValidator {

    override fun validate(request: FeeRequest): ValidationError? {
        val raw = request.state ?: return null
        if (TransactionState.fromValue(raw) != TransactionState.UNKNOWN) return null
        return UnsupportedValue.error("state", raw, SUPPORTED)
    }

    private companion object {
        val SUPPORTED = TransactionState.entries.filter { it != TransactionState.UNKNOWN }.map { it.value }
    }
}
