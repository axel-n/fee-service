package com.example.fee.validation

import com.example.fee.contract.FeeRequest
import com.example.fee.domain.Asset
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import java.math.BigDecimal

/**
 * `amount` must have no more decimals than its asset allows (422, D11): `10.001` USD is rejected. Trailing zeros don't
 * count, so `10.100` USD passes: it is the same amount as `10.10`.
 *
 * Runs after [AssetValidator] and relies on it: an unknown asset has no decimals to check against, so it passes here.
 */
@Component
@Order(ValidationOrder.AMOUNT_SCALE)
class AmountScaleValidator : TransactionValidator {

    override fun validate(request: FeeRequest): ValidationError? {
        val amount = request.amount ?: return null
        val asset = Asset.fromValue(request.asset)
        if (asset == Asset.UNKNOWN) return null
        val decimals = decimalsOf(amount)
        if (decimals <= asset.decimals) return null
        val shown = ValueDisplay.value(amount)
        return ValidationError(
            "amount",
            shown,
            "Amount ${ValueDisplay.shortened(shown)} has $decimals decimals. ${asset.value} allows ${asset.decimals}",
            ValidationError.Kind.UNSUPPORTED,
        )
    }

    private fun decimalsOf(amount: BigDecimal): Int = amount.stripTrailingZeros().scale().coerceAtLeast(0)

}
