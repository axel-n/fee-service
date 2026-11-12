package com.example.fee.fee.rule

import com.example.fee.config.BankTransferFeeProperties
import com.example.fee.domain.TransactionType
import com.example.fee.fee.FeeRule
import com.example.fee.fee.formula.PercentageWithMinimumFee
import com.example.fee.fee.money
import com.example.fee.fee.percent
import org.springframework.stereotype.Component

/** Bank Transfer: a percentage with a minimum and no maximum, e.g. `Fee rate of 0.5% (min 1.00 EUR)`. */
@Component
class BankTransferFeeRule(properties: BankTransferFeeProperties) : FeeRule by PercentageWithMinimumFee(
    type = TransactionType.BANK_TRANSFER,
    rate = properties.rate,
    min = properties.min,
    description = { asset -> "Fee rate of ${percent(properties.rate)} (min ${money(properties.min, asset)})" },
)
