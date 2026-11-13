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

class FeeDescriptionsTest {
    @ParameterizedTest(name = "{0} → {1}")
    @CsvSource(
        "0.0015, 0.15%",
        "0.005, 0.5%",
        "0.01, 1%",
        "0.010, 1%",
        "0.1, 10%",
        "0, 0%",
        "0.000, 0%",
        "0.00125, 0.125%",
    )
    fun `writes a rate as a percentage without trailing zeros`(rate: String, expected: String) {
        // given
        val value = BigDecimal(rate)

        // when
        val text = percent(value)

        // then
        assertThat(text).isEqualTo(expected)
    }

    @ParameterizedTest(name = "{0} {1} → {2}")
    @CsvSource(
        "1.00, USD, 1.00 USD",
        "1, EUR, 1.00 EUR",
        "0.3, EUR, 0.30 EUR",
        "2.005, USD, 2.00 USD",
        "1000, USD, 1000.00 USD",
    )
    fun `writes an amount at the asset's decimals followed by the asset`(amount: String, asset: Asset, expected: String) {
        // given
        val value = BigDecimal(amount)

        // when
        val text = money(value, asset)

        // then
        assertThat(text).isEqualTo(expected)
    }
}
