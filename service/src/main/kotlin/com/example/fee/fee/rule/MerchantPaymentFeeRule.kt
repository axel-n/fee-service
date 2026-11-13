package com.example.fee.fee.rule

import com.example.fee.config.MerchantPaymentFeeProperties
import com.example.fee.domain.TransactionType
import com.example.fee.fee.FeeRule
import com.example.fee.fee.formula.PercentagePlusFixedFee
import com.example.fee.fee.money
import com.example.fee.fee.percent
import org.springframework.stereotype.Component

/** Merchant Payment: a percentage plus a fixed amount, e.g. `Fee rate of 1% plus 0.30 USD fixed`. */
@Component
class MerchantPaymentFeeRule(
    properties: MerchantPaymentFeeProperties,
) : FeeRule by PercentagePlusFixedFee(
        type = TransactionType.MERCHANT_PAYMENT,
        rate = properties.rate,
        fixed = properties.fixed,
        description = { asset -> "Fee rate of ${percent(properties.rate)} plus ${money(properties.fixed, asset)} fixed" },
    )
