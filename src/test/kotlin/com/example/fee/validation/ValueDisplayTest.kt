package com.example.fee.validation

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.math.BigDecimal

class ValueDisplayTest {

    @ParameterizedTest(name = "{0} is shown as {1}")
    @CsvSource("1E+3, 1000", "-1.50, -1.50", "0E-4, 0.0000", "-5, -5")
    fun `shows an amount as a plain number keeping its scale`(raw: String, expected: String) {
        // given
        val amount = BigDecimal(raw)

        // when
        val shown = ValueDisplay.value(amount)

        // then
        assertThat(shown).isEqualTo(expected)
    }

    @Test
    fun `shows an amount beyond the plain bound in scientific form`() {
        // given
        val amount = BigDecimal("-1E+999999999")

        // when
        val shown = ValueDisplay.value(amount)

        // then
        assertThat(shown).isEqualTo("-1E+999999999")
    }

    @Test
    fun `quotes a short value as it is`() {
        // given
        val raw = "txn 001"

        // when
        val quoted = ValueDisplay.quoted(raw)

        // then
        assertThat(quoted).isEqualTo("'txn 001'")
    }

    @Test
    fun `cuts a long value to the message length`() {
        // given
        val raw = "a".repeat(ValueDisplay.MAX_MESSAGE_LENGTH) + "bbb"

        // when
        val quoted = ValueDisplay.quoted(raw)

        // then
        assertThat(quoted).isEqualTo("'${"a".repeat(ValueDisplay.MAX_MESSAGE_LENGTH)}…'")
    }

    @Test
    fun `keeps a short value as it is without quotes`() {
        // given
        val raw = "10.001"

        // when
        val shortened = ValueDisplay.shortened(raw)

        // then
        assertThat(shortened).isEqualTo("10.001")
    }

    @Test
    fun `shortens a long value without quotes`() {
        // given
        val raw = "0." + "0".repeat(ValueDisplay.MAX_MESSAGE_LENGTH) + "1"

        // when
        val shortened = ValueDisplay.shortened(raw)

        // then
        assertThat(shortened).isEqualTo("${raw.take(ValueDisplay.MAX_MESSAGE_LENGTH)}…")
    }
}
