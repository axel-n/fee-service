package com.example.fee.fee

import com.example.fee.domain.Asset
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.math.BigDecimal

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
