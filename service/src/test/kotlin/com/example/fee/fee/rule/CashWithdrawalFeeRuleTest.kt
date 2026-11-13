package com.example.fee.fee.rule

import com.example.fee.config.CashWithdrawalFeeProperties
import com.example.fee.domain.Asset
import com.example.fee.domain.TransactionType
import com.example.fee.fee.transaction
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.math.BigDecimal

class CashWithdrawalFeeRuleTest {
    private val rule = CashWithdrawalFeeRule(CashWithdrawalFeeProperties(BigDecimal("2.00")))

    @Test
    fun `prices Cash Withdrawal`() {
        // given / when
        val type = rule.type

        // then
        assertThat(type).isEqualTo(TransactionType.CASH_WITHDRAWAL)
    }

    @ParameterizedTest(name = "{0} USD → 2.0")
    @CsvSource("1000", "1", "1000000")
    fun `charges the flat fee whatever the amount with rate 0`(amount: String) {
        // given
        val tx = transaction(amount, Asset.USD, TransactionType.CASH_WITHDRAWAL)

        // when
        val quote = rule.calculate(tx)

        // then
        assertThat(quote.fee).isEqualByComparingTo("2.0")
        assertThat(quote.rate).isEqualByComparingTo("0")
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(
        "USD, Flat fee of 2.00 USD",
        "EUR, Flat fee of 2.00 EUR",
    )
    fun `describes the flat fee in the transaction's asset`(asset: Asset, expected: String) {
        // given
        val tx = transaction("1000", asset, TransactionType.CASH_WITHDRAWAL)

        // when
        val quote = rule.calculate(tx)

        // then
        assertThat(quote.description).isEqualTo(expected)
    }

    @Test
    fun `uses the configured fixed amount for fee and description`() {
        // given
        val configured = CashWithdrawalFeeRule(CashWithdrawalFeeProperties(BigDecimal("2.5")))
        val tx = transaction("1000", Asset.EUR, TransactionType.CASH_WITHDRAWAL)

        // when
        val quote = configured.calculate(tx)

        // then
        assertThat(quote.fee).isEqualByComparingTo("2.50")
        assertThat(quote.description).isEqualTo("Flat fee of 2.50 EUR")
    }
}
