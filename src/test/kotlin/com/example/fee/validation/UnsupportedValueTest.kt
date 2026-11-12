package com.example.fee.validation

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class UnsupportedValueTest {

    @Test
    fun `names the field, the rejected value and the supported values in order`() {
        // given
        val supported = listOf("USD", "EUR")

        // when
        val error = UnsupportedValue.error("asset", "BTC", supported)

        // then
        assertThat(error).isEqualTo(
            ValidationError("asset", "BTC", "Unsupported asset 'BTC'. Supported: 'USD', 'EUR'", ValidationError.Kind.UNSUPPORTED),
        )
    }

    @Test
    fun `shortens a long rejected value in the message but keeps it whole in the value`() {
        // given
        val raw = "X".repeat(ValueDisplay.MAX_MESSAGE_LENGTH + 10)

        // when
        val error = UnsupportedValue.error("type", raw, listOf("Mobile Top Up"))

        // then
        assertThat(error.value).isEqualTo(raw)
        assertThat(error.message)
            .isEqualTo("Unsupported type '${"X".repeat(ValueDisplay.MAX_MESSAGE_LENGTH)}…'. Supported: 'Mobile Top Up'")
    }
}
