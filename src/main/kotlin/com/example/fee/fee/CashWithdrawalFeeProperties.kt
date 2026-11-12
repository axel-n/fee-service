package com.example.fee.fee

import org.springframework.boot.context.properties.ConfigurationProperties
import java.math.BigDecimal

/** The numbers of [CashWithdrawalFeeRule], from `fee.cash-withdrawal` (D23). A missing or invalid value fails startup. */
@ConfigurationProperties(CashWithdrawalFeeProperties.PREFIX)
class CashWithdrawalFeeProperties(fixed: BigDecimal?) {

    /** The flat fee, in the transaction's asset. */
    val fixed: BigDecimal = requiredAmount("$PREFIX.fixed", fixed)

    companion object {
        const val PREFIX = "fee.cash-withdrawal"
    }
}
