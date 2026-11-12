package com.example.fee.config

import org.springframework.boot.context.properties.ConfigurationProperties
import java.math.BigDecimal

// Every @ConfigurationProperties class lives in this package, one file per top-level section of application.yml
// (D75). This file is the `fee:` section: one class per transaction type, so a new type adds a class here (D23).

/** The numbers of `MobileTopUpFeeRule`, from `fee.mobile-top-up` (D23). A missing or invalid value fails startup. */
@ConfigurationProperties(MobileTopUpFeeProperties.PREFIX)
class MobileTopUpFeeProperties(rate: BigDecimal?) {

    val rate: BigDecimal = requiredRate("$PREFIX.rate", rate)

    companion object {
        const val PREFIX = "fee.mobile-top-up"
    }
}

/** The numbers of `BankTransferFeeRule`, from `fee.bank-transfer` (D23). A missing or invalid value fails startup. */
@ConfigurationProperties(BankTransferFeeProperties.PREFIX)
class BankTransferFeeProperties(rate: BigDecimal?, min: BigDecimal?) {

    val rate: BigDecimal = requiredRate("$PREFIX.rate", rate)

    /** The minimum fee, in the transaction's asset. */
    val min: BigDecimal = requiredAmount("$PREFIX.min", min)

    companion object {
        const val PREFIX = "fee.bank-transfer"
    }
}

/** The numbers of `MerchantPaymentFeeRule`, from `fee.merchant-payment` (D23). A missing or invalid value fails startup. */
@ConfigurationProperties(MerchantPaymentFeeProperties.PREFIX)
class MerchantPaymentFeeProperties(rate: BigDecimal?, fixed: BigDecimal?) {

    val rate: BigDecimal = requiredRate("$PREFIX.rate", rate)

    /** The fixed part added to the percentage, in the transaction's asset. */
    val fixed: BigDecimal = requiredAmount("$PREFIX.fixed", fixed)

    companion object {
        const val PREFIX = "fee.merchant-payment"
    }
}

/** The numbers of `CashWithdrawalFeeRule`, from `fee.cash-withdrawal` (D23). A missing or invalid value fails startup. */
@ConfigurationProperties(CashWithdrawalFeeProperties.PREFIX)
class CashWithdrawalFeeProperties(fixed: BigDecimal?) {

    /** The flat fee, in the transaction's asset. */
    val fixed: BigDecimal = requiredAmount("$PREFIX.fixed", fixed)

    companion object {
        const val PREFIX = "fee.cash-withdrawal"
    }
}

// The checks every *FeeProperties class above runs in init (D23): a missing or invalid fee number fails startup with a
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
