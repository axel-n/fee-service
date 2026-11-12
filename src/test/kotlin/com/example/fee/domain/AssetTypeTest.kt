package com.example.fee.domain

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.junit.jupiter.params.provider.NullAndEmptySource
import org.junit.jupiter.params.provider.ValueSource

class AssetTypeTest {

    @Test
    fun `parses FIAT`() {
        // given
        val raw = "FIAT"

        // when
        val assetType = AssetType.fromValue(raw)

        // then
        assertThat(assetType).isEqualTo(AssetType.FIAT)
    }

    @ParameterizedTest
    @EnumSource(AssetType::class, mode = EnumSource.Mode.EXCLUDE, names = ["UNKNOWN"])
    fun `every known asset type round-trips through its value`(assetType: AssetType) {
        // given
        val raw = assetType.value

        // when
        val parsed = AssetType.fromValue(raw)

        // then
        assertThat(parsed).isEqualTo(assetType)
    }

    @ParameterizedTest(name = "''{0}'' is UNKNOWN")
    @NullAndEmptySource
    @ValueSource(strings = ["CRYPTO", "fiat", " FIAT", "UNKNOWN"])
    fun `unknown or null input is UNKNOWN without throwing`(raw: String?) {
        // given
        val input = raw

        // when
        val assetType = AssetType.fromValue(input)

        // then
        assertThat(assetType).isEqualTo(AssetType.UNKNOWN)
    }
}
