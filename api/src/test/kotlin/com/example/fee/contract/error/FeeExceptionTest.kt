package com.example.fee.contract.error

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.entry
import org.junit.jupiter.api.Test
import java.net.ConnectException

class FeeExceptionTest {

    @Test
    fun `a malformed request is a 400 carrying the field, the value and the readable message`() {
        // given
        val message = "Amount -5 must be greater than 0"

        // when
        val exception = ValidationException("amount", "-5", message, ValidationException.Kind.INVALID)

        // then
        assertThat(exception.status).isEqualTo(400)
        assertThat(exception.code).isEqualTo(FeeErrorCode.INVALID_REQUEST)
        assertThat(exception.field).isEqualTo("amount")
        assertThat(exception.value).isEqualTo("-5")
        assertThat(exception.message).isEqualTo("Amount -5 must be greater than 0")
    }

    @Test
    fun `an unsupported value is a 422 with the allowed values in its message`() {
        // given
        val message = "Unsupported state 'PENDING'. Supported: 'SETTLED - PENDING FEE'"

        // when
        val exception = ValidationException("state", "PENDING", message, ValidationException.Kind.UNSUPPORTED)

        // then
        assertThat(exception.status).isEqualTo(422)
        assertThat(exception.code).isEqualTo(FeeErrorCode.UNSUPPORTED_REQUEST)
        assertThat(exception.message).isEqualTo("Unsupported state 'PENDING'. Supported: 'SETTLED - PENDING FEE'")
    }

    @Test
    fun `a missing field is rejected with a null value`() {
        // given
        val message = "Field 'amount' is required"

        // when
        val exception = ValidationException("amount", null, message, ValidationException.Kind.INVALID)

        // then
        assertThat(exception.value).isNull()
        assertThat(exception.status).isEqualTo(400)
    }

    @Test
    fun `a transaction id claimed by another payload is a 409 naming the id`() {
        // given
        val transactionId = "txn_001"

        // when
        val exception = DuplicateTransactionException(transactionId)

        // then
        assertThat(exception.status).isEqualTo(409)
        assertThat(exception.code).isEqualTo(FeeErrorCode.DUPLICATE_TRANSACTION)
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
        assertThat(exception.status).isEqualTo(422)
        assertThat(exception.code).isEqualTo(FeeErrorCode.CHARGE_FAILED)
        assertThat(exception.reason).isEqualTo("insufficient funds")
        assertThat(exception.message).contains("'txn_001'").endsWith(": insufficient funds")
    }

    @Test
    fun `a transaction id without a fee record is a 404 naming the id`() {
        // given
        val transactionId = "txn_404"

        // when
        val exception = TransactionNotFoundException(transactionId)

        // then
        assertThat(exception.status).isEqualTo(404)
        assertThat(exception.code).isEqualTo(FeeErrorCode.TRANSACTION_NOT_FOUND)
        assertThat(exception.transactionId).isEqualTo("txn_404")
        assertThat(exception.message).isEqualTo("No fee record for transaction 'txn_404'")
    }

    @Test
    fun `an unreachable Restate is a 503 that keeps the cause and asks for a retry`() {
        // given
        val cause = ConnectException("Connection refused")

        // when
        val exception = WorkflowUnavailableException(cause)

        // then
        assertThat(exception.status).isEqualTo(503)
        assertThat(exception.code).isEqualTo(FeeErrorCode.WORKFLOW_UNAVAILABLE)
        assertThat(exception.cause).isSameAs(cause)
        assertThat(exception.message).contains("Retry")
    }

    @Test
    fun `every kind of fee failure is a fee exception with its own code`() {
        // given
        val exceptions: List<FeeException> = listOf(
            ValidationException("amount", "-5", "Amount -5 must be greater than 0", ValidationException.Kind.INVALID),
            ValidationException("state", "PENDING", "Unsupported state 'PENDING'", ValidationException.Kind.UNSUPPORTED),
            DuplicateTransactionException("txn_001"),
            ChargeFailedException("txn_001", "declined"),
            TransactionNotFoundException("txn_404"),
            WorkflowUnavailableException(),
        )

        // when
        val codes = exceptions.map { it.code }

        // then
        assertThat(codes).containsExactlyElementsOf(FeeErrorCode.entries)
        assertThat(exceptions.map { it.status }).containsExactly(400, 422, 409, 422, 404, 503)
        assertThat(exceptions).allSatisfy { assertThat(it).isInstanceOf(RuntimeException::class.java) }
    }

    @Test
    fun `the error codes keep their names and statuses, because clients compile against them`() {
        // given
        val codes = FeeErrorCode.entries

        // when
        val byName = codes.associate { it.name to it.status }

        // then
        assertThat(byName).containsExactly(
            entry("INVALID_REQUEST", 400),
            entry("UNSUPPORTED_REQUEST", 422),
            entry("DUPLICATE_TRANSACTION", 409),
            entry("CHARGE_FAILED", 422),
            entry("TRANSACTION_NOT_FOUND", 404),
            entry("WORKFLOW_UNAVAILABLE", 503),
        )
    }
}
