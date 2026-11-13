package com.example.fee.fee.formula

import com.example.fee.domain.Asset
import com.example.fee.domain.TransactionType
import com.example.fee.fee.describeAsset
import com.example.fee.fee.transaction
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.math.BigDecimal

class FlatFeeTest {
    private val rule = FlatFee(TransactionType.CASH_WITHDRAWAL, BigDecimal("2.00"), describeAsset)

    @ParameterizedTest(name = "{0} → 2.00")
    @ValueSource(strings = ["0", "0.01", "1000", "99999999.99"])
    fun `charges the fixed amount whatever the amount`(amount: String) {
        // given
        val tx = transaction(amount, type = TransactionType.CASH_WITHDRAWAL)

        // when
        val quote = rule.calculate(tx)

        // then
        assertThat(quote.fee).isEqualByComparingTo("2.00")
        assertThat(quote.fee.scale()).isEqualTo(Asset.USD.decimals)
    }

    @Test
    fun `quotes a zero rate and the asset's description`() {
        // given
        val tx = transaction("1000", asset = Asset.EUR, type = TransactionType.CASH_WITHDRAWAL)

        // when
        val quote = rule.calculate(tx)

        // then
        assertThat(quote.rate).isEqualByComparingTo("0")
        assertThat(quote.description).isEqualTo("described for EUR")
    }

    @Test
    fun `rounds a fixed amount with more decimals than the asset`() {
        // given
        val fine = FlatFee(TransactionType.CASH_WITHDRAWAL, BigDecimal("2.125"), describeAsset)

        // when
        val quote = fine.calculate(transaction("1000"))

        // then
        assertThat(quote.fee).isEqualByComparingTo("2.12")
    }

    @Test
    fun `rejects a negative fixed amount with a readable message`() {
        // given
        val fixed = BigDecimal("-2.00")

        // when / then
        assertThatThrownBy { FlatFee(TransactionType.CASH_WITHDRAWAL, fixed, describeAsset) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("Fee fixed must not be negative, was -2.00")
    }
}
