package com.example.fee.fee

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.math.BigDecimal

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
