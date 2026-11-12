package com.example.fee.fee

import org.springframework.boot.context.properties.ConfigurationProperties
import java.math.BigDecimal

/** The numbers of [MobileTopUpFeeRule], from `fee.mobile-top-up` (D23). A missing or invalid value fails startup. */
@ConfigurationProperties(MobileTopUpFeeProperties.PREFIX)
class MobileTopUpFeeProperties(rate: BigDecimal?) {

    val rate: BigDecimal = requiredRate("$PREFIX.rate", rate)

    companion object {
        const val PREFIX = "fee.mobile-top-up"
    }
}
