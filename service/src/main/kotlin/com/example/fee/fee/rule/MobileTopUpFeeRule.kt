package com.example.fee.fee.rule

import com.example.fee.config.MobileTopUpFeeProperties
import com.example.fee.domain.TransactionType
import com.example.fee.fee.FeeRule
import com.example.fee.fee.formula.PercentageFee
import com.example.fee.fee.percent
import org.springframework.stereotype.Component

/** Mobile Top Up: a percentage of the amount, e.g. `Standard fee rate of 0.15%`. */
@Component
class MobileTopUpFeeRule(properties: MobileTopUpFeeProperties) : FeeRule by PercentageFee(
    type = TransactionType.MOBILE_TOP_UP,
    rate = properties.rate,
    description = { "Standard fee rate of ${percent(properties.rate)}" },
)
