package com.example.fee.config

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.math.BigDecimal

class MobileTopUpFeePropertiesTest {
    @Test
    fun `keeps a valid rate`() {
        // given
        val rate = BigDecimal("0.0015")

        // when
        val properties = MobileTopUpFeeProperties(rate)

        // then
        assertThat(properties.rate).isEqualByComparingTo("0.0015")
    }

    @Test
    fun `rejects a missing rate with a readable message`() {
        // given
        val rate: BigDecimal? = null

        // when / then
        assertThatThrownBy { MobileTopUpFeeProperties(rate) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageStartingWith("fee.mobile-top-up.rate is missing")
    }

    @Test
    fun `rejects a rate of 1 or more with a readable message`() {
        // given
        val rate = BigDecimal("1")

        // when / then
        assertThatThrownBy { MobileTopUpFeeProperties(rate) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("fee.mobile-top-up.rate must be in [0, 1), was 1")
    }

    @Test
    fun `rejects a negative rate with a readable message`() {
        // given
        val rate = BigDecimal("-0.0015")

        // when / then
        assertThatThrownBy { MobileTopUpFeeProperties(rate) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("fee.mobile-top-up.rate must be in [0, 1), was -0.0015")
    }
}

class BankTransferFeePropertiesTest {
    @Test
    fun `keeps a valid rate and minimum`() {
        // given
        val rate = BigDecimal("0.005")
        val min = BigDecimal("1.00")

        // when
        val properties = BankTransferFeeProperties(rate, min)

        // then
        assertThat(properties.rate).isEqualByComparingTo("0.005")
        assertThat(properties.min).isEqualByComparingTo("1.00")
    }

    @Test
    fun `accepts a minimum of 0`() {
        // given
        val min = BigDecimal("0")

        // when
        val properties = BankTransferFeeProperties(BigDecimal("0.005"), min)

        // then
        assertThat(properties.min).isEqualByComparingTo("0")
    }

    @Test
    fun `rejects a negative minimum with a readable message`() {
        // given
        val min = BigDecimal("-1.00")

        // when / then
        assertThatThrownBy { BankTransferFeeProperties(BigDecimal("0.005"), min) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("fee.bank-transfer.min must not be negative, was -1.00")
    }

    @Test
    fun `rejects a missing minimum with a readable message`() {
        // given
        val min: BigDecimal? = null

        // when / then
        assertThatThrownBy { BankTransferFeeProperties(BigDecimal("0.005"), min) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageStartingWith("fee.bank-transfer.min is missing")
    }

    @Test
    fun `rejects a rate of 1 or more with a readable message`() {
        // given
        val rate = BigDecimal("1.5")

        // when / then
        assertThatThrownBy { BankTransferFeeProperties(rate, BigDecimal("1.00")) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("fee.bank-transfer.rate must be in [0, 1), was 1.5")
    }

    @Test
    fun `rejects a missing rate with a readable message`() {
        // given
        val rate: BigDecimal? = null

        // when / then
        assertThatThrownBy { BankTransferFeeProperties(rate, BigDecimal("1.00")) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageStartingWith("fee.bank-transfer.rate is missing")
    }
}

class MerchantPaymentFeePropertiesTest {
    @Test
    fun `keeps a valid rate and fixed amount`() {
        // given
        val rate = BigDecimal("0.01")
        val fixed = BigDecimal("0.30")

        // when
        val properties = MerchantPaymentFeeProperties(rate, fixed)

        // then
        assertThat(properties.rate).isEqualByComparingTo("0.01")
        assertThat(properties.fixed).isEqualByComparingTo("0.30")
    }

    @Test
    fun `rejects a negative fixed amount with a readable message`() {
        // given
        val fixed = BigDecimal("-0.30")

        // when / then
        assertThatThrownBy { MerchantPaymentFeeProperties(BigDecimal("0.01"), fixed) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("fee.merchant-payment.fixed must not be negative, was -0.30")
    }

    @Test
    fun `rejects a missing fixed amount with a readable message`() {
        // given
        val fixed: BigDecimal? = null

        // when / then
        assertThatThrownBy { MerchantPaymentFeeProperties(BigDecimal("0.01"), fixed) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageStartingWith("fee.merchant-payment.fixed is missing")
    }

    @Test
    fun `rejects a rate of 1 or more with a readable message`() {
        // given
        val rate = BigDecimal("1.00")

        // when / then
        assertThatThrownBy { MerchantPaymentFeeProperties(rate, BigDecimal("0.30")) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("fee.merchant-payment.rate must be in [0, 1), was 1.00")
    }

    @Test
    fun `rejects a missing rate with a readable message`() {
        // given
        val rate: BigDecimal? = null

        // when / then
        assertThatThrownBy { MerchantPaymentFeeProperties(rate, BigDecimal("0.30")) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageStartingWith("fee.merchant-payment.rate is missing")
    }
}

class CashWithdrawalFeePropertiesTest {
    @Test
    fun `keeps a valid fixed amount`() {
        // given
        val fixed = BigDecimal("2.00")

        // when
        val properties = CashWithdrawalFeeProperties(fixed)

        // then
        assertThat(properties.fixed).isEqualByComparingTo("2.00")
    }

    @Test
    fun `rejects a negative fixed amount with a readable message`() {
        // given
        val fixed = BigDecimal("-2.00")

        // when / then
        assertThatThrownBy { CashWithdrawalFeeProperties(fixed) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("fee.cash-withdrawal.fixed must not be negative, was -2.00")
    }

    @Test
    fun `rejects a missing fixed amount with a readable message`() {
        // given
        val fixed: BigDecimal? = null

        // when / then
        assertThatThrownBy { CashWithdrawalFeeProperties(fixed) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageStartingWith("fee.cash-withdrawal.fixed is missing")
    }
}

class FeePropertyChecksTest {
    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = ["0", "0.0015", "0.999999"])
    fun `accepts a rate in 0 to 1 exclusive`(rate: String) {
        // given
        val value = BigDecimal(rate)

        // when
        val checked = requiredRate("fee.x.rate", value)

        // then
        assertThat(checked).isEqualByComparingTo(rate)
    }

    @Test
    fun `rejects a missing rate naming the key`() {
        // given
        val value: BigDecimal? = null

        // when / then
        assertThatThrownBy { requiredRate("fee.x.rate", value) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("fee.x.rate is missing: set it to a rate in [0, 1), e.g. \"0.005\"")
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = ["1", "1.00", "1.5", "-0.01"])
    fun `rejects a rate outside 0 to 1 exclusive naming the key and the value`(rate: String) {
        // given
        val value = BigDecimal(rate)

        // when / then
        assertThatThrownBy { requiredRate("fee.x.rate", value) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("fee.x.rate must be in [0, 1), was $rate")
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = ["0", "0.00", "1.00", "2.005"])
    fun `accepts an amount of 0 or more`(amount: String) {
        // given
        val value = BigDecimal(amount)

        // when
        val checked = requiredAmount("fee.x.min", value)

        // then
        assertThat(checked).isEqualByComparingTo(amount)
    }

    @Test
    fun `rejects a missing amount naming the key`() {
        // given
        val value: BigDecimal? = null

        // when / then
        assertThatThrownBy { requiredAmount("fee.x.min", value) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("fee.x.min is missing: set it to an amount of 0 or more, e.g. \"1.00\"")
    }

    @Test
    fun `rejects a negative amount naming the key and the value`() {
        // given
        val value = BigDecimal("-0.01")

        // when / then
        assertThatThrownBy { requiredAmount("fee.x.min", value) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("fee.x.min must not be negative, was -0.01")
    }
}
