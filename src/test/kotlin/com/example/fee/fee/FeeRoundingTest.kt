package com.example.fee.fee

import com.example.fee.domain.Asset
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatCode
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource
import java.math.BigDecimal

class FeeRoundingTest {

    @ParameterizedTest(name = "{0} {1} → {2}")
    @CsvSource(
        "1.005, USD, 1.00",
        "1.015, EUR, 1.02",
        "1.0051, USD, 1.01",
        "1.5, USD, 1.50",
        "2.5, UNKNOWN, 2",
    )
    fun `rounds half to even at the asset's decimals`(value: String, asset: Asset, expected: String) {
        // given
        val amount = BigDecimal(value)

        // when
        val rounded = amount.roundedFor(asset)

        // then
        assertThat(rounded).isEqualTo(BigDecimal(expected))
    }

    @ParameterizedTest
    @ValueSource(strings = ["0", "0.0015", "0.999999"])
    fun `accepts a rate in 0 to 1`(rate: String) {
        // given
        val value = BigDecimal(rate)

        // when / then
        assertThatCode { requireRate(value) }.doesNotThrowAnyException()
    }

    @ParameterizedTest
    @ValueSource(strings = ["0", "0.00", "2.00"])
    fun `accepts a non-negative amount`(amount: String) {
        // given
        val value = BigDecimal(amount)

        // when / then
        assertThatCode { requireNonNegative("fixed", value) }.doesNotThrowAnyException()
    }
}
