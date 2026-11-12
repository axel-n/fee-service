package com.example.fee.fee

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.math.BigDecimal

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
