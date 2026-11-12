package com.example.fee.domain

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.junit.jupiter.params.provider.NullAndEmptySource
import org.junit.jupiter.params.provider.ValueSource

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
