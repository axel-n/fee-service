package com.example.fee.fee

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.math.BigDecimal

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
