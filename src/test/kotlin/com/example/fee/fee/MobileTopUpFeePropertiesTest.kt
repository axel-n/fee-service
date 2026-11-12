package com.example.fee.fee

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
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
