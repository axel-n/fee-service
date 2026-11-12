package com.example.fee.domain

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.EnumSource
import org.junit.jupiter.params.provider.NullAndEmptySource
import org.junit.jupiter.params.provider.ValueSource

class TransactionTypeTest {

    @ParameterizedTest(name = "''{0}'' is {1}")
    @CsvSource(
        "Mobile Top Up, MOBILE_TOP_UP",
        "Bank Transfer, BANK_TRANSFER",
        "Merchant Payment, MERCHANT_PAYMENT",
        "Cash Withdrawal, CASH_WITHDRAWAL",
    )
    fun `parses the request value of every type`(raw: String, expected: TransactionType) {
        // given
        val input = raw

        // when
        val type = TransactionType.fromValue(input)

        // then
        assertThat(type).isEqualTo(expected)
    }

    @ParameterizedTest
    @EnumSource(TransactionType::class, mode = EnumSource.Mode.EXCLUDE, names = ["UNKNOWN"])
    fun `every known type round-trips through its value`(type: TransactionType) {
        // given
        val raw = type.value

        // when
        val parsed = TransactionType.fromValue(raw)

        // then
        assertThat(parsed).isEqualTo(type)
    }

    @ParameterizedTest(name = "''{0}'' is UNKNOWN")
    @NullAndEmptySource
    @ValueSource(strings = ["Crypto Swap", "mobile top up", "MOBILE_TOP_UP", " Mobile Top Up", "Mobile Top Up ", "UNKNOWN", " "])
    fun `unknown or null input is UNKNOWN without throwing`(raw: String?) {
        // given
        val input = raw

        // when
        val type = TransactionType.fromValue(input)

        // then
        assertThat(type).isEqualTo(TransactionType.UNKNOWN)
    }
}

class TransactionStateTest {

    @Test
    fun `parses SETTLED - PENDING FEE`() {
        // given
        val raw = "SETTLED - PENDING FEE"

        // when
        val state = TransactionState.fromValue(raw)

        // then
        assertThat(state).isEqualTo(TransactionState.SETTLED_PENDING_FEE)
    }

    @ParameterizedTest
    @EnumSource(TransactionState::class, mode = EnumSource.Mode.EXCLUDE, names = ["UNKNOWN"])
    fun `every known state round-trips through its value`(state: TransactionState) {
        // given
        val raw = state.value

        // when
        val parsed = TransactionState.fromValue(raw)

        // then
        assertThat(parsed).isEqualTo(state)
    }

    @ParameterizedTest(name = "''{0}'' is UNKNOWN")
    @NullAndEmptySource
    @ValueSource(strings = ["PENDING", "SETTLED", "settled - pending fee", "SETTLED_PENDING_FEE", " SETTLED - PENDING FEE", "UNKNOWN"])
    fun `unknown or null input is UNKNOWN without throwing`(raw: String?) {
        // given
        val input = raw

        // when
        val state = TransactionState.fromValue(input)

        // then
        assertThat(state).isEqualTo(TransactionState.UNKNOWN)
    }
}

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
