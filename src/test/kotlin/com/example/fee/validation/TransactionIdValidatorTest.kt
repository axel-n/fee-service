package com.example.fee.validation

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.core.annotation.Order

class TransactionIdValidatorTest {

    private val validator = TransactionIdValidator()

    @ParameterizedTest(name = "''{0}''")
    @ValueSource(strings = ["txn_001", "a", "TXN-2023-08-30", "3f2b9c1e-7a4d-4e8b-9c0a-1d2e3f4a5b6c"])
    fun `passes a well-formed id`(id: String) {
        // given
        val request = validRequest.copy(transactionId = id)

        // when
        val error = validator.validate(request)

        // then
        assertThat(error).isNull()
    }

    @Test
    fun `passes an id of the maximum length`() {
        // given
        val request = validRequest.copy(transactionId = "x".repeat(TransactionIdValidator.MAX_LENGTH))

        // when
        val error = validator.validate(request)

        // then
        assertThat(error).isNull()
    }

    @ParameterizedTest(name = "''{0}''")
    @ValueSource(strings = ["", " ", "txn 001", " txn_001", "txn:001", "txn/001", "txn.001", "txé", "txn_001\n"])
    fun `rejects a malformed id with a message naming it and the allowed form`(id: String) {
        // given
        val request = validRequest.copy(transactionId = id)

        // when
        val error = validator.validate(request)

        // then
        assertThat(error).isEqualTo(
            ValidationError(
                "transaction_id",
                id,
                "Invalid transaction_id '$id'. Allowed: 1 to 64 characters, each a letter (A-Z, a-z), a digit, '_' or '-'",
                ValidationError.Kind.INVALID,
            ),
        )
    }

    @Test
    fun `rejects a too long id and shortens it in the message`() {
        // given
        val id = "x".repeat(TransactionIdValidator.MAX_LENGTH + 1)
        val request = validRequest.copy(transactionId = id)

        // when
        val error = validator.validate(request)

        // then
        assertThat(error?.value).isEqualTo(id)
        assertThat(error?.kind).isEqualTo(ValidationError.Kind.INVALID)
        assertThat(error?.message).startsWith("Invalid transaction_id '${"x".repeat(64)}…'. Allowed: 1 to 64 characters")
    }

    @Test
    fun `rejects a missing id on its own, for the key pre-check`() {
        // given
        val request = validRequest.copy(transactionId = null)

        // when
        val error = validator.validate(request)

        // then
        assertThat(error).isEqualTo(
            ValidationError("transaction_id", null, "Field 'transaction_id' is required", ValidationError.Kind.INVALID),
        )
    }

    @Test
    fun `runs after the required fields check`() {
        // given
        val type = TransactionIdValidator::class.java

        // when
        val order = type.getAnnotation(Order::class.java)?.value

        // then
        assertThat(order).isEqualTo(ValidationOrder.TRANSACTION_ID)
    }
}
