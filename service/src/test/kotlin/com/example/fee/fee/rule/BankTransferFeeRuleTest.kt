package com.example.fee.fee.rule

import com.example.fee.config.BankTransferFeeProperties
import com.example.fee.domain.Asset
import com.example.fee.domain.TransactionType
import com.example.fee.fee.transaction
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.math.BigDecimal

class BankTransferFeeRuleTest {
    private val rule = BankTransferFeeRule(BankTransferFeeProperties(BigDecimal("0.005"), BigDecimal("1.00")))

    @Test
    fun `prices Bank Transfer`() {
        // given / when
        val type = rule.type

        // then
        assertThat(type).isEqualTo(TransactionType.BANK_TRANSFER)
    }

    @ParameterizedTest(name = "{0} USD → {1}")
    @CsvSource(
        "1000, 5.0",
        "100, 1.0", // the minimum
        "10000, 50.0",
    )
    fun `charges the fee table's numbers`(amount: String, expected: String) {
        // given
        val tx = transaction(amount, Asset.USD, TransactionType.BANK_TRANSFER)

        // when
        val quote = rule.calculate(tx)

        // then
        assertThat(quote.fee).isEqualByComparingTo(expected)
        assertThat(quote.rate).isEqualByComparingTo("0.005")
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(
        "USD, Fee rate of 0.5% (min 1.00 USD)",
        "EUR, Fee rate of 0.5% (min 1.00 EUR)",
    )
    fun `describes rate and minimum in the transaction's asset`(asset: Asset, expected: String) {
        // given
        val tx = transaction("1000", asset, TransactionType.BANK_TRANSFER)

        // when
        val quote = rule.calculate(tx)

        // then
        assertThat(quote.description).isEqualTo(expected)
    }

    @Test
    fun `uses the configured rate and minimum for fee and description`() {
        // given
        val configured = BankTransferFeeRule(BankTransferFeeProperties(BigDecimal("0.006"), BigDecimal("1.5")))
        val tx = transaction("100", Asset.EUR, TransactionType.BANK_TRANSFER)

        // when
        val quote = configured.calculate(tx)

        // then
        assertThat(quote.fee).isEqualByComparingTo("1.50")
        assertThat(quote.rate).isEqualByComparingTo("0.006")
        assertThat(quote.description).isEqualTo("Fee rate of 0.6% (min 1.50 EUR)")
    }
}
