package com.example.fee.domain

import org.assertj.core.api.Assertions.assertThat
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
