package com.example.fee.fee.rule

import com.example.fee.config.MerchantPaymentFeeProperties
import com.example.fee.domain.Asset
import com.example.fee.domain.TransactionType
import com.example.fee.fee.transaction
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.math.BigDecimal

class MerchantPaymentFeeRuleTest {

    private val rule = MerchantPaymentFeeRule(MerchantPaymentFeeProperties(BigDecimal("0.01"), BigDecimal("0.30")))

    @Test
    fun `prices Merchant Payment`() {
        // given / when
        val type = rule.type

        // then
        assertThat(type).isEqualTo(TransactionType.MERCHANT_PAYMENT)
    }

    @Test
    fun `charges the fee table's 10,3 on 1000 USD`() {
        // given
        val tx = transaction("1000", Asset.USD, TransactionType.MERCHANT_PAYMENT)

        // when
        val quote = rule.calculate(tx)

        // then
        assertThat(quote.fee).isEqualByComparingTo("10.3")
        assertThat(quote.rate).isEqualByComparingTo("0.01")
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(
        "USD, Fee rate of 1% plus 0.30 USD fixed",
        "EUR, Fee rate of 1% plus 0.30 EUR fixed",
    )
    fun `describes rate and fixed amount in the transaction's asset`(asset: Asset, expected: String) {
        // given
        val tx = transaction("1000", asset, TransactionType.MERCHANT_PAYMENT)

        // when
        val quote = rule.calculate(tx)

        // then
        assertThat(quote.description).isEqualTo(expected)
    }

    @Test
    fun `uses the configured rate and fixed amount for fee and description`() {
        // given
        val configured = MerchantPaymentFeeRule(MerchantPaymentFeeProperties(BigDecimal("0.0125"), BigDecimal("0.5")))
        val tx = transaction("1000", Asset.EUR, TransactionType.MERCHANT_PAYMENT)

        // when
        val quote = configured.calculate(tx)

        // then
        assertThat(quote.fee).isEqualByComparingTo("13.00")
        assertThat(quote.rate).isEqualByComparingTo("0.0125")
        assertThat(quote.description).isEqualTo("Fee rate of 1.25% plus 0.50 EUR fixed")
    }
}
