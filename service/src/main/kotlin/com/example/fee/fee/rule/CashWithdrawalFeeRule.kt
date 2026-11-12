package com.example.fee.fee.rule

import com.example.fee.config.CashWithdrawalFeeProperties
import com.example.fee.domain.TransactionType
import com.example.fee.fee.FeeRule
import com.example.fee.fee.formula.FlatFee
import com.example.fee.fee.money
import org.springframework.stereotype.Component

/** Cash Withdrawal: a flat fee whatever the amount, e.g. `Flat fee of 2.00 USD`. */
@Component
class CashWithdrawalFeeRule(properties: CashWithdrawalFeeProperties) : FeeRule by FlatFee(
    type = TransactionType.CASH_WITHDRAWAL,
    fixed = properties.fixed,
    description = { asset -> "Flat fee of ${money(properties.fixed, asset)}" },
)
