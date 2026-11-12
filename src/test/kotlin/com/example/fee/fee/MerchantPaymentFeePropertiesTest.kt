package com.example.fee.fee

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.math.BigDecimal

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
