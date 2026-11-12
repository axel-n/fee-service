package com.example.fee.fee

import com.example.fee.domain.TransactionType
import org.springframework.stereotype.Component

/** Cash Withdrawal: a flat fee whatever the amount, e.g. `Flat fee of 2.00 USD`. */
@Component
class CashWithdrawalFeeRule(properties: CashWithdrawalFeeProperties) : FeeRule by FlatFee(
    type = TransactionType.CASH_WITHDRAWAL,
    fixed = properties.fixed,
    description = { asset -> "Flat fee of ${money(properties.fixed, asset)}" },
)
