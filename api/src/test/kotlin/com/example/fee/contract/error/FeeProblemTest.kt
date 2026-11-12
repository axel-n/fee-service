package com.example.fee.contract.error

import com.example.fee.contract.ContractMapper
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class FeeProblemTest {

    private fun read(json: String): FeeProblem = ContractMapper.SPRING.read(json, FeeProblem::class.java)

    @Test
    fun `reads a validation problem with snake_case names and a null value`() {
        // given
        val json = """
            {
              "type": "about:blank",
              "title": "Bad Request",
              "status": 400,
              "detail": "Field 'amount' is required",
              "instance": "/transaction/fee",
              "code": "INVALID_REQUEST",
              "field": "amount",
              "value": null,
              "message": "Field 'amount' is required"
            }
        """.trimIndent()

        // when
        val problem = read(json)

        // then
        assertThat(problem).isEqualTo(
            FeeProblem(
                type = "about:blank",
                title = "Bad Request",
                status = 400,
                detail = "Field 'amount' is required",
                instance = "/transaction/fee",
                code = "INVALID_REQUEST",
                field = "amount",
                value = null,
                message = "Field 'amount' is required",
            ),
        )
    }

    @Test
    fun `ignores properties a later version adds`() {
        // given
        val json = """{"status": 409, "code": "DUPLICATE_TRANSACTION", "transaction_id": "txn_001", "retry_after": 5}"""

        // when
        val problem = read(json)

        // then
        assertThat(problem.transactionId).isEqualTo("txn_001")
        assertThat(problem.errorCode()).isEqualTo(FeeErrorCode.DUPLICATE_TRANSACTION)
    }

    @Test
    fun `rebuilds a rejected request as a validation exception of the same kind`() {
        // given
        val problem = FeeProblem(
            status = 422,
            detail = "Unsupported state 'PENDING'. Supported: 'SETTLED - PENDING FEE'",
            code = "UNSUPPORTED_REQUEST",
            field = "state",
            value = "PENDING",
            message = "Unsupported state 'PENDING'. Supported: 'SETTLED - PENDING FEE'",
        )

        // when
        val exception = problem.toException()

        // then
        assertThat(exception).isInstanceOfSatisfying(ValidationException::class.java) {
            assertThat(it.kind).isEqualTo(ValidationException.Kind.UNSUPPORTED)
            assertThat(it.field).isEqualTo("state")
            assertThat(it.value).isEqualTo("PENDING")
            assertThat(it.message).isEqualTo("Unsupported state 'PENDING'. Supported: 'SETTLED - PENDING FEE'")
            assertThat(it.status).isEqualTo(422)
        }
    }

    @Test
    fun `rebuilds a malformed request with a missing field as a 400 with a null value`() {
        // given
        val problem = FeeProblem(
            status = 400,
            detail = "Field 'amount' is required",
            code = "INVALID_REQUEST",
            field = "amount",
            message = "Field 'amount' is required",
        )

        // when
        val exception = problem.toException()

        // then
        assertThat(exception).isInstanceOfSatisfying(ValidationException::class.java) {
            assertThat(it.kind).isEqualTo(ValidationException.Kind.INVALID)
            assertThat(it.value).isNull()
        }
    }

    @Test
    fun `rebuilds every failure about one transaction with the same message the service sent`() {
        // given
        val originals = listOf(
            DuplicateTransactionException("txn_001"),
            ChargeFailedException("txn_002", "insufficient funds"),
            TransactionNotFoundException("txn_404"),
            WorkflowUnavailableException(),
        )
        val problems = listOf(
            FeeProblem(status = 409, code = "DUPLICATE_TRANSACTION", transactionId = "txn_001"),
            FeeProblem(status = 422, code = "CHARGE_FAILED", transactionId = "txn_002", reason = "insufficient funds"),
            FeeProblem(status = 404, code = "TRANSACTION_NOT_FOUND", transactionId = "txn_404"),
            FeeProblem(status = 503, code = "WORKFLOW_UNAVAILABLE"),
        )

        // when
        val rebuilt = problems.map { it.toException() }

        // then
        assertThat(rebuilt.map { it?.javaClass }).containsExactlyElementsOf(originals.map { it.javaClass })
        assertThat(rebuilt.map { it?.message }).containsExactlyElementsOf(originals.map { it.message })
        assertThat(rebuilt.map { it?.status }).containsExactly(409, 422, 404, 503)
    }

    @Test
    fun `a problem without a known code is not one of the service's exceptions`() {
        // given
        val springError = FeeProblem(status = 415, title = "Unsupported Media Type")
        val laterCode = FeeProblem(status = 429, code = "RATE_LIMITED")

        // when
        val exceptions = listOf(springError.toException(), laterCode.toException())

        // then
        assertThat(exceptions).containsOnlyNulls()
        assertThat(laterCode.errorCode()).isNull()
    }

    @Test
    fun `a problem missing a property its exception needs is not rebuilt`() {
        // given
        val withoutReason = FeeProblem(status = 422, code = "CHARGE_FAILED", transactionId = "txn_002")
        val withoutField = FeeProblem(status = 400, code = "INVALID_REQUEST", message = "Field is required")

        // when
        val exceptions = listOf(withoutReason.toException(), withoutField.toException())

        // then
        assertThat(exceptions).containsOnlyNulls()
    }

    @Test
    fun `writes snake_case names and leaves out absent properties`() {
        // given
        val problem = FeeProblem(status = 409, code = "DUPLICATE_TRANSACTION", transactionId = "txn_001")

        // when
        val json = ContractMapper.SPRING.write(problem, FeeProblem::class.java)

        // then
        assertThat(ContractMapper.fieldNames(json)).containsExactlyInAnyOrder("status", "code", "transaction_id")
    }
}
