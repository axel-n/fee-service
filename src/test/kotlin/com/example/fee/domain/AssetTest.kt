package com.example.fee.domain

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.EnumSource
import org.junit.jupiter.params.provider.NullAndEmptySource
import org.junit.jupiter.params.provider.ValueSource

class AssetTest {

    @ParameterizedTest(name = "''{0}'' is {1} with {2} decimals, {3}")
    @CsvSource(
        "USD, USD, 2, FIAT",
        "EUR, EUR, 2, FIAT",
    )
    fun `parses every supported asset with its decimals and asset type`(
        raw: String,
        expected: Asset,
        decimals: Int,
        assetType: AssetType,
    ) {
        // given
        val input = raw

        // when
        val asset = Asset.fromValue(input)

        // then
        assertThat(asset).isEqualTo(expected)
        assertThat(asset.decimals).isEqualTo(decimals)
        assertThat(asset.assetType).isEqualTo(assetType)
    }

    @ParameterizedTest
    @EnumSource(Asset::class, mode = EnumSource.Mode.EXCLUDE, names = ["UNKNOWN"])
    fun `every known asset round-trips through its value`(asset: Asset) {
        // given
        val raw = asset.value

        // when
        val parsed = Asset.fromValue(raw)

        // then
        assertThat(parsed).isEqualTo(asset)
    }

    @ParameterizedTest(name = "''{0}'' is UNKNOWN")
    @NullAndEmptySource
    @ValueSource(strings = ["BTC", "GBP", "usd", " USD", "UNKNOWN"])
    fun `unknown or null input is UNKNOWN without throwing`(raw: String?) {
        // given
        val input = raw

        // when
        val asset = Asset.fromValue(input)

        // then
        assertThat(asset).isEqualTo(Asset.UNKNOWN)
        assertThat(asset.assetType).isEqualTo(AssetType.UNKNOWN)
    }
}
