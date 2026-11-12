package com.example.fee.fee.formula

import com.example.fee.domain.Asset
import com.example.fee.domain.TransactionType
import com.example.fee.fee.describeAsset
import com.example.fee.fee.transaction
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource
import java.math.BigDecimal

class PercentageFeeTest {

    private val rule = PercentageFee(TransactionType.MOBILE_TOP_UP, BigDecimal("0.0015"), describeAsset)

    @Test
    fun `charges the rate of the reference example`() {
        // given
        val tx = transaction("1000")

        // when
        val quote = rule.calculate(tx)

        // then
        assertThat(quote.fee).isEqualByComparingTo("1.5")
        assertThat(quote.rate).isEqualByComparingTo("0.0015")
        assertThat(quote.description).isEqualTo("described for USD")
    }

    @ParameterizedTest(name = "{0} → {1}")
    @CsvSource(
        "1000, 1.50",
        "333.33, 0.50", // 0.499995 rounds up
        "1, 0.00", // 0.0015 rounds down
        "0.01, 0.00",
        "0, 0.00",
        "1000000.99, 1500.00", // 1500.001485
    )
    fun `rounds to the asset's decimals`(amount: String, expected: String) {
        // given
        val tx = transaction(amount)

        // when
        val quote = rule.calculate(tx)

        // then
        assertThat(quote.fee).isEqualByComparingTo(expected)
        assertThat(quote.fee.scale()).isEqualTo(Asset.USD.decimals)
    }

    @ParameterizedTest(name = "{0} × 0.005 → {1}")
    @CsvSource(
        "1, 0.00", // 0.005: half to even (down)
        "3, 0.02", // 0.015: half to even (up)
        "5, 0.02", // 0.025: half to even (down)
        "7, 0.04", // 0.035: half to even (up)
    )
    fun `rounds a half to the even neighbour`(amount: String, expected: String) {
        // given
        val halfCent = PercentageFee(TransactionType.MOBILE_TOP_UP, BigDecimal("0.005"), describeAsset)

        // when
        val quote = halfCent.calculate(transaction(amount))

        // then
        assertThat(quote.fee).isEqualByComparingTo(expected)
    }

    @Test
    fun `builds the description for the transaction's asset`() {
        // given
        val tx = transaction("1000", asset = Asset.EUR)

        // when
        val quote = rule.calculate(tx)

        // then
        assertThat(quote.description).isEqualTo("described for EUR")
    }

    @Test
    fun `reports the type it was built for`() {
        // given
        val built = PercentageFee(TransactionType.BANK_TRANSFER, BigDecimal("0.01"), describeAsset)

        // when
        val type = built.type

        // then
        assertThat(type).isEqualTo(TransactionType.BANK_TRANSFER)
    }

    @ParameterizedTest
    @ValueSource(strings = ["-0.001", "1", "1.5"])
    fun `rejects a rate outside 0 to 1 with a readable message`(rate: String) {
        // given
        val invalid = BigDecimal(rate)

        // when / then
        assertThatThrownBy { PercentageFee(TransactionType.MOBILE_TOP_UP, invalid, describeAsset) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("Fee rate must be in [0, 1), was $rate")
    }
}
