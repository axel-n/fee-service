package com.example.fee.fee

import org.springframework.boot.context.properties.ConfigurationProperties
import java.math.BigDecimal

/** The numbers of [BankTransferFeeRule], from `fee.bank-transfer` (D23). A missing or invalid value fails startup. */
@ConfigurationProperties(BankTransferFeeProperties.PREFIX)
class BankTransferFeeProperties(rate: BigDecimal?, min: BigDecimal?) {

    val rate: BigDecimal = requiredRate("$PREFIX.rate", rate)

    /** The minimum fee, in the transaction's asset. */
    val min: BigDecimal = requiredAmount("$PREFIX.min", min)

    companion object {
        const val PREFIX = "fee.bank-transfer"
    }
}
