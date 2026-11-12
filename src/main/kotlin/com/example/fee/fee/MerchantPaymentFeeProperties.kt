package com.example.fee.fee

import org.springframework.boot.context.properties.ConfigurationProperties
import java.math.BigDecimal

/** The numbers of [MerchantPaymentFeeRule], from `fee.merchant-payment` (D23). A missing or invalid value fails startup. */
@ConfigurationProperties(MerchantPaymentFeeProperties.PREFIX)
class MerchantPaymentFeeProperties(rate: BigDecimal?, fixed: BigDecimal?) {

    val rate: BigDecimal = requiredRate("$PREFIX.rate", rate)

    /** The fixed part added to the percentage, in the transaction's asset. */
    val fixed: BigDecimal = requiredAmount("$PREFIX.fixed", fixed)

    companion object {
        const val PREFIX = "fee.merchant-payment"
    }
}
