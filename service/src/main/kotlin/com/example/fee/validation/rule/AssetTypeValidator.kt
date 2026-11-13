package com.example.fee.validation.rule

import com.example.fee.contract.FeeRequest
import com.example.fee.domain.AssetType
import com.example.fee.validation.TransactionValidator
import com.example.fee.validation.UnsupportedValue
import com.example.fee.validation.ValidationError
import com.example.fee.validation.ValidationOrder
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component

/**
 * `asset_type` must be a supported [AssetType]: only `FIAT` (422). Relies on [RequiredFieldsValidator] for
 * presence.
 */
@Component
@Order(ValidationOrder.ASSET_TYPE)
class AssetTypeValidator : TransactionValidator {
    override fun validate(request: FeeRequest): ValidationError? {
        val raw = request.assetType ?: return null
        if (AssetType.fromValue(raw) != AssetType.UNKNOWN) return null
        return UnsupportedValue.error("asset_type", raw, SUPPORTED)
    }

    private companion object {
        val SUPPORTED = AssetType.entries.filter { it != AssetType.UNKNOWN }.map { it.value }
    }
}
