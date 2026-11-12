package com.example.fee.fee

import com.example.fee.domain.Asset
import com.example.fee.domain.TransactionType
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import java.math.BigDecimal

class MobileTopUpFeeRuleTest {

    private val rule = MobileTopUpFeeRule(MobileTopUpFeeProperties(BigDecimal("0.0015")))

    @Test
    fun `prices Mobile Top Up`() {
        // given / when
        val type = rule.type

        // then
        assertThat(type).isEqualTo(TransactionType.MOBILE_TOP_UP)
    }

    @Test
    fun `charges the reference example 1,5 on 1000 USD`() {
        // given
        val tx = transaction("1000", Asset.USD, TransactionType.MOBILE_TOP_UP)

        // when
        val quote = rule.calculate(tx)

        // then
        assertThat(quote.fee).isEqualByComparingTo("1.5")
        assertThat(quote.rate).isEqualByComparingTo("0.0015")
        assertThat(quote.description).isEqualTo("Standard fee rate of 0.15%")
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(value = Asset::class, names = ["USD", "EUR"])
    fun `describes the configured rate as a percentage in every asset`(asset: Asset) {
        // given
        val tx = transaction("1000", asset, TransactionType.MOBILE_TOP_UP)

        // when
        val quote = rule.calculate(tx)

        // then
        assertThat(quote.description).isEqualTo("Standard fee rate of 0.15%")
    }

    @Test
    fun `uses the configured rate for fee and description`() {
        // given
        val configured = MobileTopUpFeeRule(MobileTopUpFeeProperties(BigDecimal("0.01")))
        val tx = transaction("1000", Asset.EUR, TransactionType.MOBILE_TOP_UP)

        // when
        val quote = configured.calculate(tx)

        // then
        assertThat(quote.fee).isEqualByComparingTo("10")
        assertThat(quote.rate).isEqualByComparingTo("0.01")
        assertThat(quote.description).isEqualTo("Standard fee rate of 1%")
    }
}
