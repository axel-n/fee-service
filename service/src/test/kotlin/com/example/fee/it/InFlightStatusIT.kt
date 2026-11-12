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
 * A `POST` that outlasts `restate.attach-timeout` while its workflow is held inside its CALCULATE journaled step: it answers
 * 202 with the `IN_PROGRESS` status, and the record claimed at CLAIM is `IN_PROGRESS`. Once the calculation is let go,
 * resending the same payload returns the charged fee, calculated once.
 */
class InFlightStatusIT @Autowired constructor(
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
    fun `a POST that outlasts the attach timeout is 202 IN_PROGRESS and the resent payload gets the fee`() {
        // given: the calculation of this transaction is held, and a request is on its way into it
        val transactionId = "it-inflight-${UUID.randomUUID().toString().take(13)}"
        val request = validRequest(transactionId)
        calculator.block(transactionId)
        blockedIds += transactionId
        val post = CompletableFuture.supplyAsync { api.submit(bodyOf(request)) }
        awaitUntil("CALCULATE has started") { calculator.count(transactionId) == 1 }
        assertThat(StoredWorkflows.step(request)).isEqualTo(WorkflowStep.CALCULATE)

        // when: the POST outlasts the attach timeout while CALCULATE is still blocked
        val accepted = post.get(2, TimeUnit.MINUTES)

        // then: 202 with the IN_PROGRESS status and no fee yet; the id is claimed
        assertThat(accepted.status).describedAs("POST status, body %s", accepted.body).isEqualTo(202)
        assertThat(accepted.body["transaction_id"].asText()).isEqualTo(transactionId)
        assertThat(accepted.body["status"].asText()).isEqualTo("IN_PROGRESS")
        assertThat(accepted.body.has("fee")).describedAs("fee present in %s", accepted.body).isFalse()
        assertThat(StoredFeeRecords.of(transactionId)?.status).isEqualTo(FeeRecordStatus.IN_PROGRESS)

        // when: the calculation is let go, the workflow finishes, and the same payload is sent again
        calculator.release(transactionId)
        awaitUntil("the workflow has returned") { StoredWorkflows.step(request) == WorkflowStep.DONE }
        val completed = api.submit(bodyOf(request))

        // then: the COMPLETED status with the charged fee
        assertThat(completed.status).describedAs("POST status, body %s", completed.body).isEqualTo(200)
        assertThat(completed.body["status"].asText()).isEqualTo("COMPLETED")
        assertThat(completed.body["transaction_id"].asText()).isEqualTo(transactionId)
        assertThat(completed.body["amount"].decimalValue()).isEqualByComparingTo(BigDecimal("1000"))
        assertThat(completed.body["asset"].asText()).isEqualTo("USD")
        assertThat(completed.body["type"].asText()).isEqualTo("Mobile Top Up")
        assertThat(completed.body["fee"].decimalValue()).isEqualByComparingTo(BigDecimal("1.50"))
        assertThat(completed.body["rate"].decimalValue()).isEqualByComparingTo(BigDecimal("0.0015"))
        assertThat(completed.body["description"].asText()).isEqualTo("Standard fee rate of 0.15%")

        // then: calculated once, and the record agrees
        assertThat(calculator.count(transactionId)).describedAs("CALCULATE executions").isEqualTo(1)
        assertThat(StoredFeeRecords.of(transactionId)?.status).isEqualTo(FeeRecordStatus.COMPLETED)
    }

    private fun bodyOf(request: FeeRequest): Map<String, Any?> = mapOf(
        "transaction_id" to request.transactionId,
        "amount" to request.amount,
        "asset" to request.asset,
        "asset_type" to request.assetType,
        "type" to request.type,
        "state" to request.state,
        "created_at" to request.createdAt,
    )

    private fun validRequest(transactionId: String) = FeeRequest(
        transactionId = transactionId,
        amount = BigDecimal("1000"),
        asset = "USD",
        assetType = "FIAT",
        type = "Mobile Top Up",
        state = "SETTLED - PENDING FEE",
        createdAt = "2023-08-30 15:42:17.610059",
    )
}
