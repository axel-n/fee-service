package com.example.fee.it

import com.example.fee.contract.FeeRequest
import com.example.fee.record.FeeRecordStatus
import com.example.fee.restate.RestateEndpointLifecycle
import com.example.fee.workflow.FeeOutcome
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
 * The endpoint stops while a workflow is inside its CALCULATE `ctx.run` and starts again on the same port. The
 * step was not journaled, so Restate retries the invocation on the restarted endpoint and the closure runs again, while
 * the call from before the stop is still waiting. Once released, both calls finish, only the restarted run's result is
 * journaled, and the workflow completes with one fee record.
 */
class EndpointRestartIT @Autowired constructor(
    private val calculator: CountingFeeCalculator,
    private val endpoint: RestateEndpointLifecycle,
    @LocalServerPort apiPort: Int,
) : RestateIntegrationTest() {

    private val api = FeeApi(apiPort)
    private val blockedIds = mutableListOf<String>()

    @AfterEach
    fun restoreSharedEndpoint() {
        // a failed test must not leave a thread waiting or the shared endpoint down for the other ITs
        blockedIds.forEach(calculator::release)
        endpoint.start()
    }

    @Test
    fun `an endpoint restart in the middle of CALCULATE completes the workflow with one fee record`() {
        // given: the calculation of this transaction is held, and a request is on its way into it
        val transactionId = "it-restart-${UUID.randomUUID().toString().take(13)}"
        val request = validRequest(transactionId)
        calculator.block(transactionId)
        blockedIds += transactionId
        val firstPost = CompletableFuture.supplyAsync { api.submit(bodyOf(request)) }
        awaitUntil("CALCULATE has started") { calculator.count(transactionId) == 1 }
        assertThat(StoredWorkflows.step(request)).isEqualTo(WorkflowStep.CALCULATE)
        assertThat(StoredFeeRecords.of(transactionId)?.status).isEqualTo(FeeRecordStatus.IN_PROGRESS)

        // when: the endpoint restarts while CALCULATE is blocked
        endpoint.stop()
        assertThat(endpoint.isRunning).isFalse()
        endpoint.start()
        assertThat(endpoint.isRunning).isTrue()

        // then: Restate retried the invocation on the restarted endpoint, and CALCULATE (not journaled) runs again
        awaitUntil("Restate runs CALCULATE again on the restarted endpoint") { calculator.count(transactionId) >= 2 }
        assertThat(StoredFeeRecords.of(transactionId)?.status).isEqualTo(FeeRecordStatus.IN_PROGRESS)

        // when: both calls go on
        calculator.release(transactionId)

        // then: the workflow completes as Charged
        val first = firstPost.get(2, TimeUnit.MINUTES)
        awaitUntil("the workflow has returned") { StoredWorkflows.outcome(request) != null }
        val outcome = StoredWorkflows.outcome(request)
        assertThat(outcome).isInstanceOf(FeeOutcome.Charged::class.java)
        assertThat((outcome as FeeOutcome.Charged).response.fee).isEqualByComparingTo(BigDecimal("1.50"))
        assertThat(StoredWorkflows.step(request)).isEqualTo(WorkflowStep.DONE)

        // then: the first caller got the result, or a 202 if the restart outlasted the attach timeout
        assertThat(first.status).describedAs("first POST status, body %s", first.body).isIn(200, 202)
        if (first.status == 200) {
            assertThat(first.body["fee"].decimalValue()).isEqualByComparingTo(BigDecimal("1.50"))
        }

        // then: the record is completed with the fee
        val record = StoredFeeRecords.of(transactionId)
        assertThat(record?.status).isEqualTo(FeeRecordStatus.COMPLETED)
        assertThat(record?.response?.fee).isEqualByComparingTo(BigDecimal("1.50"))

        // when: the same payload is sent again
        val calculations = calculator.count(transactionId)
        val again = api.submit(bodyOf(request))

        // then: the same response, and nothing was calculated again
        assertThat(again.status).describedAs("resubmission status, body %s", again.body).isEqualTo(200)
        assertThat(again.body["transaction_id"].asText()).isEqualTo(transactionId)
        assertThat(again.body["fee"].decimalValue()).isEqualByComparingTo(BigDecimal("1.50"))
        assertThat(calculator.count(transactionId)).describedAs("CALCULATE executions after the resubmission")
            .isEqualTo(calculations)
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
