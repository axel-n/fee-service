package com.example.fee.validation

import com.example.fee.contract.ContractMapper
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource

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
