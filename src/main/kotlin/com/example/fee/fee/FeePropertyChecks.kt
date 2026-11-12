package com.example.fee.fee

import java.math.BigDecimal

// The checks every *FeeProperties class runs in init (D23): a missing or invalid fee number fails startup with a
// message that names the configuration key, without the Bean Validation starter.

/** [value] of the configuration key [key] as a rate: present and in [0, 1). */
internal fun requiredRate(key: String, value: BigDecimal?): BigDecimal {
    requireNotNull(value) { "$key is missing: set it to a rate in [0, 1), e.g. \"0.005\"" }
    require(value.signum() >= 0 && value < BigDecimal.ONE) { "$key must be in [0, 1), was ${value.toPlainString()}" }
    return value
}

/** [value] of the configuration key [key] as an amount in the transaction's asset: present and not negative. */
internal fun requiredAmount(key: String, value: BigDecimal?): BigDecimal {
    requireNotNull(value) { "$key is missing: set it to an amount of 0 or more, e.g. \"1.00\"" }
    require(value.signum() >= 0) { "$key must not be negative, was ${value.toPlainString()}" }
    return value
}
