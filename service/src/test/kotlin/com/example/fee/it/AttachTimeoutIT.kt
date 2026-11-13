package com.example.fee.it

import com.example.fee.contract.FeeRequest
import com.example.fee.record.FeeRecordStatus
import com.example.fee.workflow.WorkflowStep
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.web.server.LocalServerPort
import java.math.BigDecimal
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/**
 * A `POST` that outlasts `restate.attach-timeout` while its workflow is held inside its CALCULATE journaled step: Restate
 * answers 470 (not ready) for the run's output, so the `POST` answers 504 `WORKFLOW_TIMEOUT`, and the record claimed at
 * CLAIM is `IN_PROGRESS`. Once the calculation is let go, resending the same payload returns the charged fee,
 * calculated once.
 */
class AttachTimeoutIT
    @Autowired
    constructor(
        private val calculator: CountingFeeCalculator,
        @LocalServerPort apiPort: Int,
    ) : RestateIntegrationTest() {
        private val api = FeeApi(apiPort)
        private val blockedIds = mutableListOf<String>()

        @AfterEach
        fun releaseBlockedCalculations() {
            // a failed test must not leave a thread waiting for the other ITs
            blockedIds.forEach(calculator::release)
        }

        @Test
        fun `a POST that outlasts the attach timeout is 504 WORKFLOW_TIMEOUT and the resent payload gets the fee`() {
            // given: the calculation of this transaction is held, and a request is on its way into it
            val transactionId = "it-timeout-${UUID.randomUUID().toString().take(13)}"
            val request = validRequest(transactionId)
            calculator.block(transactionId)
            blockedIds += transactionId
            val post = CompletableFuture.supplyAsync { api.submit(bodyOf(request)) }
            awaitUntil("CALCULATE has started") { calculator.count(transactionId) == 1 }
            assertThat(StoredWorkflows.step(request)).isEqualTo(WorkflowStep.CALCULATE)

            // when: the POST outlasts the attach timeout while CALCULATE is still blocked
            val timedOut = post.get(2, TimeUnit.MINUTES)

            // then: a 504 problem naming the transaction, and no fee yet; the id is claimed
            assertThat(timedOut.status).describedAs("POST status, body %s", timedOut.body).isEqualTo(504)
            assertThat(timedOut.body["code"].asText()).isEqualTo("WORKFLOW_TIMEOUT")
            assertThat(timedOut.body["transaction_id"].asText()).isEqualTo(transactionId)
            assertThat(timedOut.body.has("fee")).describedAs("fee present in %s", timedOut.body).isFalse()
            assertThat(StoredFeeRecords.of(transactionId)?.status).isEqualTo(FeeRecordStatus.IN_PROGRESS)

            // when: the calculation is let go, the workflow finishes, and the same payload is sent again
            calculator.release(transactionId)
            awaitUntil("the workflow has returned") { StoredWorkflows.step(request) == WorkflowStep.DONE }
            val charged = api.submit(bodyOf(request))

            // then: the charged fee, with exactly the fields of the requirements' expected response payload
            assertThat(charged.status).describedAs("POST status, body %s", charged.body).isEqualTo(200)
            assertThat(
                charged.body
                    .fieldNames()
                    .asSequence()
                    .toList(),
            ).containsExactlyElementsOf(FeeApi.FEE_FIELDS)
            assertThat(charged.body["transaction_id"].asText()).isEqualTo(transactionId)
            assertThat(charged.body["amount"].decimalValue()).isEqualByComparingTo(BigDecimal("1000"))
            assertThat(charged.body["asset"].asText()).isEqualTo("USD")
            assertThat(charged.body["type"].asText()).isEqualTo("Mobile Top Up")
            assertThat(charged.body["fee"].decimalValue()).isEqualByComparingTo(BigDecimal("1.50"))
            assertThat(charged.body["rate"].decimalValue()).isEqualByComparingTo(BigDecimal("0.0015"))
            assertThat(charged.body["description"].asText()).isEqualTo("Standard fee rate of 0.15%")

            // then: calculated once, and the record agrees
            assertThat(calculator.count(transactionId)).describedAs("CALCULATE executions").isEqualTo(1)
            assertThat(StoredFeeRecords.of(transactionId)?.status).isEqualTo(FeeRecordStatus.COMPLETED)
        }

        private fun bodyOf(request: FeeRequest): Map<String, Any?> =
            mapOf(
                "transaction_id" to request.transactionId,
                "amount" to request.amount,
                "asset" to request.asset,
                "asset_type" to request.assetType,
                "type" to request.type,
                "state" to request.state,
                "created_at" to request.createdAt,
            )

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
    }
