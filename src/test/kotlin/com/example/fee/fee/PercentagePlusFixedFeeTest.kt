package com.example.fee.fee

import com.example.fee.domain.Asset
import com.example.fee.domain.TransactionType
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.math.BigDecimal

class PercentagePlusFixedFeeTest {

    private val rule = PercentagePlusFixedFee(
        TransactionType.MERCHANT_PAYMENT,
        rate = BigDecimal("0.01"),
        fixed = BigDecimal("0.30"),
        description = describeAsset,
    )

    @ParameterizedTest(name = "{0} → {1}")
    @CsvSource(
        "1000, 10.30",
        "0, 0.30",
        "12.34, 0.42", // 0.1234 + 0.30 = 0.4234
        "0.5, 0.30", // 0.305: half to even (down), the sum is rounded once
        "1.5, 0.32", // 0.315: half to even (up)
    )
    fun `charges the rate plus the fixed amount, rounded once`(amount: String, expected: String) {
        // given
        val tx = transaction(amount, type = TransactionType.MERCHANT_PAYMENT)

        // when
        val quote = rule.calculate(tx)

        // then
        assertThat(quote.fee).isEqualByComparingTo(expected)
        assertThat(quote.fee.scale()).isEqualTo(Asset.USD.decimals)
    }

    @Test
    fun `quotes the configured rate and the asset's description`() {
        // given
        val tx = transaction("1000", asset = Asset.EUR, type = TransactionType.MERCHANT_PAYMENT)

        // when
        val quote = rule.calculate(tx)

        // then
        assertThat(quote.rate).isEqualByComparingTo("0.01")
        assertThat(quote.description).isEqualTo("described for EUR")
    }

    @Test
    fun `rejects a negative fixed amount with a readable message`() {
        // given
        val fixed = BigDecimal("-0.30")

        // when / then
        assertThatThrownBy { PercentagePlusFixedFee(TransactionType.MERCHANT_PAYMENT, BigDecimal("0.01"), fixed, describeAsset) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("Fee fixed must not be negative, was -0.30")
    }

    @Test
    fun `rejects a negative rate with a readable message`() {
        // given
        val rate = BigDecimal("-0.01")

        // when / then
        assertThatThrownBy { PercentagePlusFixedFee(TransactionType.MERCHANT_PAYMENT, rate, BigDecimal("0.30"), describeAsset) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("Fee rate must be in [0, 1), was -0.01")
    }
}
