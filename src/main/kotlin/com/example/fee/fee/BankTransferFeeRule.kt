package com.example.fee.fee

import com.example.fee.domain.TransactionType
import org.springframework.stereotype.Component

/** Bank Transfer: a percentage with a minimum and no maximum (D23), e.g. `Fee rate of 0.5% (min 1.00 EUR)`. */
@Component
class BankTransferFeeRule(properties: BankTransferFeeProperties) : FeeRule by PercentageWithMinimumFee(
    type = TransactionType.BANK_TRANSFER,
    rate = properties.rate,
    min = properties.min,
    description = { asset -> "Fee rate of ${percent(properties.rate)} (min ${money(properties.min, asset)})" },
)
