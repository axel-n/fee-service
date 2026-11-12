package com.example.fee.validation

import com.example.fee.config.BankTransferFeeProperties
import com.example.fee.config.CashWithdrawalFeeProperties
import com.example.fee.config.MerchantPaymentFeeProperties
import com.example.fee.config.MobileTopUpFeeProperties
import com.example.fee.contract.FeeRequest
import com.example.fee.fee.FeeCalculator
import com.example.fee.fee.rule.BankTransferFeeRule
import com.example.fee.fee.rule.CashWithdrawalFeeRule
import com.example.fee.fee.rule.MerchantPaymentFeeRule
import com.example.fee.fee.rule.MobileTopUpFeeRule
import java.math.BigDecimal

/** The reference request of the challenge: passes every check. */
internal val validRequest = FeeRequest(
    transactionId = "txn_001",
    amount = BigDecimal("1000"),
    asset = "USD",
    assetType = "FIAT",
    type = "Mobile Top Up",
    state = "SETTLED - PENDING FEE",
    createdAt = "2023-08-30 15:42:17.610059",
)

/** A [FeeCalculator] with the four production rules, as Spring builds it. */
internal fun allRulesCalculator(): FeeCalculator = FeeCalculator(
    listOf(
        MobileTopUpFeeRule(MobileTopUpFeeProperties(BigDecimal("0.0015"))),
        BankTransferFeeRule(BankTransferFeeProperties(BigDecimal("0.005"), BigDecimal("1.00"))),
        MerchantPaymentFeeRule(MerchantPaymentFeeProperties(BigDecimal("0.01"), BigDecimal("0.30"))),
        CashWithdrawalFeeRule(CashWithdrawalFeeProperties(BigDecimal("2.00"))),
    ),
)
