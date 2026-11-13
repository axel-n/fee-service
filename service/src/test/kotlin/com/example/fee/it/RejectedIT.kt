package com.example.fee.it

import com.example.fee.contract.FeeRequest
import com.example.fee.record.FeeRecordStatus
import com.example.fee.validation.ValidationError
import com.example.fee.validation.ValidationError.Kind.INVALID
import com.example.fee.validation.ValidationError.Kind.UNSUPPORTED
import com.example.fee.workflow.FeeOutcome
import com.example.fee.workflow.WorkflowStep
import dev.restate.client.SendResponse
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.web.server.LocalServerPort
import java.math.BigDecimal
import java.util.UUID

/**
 * Rejections end to end. Through the API, `FeeService` validates the request before any call to Postgres or Restate:
 * a rejected payload starts no workflow and asks Postgres nothing, so the same id with a corrected payload is charged
 * right away. Sent past the app, straight through Restate's ingress, the same payload is rejected by the workflow's own
 * VALIDATE step, which returns `FeeOutcome.Rejected` before CLAIM: the workflow never trusts its input.
 */
class RejectedIT
    @Autowired
    constructor(
        private val calculator: CountingFeeCalculator,
        private val records: FaultInjectingFeeRecordRepository,
        @LocalServerPort apiPort: Int,
    ) : RestateIntegrationTest() {
        private val api = FeeApi(apiPort)

        /**
         * One rejected payload: [broken] turns the valid request into the rejected one, which fails with [status] on
         * [field] (value [value]); the message is [message] exactly, or starts with it when [messageIsPrefix].
         */
        data class Case(
            val name: String,
            val field: String,
            val value: String?,
            val status: Int,
            val kind: ValidationError.Kind,
            val message: String,
            val messageIsPrefix: Boolean = false,
            val broken: (FeeRequest) -> FeeRequest,
        ) {
            override fun toString() = name

            fun assertMessage(actual: String) {
                if (messageIsPrefix) assertThat(actual).startsWith(message) else assertThat(actual).isEqualTo(message)
            }
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("cases")
        fun `a rejected payload reaches neither Postgres nor Restate, and the corrected payload with the same id is charged`(case: Case) {
            // given
            val transactionId = newTransactionId()
            val valid = validRequest(transactionId)
            val rejected = case.broken(valid)

            // when
            val rejection = api.submit(bodyOf(rejected))

            // then: the API answers with the validator's problem
            assertThat(rejection.status).describedAs("POST status, body %s", rejection.body).isEqualTo(case.status)
            val problem = rejection.body
            assertThat(problem["field"].asText()).isEqualTo(case.field)
            if (case.value == null) {
                assertThat(problem["value"].isNull).describedAs("value of %s", problem).isTrue()
            } else {
                assertThat(problem["value"].asText()).isEqualTo(case.value)
            }
            assertThat(problem["detail"].asText()).isEqualTo(problem["message"].asText())
            case.assertMessage(problem["message"].asText())

            // then: the gateway rejected it: no workflow started, Postgres never asked, nothing calculated or recorded
            assertThat(StoredWorkflows.step(rejected)).describedAs("workflow step").isNull()
            assertThat(records.callCount(transactionId)).describedAs("Postgres calls").isZero()
            assertThat(calculator.count(transactionId)).isZero()
            assertThat(StoredFeeRecords.of(transactionId)).isNull()

            // when: the same id with the corrected payload
            val corrected = api.submit(bodyOf(valid))

            // then: the rejection didn't use the id up: it is charged, once
            assertThat(corrected.status).describedAs("POST status, body %s", corrected.body).isEqualTo(200)
            assertThat(corrected.body["fee"].decimalValue()).isEqualByComparingTo(BigDecimal("1.50"))
            assertThat(StoredWorkflows.outcome(valid)).isInstanceOf(FeeOutcome.Charged::class.java)
            assertThat(calculator.count(transactionId)).describedAs("CALCULATE executions").isEqualTo(1)
            assertThat(StoredFeeRecords.of(transactionId)?.status).isEqualTo(FeeRecordStatus.COMPLETED)
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("cases")
        fun `a rejected payload sent past the app is rejected by the workflow itself, before CLAIM`(case: Case) {
            // given
            val transactionId = newTransactionId()
            val rejected = case.broken(validRequest(transactionId))

            // when: straight through Restate's ingress, as an operator or another service could
            val sent = StoredWorkflows.submit(rejected)
            awaitUntil("the run of $transactionId has finished") { StoredWorkflows.outcome(rejected) != null }

            // then: VALIDATE returned the same rejection as a business result
            assertThat(sent.sendStatus()).isEqualTo(SendResponse.SendStatus.ACCEPTED)
            assertThat(StoredWorkflows.step(rejected)).isEqualTo(WorkflowStep.DONE)
            val outcome = StoredWorkflows.outcome(rejected)
            assertThat(outcome).isInstanceOf(FeeOutcome.Rejected::class.java)
            val error = (outcome as FeeOutcome.Rejected).error
            assertThat(error.field).isEqualTo(case.field)
            assertThat(error.value).isEqualTo(case.value)
            assertThat(error.kind).isEqualTo(case.kind)
            case.assertMessage(error.message)

            // then: it stopped before CLAIM: Postgres never asked, nothing calculated or recorded
            assertThat(records.callCount(transactionId)).describedAs("Postgres calls").isZero()
            assertThat(calculator.count(transactionId)).isZero()
            assertThat(StoredFeeRecords.of(transactionId)).isNull()
        }

        @Test
        fun `a malformed transaction_id is rejected by the gateway before any workflow starts`() {
            // given: valid characters, so Restate could key on it, but one too many of them
            val transactionId = newTransactionId().padEnd(65, 'x')
            val rejected = validRequest(transactionId)

            // when
            val rejection = api.submit(bodyOf(rejected))

            // then: the value in full, the message shortened to 64 characters
            assertThat(rejection.status).describedAs("POST status, body %s", rejection.body).isEqualTo(400)
            assertThat(rejection.body["field"].asText()).isEqualTo("transaction_id")
            assertThat(rejection.body["value"].asText()).isEqualTo(transactionId)
            assertThat(rejection.body["message"].asText()).isEqualTo(
                "Invalid transaction_id '${transactionId.take(64)}…'. Allowed: 1 to 64 characters, " +
                    "each a letter (A-Z, a-z), a digit, '_' or '-'",
            )
            assertThat(StoredWorkflows.step(rejected)).isNull()
            assertThat(records.callCount(transactionId)).describedAs("Postgres calls").isZero()
            assertThat(StoredFeeRecords.of(transactionId)).isNull()
            assertThat(calculator.count(transactionId)).isZero()
        }

        private fun newTransactionId() = "it-rejected-${UUID.randomUUID().toString().take(13)}"

        // Only the fields that are set, so a missing field is absent from the JSON, as a client would leave it out
        private fun bodyOf(request: FeeRequest): Map<String, Any?> =
            mapOf(
                "transaction_id" to request.transactionId,
                "amount" to request.amount,
                "asset" to request.asset,
                "asset_type" to request.assetType,
                "type" to request.type,
                "state" to request.state,
                "created_at" to request.createdAt,
            ).filterValues { it != null }

        companion object {
            private fun validRequest(transactionId: String) =
                FeeRequest(
                    transactionId = transactionId,
                    amount = BigDecimal("1000"),
                    asset = "USD",
                    assetType = "FIAT",
                    type = "Mobile Top Up",
                    state = "SETTLED - PENDING FEE",
                    createdAt = "2023-08-30 15:42:17.610059",
                )

            // Cases across the validators, in @Order order; the transaction_id has its own test above
            @JvmStatic
            fun cases(): List<Case> =
                listOf(
                    Case(
                        "required field missing",
                        "asset",
                        null,
                        400,
                        INVALID,
                        "Field 'asset' is required",
                    ) { it.copy(asset = null) },
                    Case(
                        "required field blank",
                        "state",
                        "  ",
                        400,
                        INVALID,
                        "Field 'state' is required but was blank: '  '",
                    ) { it.copy(state = "  ") },
                    Case(
                        "amount not positive",
                        "amount",
                        "0",
                        400,
                        INVALID,
                        "Invalid amount '0'. Allowed: a number greater than 0",
                    ) { it.copy(amount = BigDecimal("0")) },
                    Case(
                        "created_at malformed",
                        "created_at",
                        "2023-08-30T15:42:17",
                        400,
                        INVALID,
                        "Invalid created_at '2023-08-30T15:42:17'. Allowed: 'yyyy-MM-dd HH:mm:ss' with 0 to 9 fraction " +
                            "digits, e.g. '2023-08-30 15:42:17.610059'",
                    ) { it.copy(createdAt = "2023-08-30T15:42:17") },
                    Case(
                        "created_at in the future",
                        "created_at",
                        "2999-01-01 00:00:00",
                        400,
                        INVALID,
                        "Invalid created_at '2999-01-01 00:00:00': it is in the future. Allowed: up to now, ",
                        messageIsPrefix = true,
                    ) { it.copy(createdAt = "2999-01-01 00:00:00") },
                    Case(
                        "state not supported",
                        "state",
                        "PENDING",
                        422,
                        UNSUPPORTED,
                        "Unsupported state 'PENDING'. Supported: 'SETTLED - PENDING FEE'",
                    ) { it.copy(state = "PENDING") },
                    Case(
                        "asset not supported",
                        "asset",
                        "BTC",
                        422,
                        UNSUPPORTED,
                        "Unsupported asset 'BTC'. Supported: 'USD', 'EUR'",
                    ) { it.copy(asset = "BTC") },
                    Case(
                        "asset_type not supported",
                        "asset_type",
                        "CRYPTO",
                        422,
                        UNSUPPORTED,
                        "Unsupported asset_type 'CRYPTO'. Supported: 'FIAT'",
                    ) { it.copy(assetType = "CRYPTO") },
                    Case(
                        "type not supported",
                        "type",
                        "Refund",
                        422,
                        UNSUPPORTED,
                        "Unsupported type 'Refund'. Supported: 'Mobile Top Up', 'Bank Transfer', 'Merchant Payment', " +
                            "'Cash Withdrawal'",
                    ) { it.copy(type = "Refund") },
                    Case(
                        "amount has too many decimals",
                        "amount",
                        "10.001",
                        422,
                        UNSUPPORTED,
                        "Amount 10.001 has 3 decimals. USD allows 2",
                    ) { it.copy(amount = BigDecimal("10.001")) },
                )
        }
    }
