package com.example.fee.validation

import com.example.fee.contract.FeeRequest
import com.example.fee.domain.Asset
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component

/**
 * `asset` must be a supported [Asset]: `USD` or `EUR` (D13) (422). [AmountScaleValidator] relies on it, because the
 * allowed decimals belong to the asset. Relies on [RequiredFieldsValidator] for presence.
 */
@Component
@Order(ValidationOrder.ASSET)
class AssetValidator : TransactionValidator {

    override fun validate(request: FeeRequest): ValidationError? {
        val raw = request.asset ?: return null
        if (Asset.fromValue(raw) != Asset.UNKNOWN) return null
        return UnsupportedValue.error("asset", raw, SUPPORTED)
    }

    private companion object {
        val SUPPORTED = Asset.entries.filter { it != Asset.UNKNOWN }.map { it.value }
    }
}
