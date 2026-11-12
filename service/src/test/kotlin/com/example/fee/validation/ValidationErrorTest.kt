package com.example.fee.validation

import com.example.fee.contract.ContractMapper
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.EnumSource
import java.math.BigDecimal

class ValidationErrorTest {

    private val error = ValidationError(
        field = "asset",
        value = "BTC",
        message = "Unsupported asset 'BTC'. Supported: 'USD', 'EUR'",
        kind = ValidationError.Kind.UNSUPPORTED,
    )

    @ParameterizedTest
    @EnumSource(ContractMapper::class)
    fun `writes the pinned field names and the kind by name`(mapper: ContractMapper) {
        // given
        val value = error

        // when
        val json = mapper.write(value, ValidationError::class.java)

        // then
        assertThat(ContractMapper.fieldNames(json)).containsExactlyInAnyOrder("field", "value", "message", "kind")
        assertThat(ContractMapper.tree(json)["kind"].asText()).isEqualTo("UNSUPPORTED")
    }

    @ParameterizedTest
    @EnumSource(ContractMapper::class)
    fun `round-trips an error`(mapper: ContractMapper) {
        // given
        val json = mapper.write(error, ValidationError::class.java)

        // when
        val read = mapper.read(json, ValidationError::class.java)

        // then
        assertThat(read).isEqualTo(error)
    }

    @ParameterizedTest
    @EnumSource(ContractMapper::class)
    fun `round-trips an error for a missing field with a null value`(mapper: ContractMapper) {
        // given
        val missing = ValidationError("amount", null, "Field 'amount' is required", ValidationError.Kind.INVALID)
        val json = mapper.write(missing, ValidationError::class.java)

        // when
        val read = mapper.read(json, ValidationError::class.java)

        // then
        assertThat(read).isEqualTo(missing)
    }

    @ParameterizedTest
    @EnumSource(ContractMapper::class)
    fun `rejects an unknown kind`(mapper: ContractMapper) {
        // given
        val json = """{"field":"asset","value":"BTC","message":"m","kind":"FORBIDDEN"}"""

        // when / then
        assertThatThrownBy { mapper.read(json, ValidationError::class.java) }.isInstanceOf(Exception::class.java)
    }
}

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
