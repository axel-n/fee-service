package com.example.fee.fee

import com.example.fee.domain.Asset
import com.example.fee.domain.TransactionType
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.math.BigDecimal

class PercentageWithMinimumFeeTest {

    private val rule = PercentageWithMinimumFee(
        TransactionType.BANK_TRANSFER,
        rate = BigDecimal("0.005"),
        min = BigDecimal("1.00"),
        description = describeAsset,
    )

    @ParameterizedTest(name = "{0} → {1}")
    @CsvSource(
        "1000, 5.00",
        "10000, 50.00",
        "100, 1.00", // 0.50 is below the minimum
        "0.01, 1.00",
        "200, 1.00", // exactly the minimum
        "199.99, 1.00", // 0.99995 rounds to the minimum
        "201, 1.00", // 1.005: half to even (down)
        "203, 1.02", // 1.015: half to even (up)
    )
    fun `charges the rate but at least the minimum`(amount: String, expected: String) {
        // given
        val tx = transaction(amount, type = TransactionType.BANK_TRANSFER)

        // when
        val quote = rule.calculate(tx)

        // then
        assertThat(quote.fee).isEqualByComparingTo(expected)
        assertThat(quote.fee.scale()).isEqualTo(Asset.USD.decimals)
    }

    @Test
    fun `keeps the configured rate when the minimum applies`() {
        // given
        val tx = transaction("100", asset = Asset.EUR, type = TransactionType.BANK_TRANSFER)

        // when
        val quote = rule.calculate(tx)

        // then
        assertThat(quote.fee).isEqualByComparingTo("1.00")
        assertThat(quote.rate).isEqualByComparingTo("0.005")
        assertThat(quote.description).isEqualTo("described for EUR")
    }

    @Test
    fun `rounds a minimum with more decimals than the asset`() {
        // given
        val fine = PercentageWithMinimumFee(TransactionType.BANK_TRANSFER, BigDecimal("0.005"), BigDecimal("1.005"), describeAsset)

        // when
        val quote = fine.calculate(transaction("1"))

        // then
        assertThat(quote.fee).isEqualByComparingTo("1.00")
    }

    @Test
    fun `rejects a negative minimum with a readable message`() {
        // given
        val min = BigDecimal("-1.00")

        // when / then
        assertThatThrownBy { PercentageWithMinimumFee(TransactionType.BANK_TRANSFER, BigDecimal("0.005"), min, describeAsset) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("Fee min must not be negative, was -1.00")
    }

    @Test
    fun `rejects a rate of 1 or more with a readable message`() {
        // given
        val rate = BigDecimal("1.0")

        // when / then
        assertThatThrownBy { PercentageWithMinimumFee(TransactionType.BANK_TRANSFER, rate, BigDecimal("1.00"), describeAsset) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("Fee rate must be in [0, 1), was 1.0")
    }
}
