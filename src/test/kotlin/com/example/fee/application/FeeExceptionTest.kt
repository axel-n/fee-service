package com.example.fee.application

import com.example.fee.validation.ValidationError
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import java.net.ConnectException

class FeeExceptionTest {

    @Test
    fun `a malformed request is a 400 carrying the field, the value and the readable message`() {
        // given
        val error = ValidationError(
            field = "amount",
            value = "-5",
            message = "Amount -5 must be greater than 0",
            kind = ValidationError.Kind.INVALID,
        )

        // when
        val exception = ValidationException(error)

        // then
        assertThat(exception.status).isEqualTo(HttpStatus.BAD_REQUEST)
        assertThat(exception.error).isEqualTo(error)
        assertThat(exception.message).isEqualTo("Amount -5 must be greater than 0")
    }

    @Test
    fun `an unsupported value is a 422 with the allowed values in its message`() {
        // given
        val error = ValidationError(
            field = "state",
            value = "PENDING",
            message = "Unsupported state 'PENDING'. Supported: 'SETTLED - PENDING FEE'",
            kind = ValidationError.Kind.UNSUPPORTED,
        )

        // when
        val exception = ValidationException(error)

        // then
        assertThat(exception.status).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY)
        assertThat(exception.message).isEqualTo("Unsupported state 'PENDING'. Supported: 'SETTLED - PENDING FEE'")
    }

    @Test
    fun `a transaction id claimed by another payload is a 409 naming the id`() {
        // given
        val transactionId = "txn_001"

        // when
        val exception = DuplicateTransactionException(transactionId)

        // then
        assertThat(exception.status).isEqualTo(HttpStatus.CONFLICT)
        assertThat(exception.transactionId).isEqualTo("txn_001")
        assertThat(exception.message).contains("'txn_001'").contains("different payload")
    }

    @Test
    fun `a declined charge is a 422 with the wallet's reason`() {
        // given
        val reason = "insufficient funds"

        // when
        val exception = ChargeFailedException("txn_001", reason)

        // then
        assertThat(exception.status).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY)
        assertThat(exception.reason).isEqualTo("insufficient funds")
        assertThat(exception.message).contains("'txn_001'").endsWith(": insufficient funds")
    }

    @Test
    fun `an unreachable Restate is a 503 that keeps the cause and asks for a retry`() {
        // given
        val cause = ConnectException("Connection refused")

        // when
        val exception = WorkflowUnavailableException(cause)

        // then
        assertThat(exception.status).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE)
        assertThat(exception.cause).isSameAs(cause)
        assertThat(exception.message).contains("Retry")
    }

    @Test
    fun `every kind of fee failure is a fee exception the handler can map`() {
        // given
        val error = ValidationError("state", "PENDING", "Unsupported state 'PENDING'", ValidationError.Kind.UNSUPPORTED)

        // when
        val exceptions: List<FeeException> = listOf(
            ValidationException(error),
            DuplicateTransactionException("txn_001"),
            ChargeFailedException("txn_001", "declined"),
            TransactionNotFoundException("txn_404"),
            WorkflowUnavailableException(),
        )

        // then
        assertThat(exceptions.map { it.status }).containsExactly(
            HttpStatus.UNPROCESSABLE_ENTITY,
            HttpStatus.CONFLICT,
            HttpStatus.UNPROCESSABLE_ENTITY,
            HttpStatus.NOT_FOUND,
            HttpStatus.SERVICE_UNAVAILABLE,
        )
        assertThat(exceptions).allSatisfy { assertThat(it).isInstanceOf(RuntimeException::class.java) }
    }

    @Test
    fun `a transaction id without a fee record is a 404 naming the id`() {
        // given
        val transactionId = "txn_404"

        // when
        val exception = TransactionNotFoundException(transactionId)

        // then
        assertThat(exception.status).isEqualTo(HttpStatus.NOT_FOUND)
        assertThat(exception.transactionId).isEqualTo("txn_404")
        assertThat(exception.message).isEqualTo("No fee record for transaction 'txn_404'")
    }
}
