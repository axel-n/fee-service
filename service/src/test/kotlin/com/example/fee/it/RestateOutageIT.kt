package com.example.fee.it

import com.example.fee.contract.FeeRequest
import com.example.fee.record.FeeRecordStatus
import com.example.fee.workflow.FeeOutcome
import com.example.fee.workflow.WorkflowStep
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.web.server.LocalServerPort
import java.math.BigDecimal
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/**
 * Restate goes away and comes back. The outage is a frozen Restate container (SIGSTOP): connections are accepted,
 * nothing answers. A request that Restate never acknowledged gets a 503 and can be sent again safely; a workflow
 * that was already running when Restate froze completes once it thaws, with one fee record.
 */
class RestateOutageIT @Autowired constructor(
    private val calculator: CountingFeeCalculator,
    @LocalServerPort apiPort: Int,
) : RestateIntegrationTest() {

    private val restate = RestateTestEnvironment
    private val api = FeeApi(apiPort)
    private val blockedIds = mutableListOf<String>()

    @AfterEach
    fun restoreRestate() {
        // a failed test must not leave Restate frozen or a thread waiting for the other ITs
        restate.thaw()
        blockedIds.forEach(calculator::release)
    }

    @Test
    fun `a request while Restate is frozen gets 503 and the same payload is charged once Restate thaws`() {
        // given
        val transactionId = "it-outage-${UUID.randomUUID().toString().take(13)}"
        val request = validRequest(transactionId)
        restate.freeze()
        assertThat(restateIsUnreachable()).describedAs("Restate frozen").isTrue()

        // when
        val duringOutage = api.submit(bodyOf(request))

        // then: Restate never acknowledged the submit within the attach timeout → 503, nothing calculated
        assertThat(duringOutage.status).describedAs("POST status, body %s", duringOutage.body).isEqualTo(503)
        assertThat(duringOutage.body["status"].asInt()).isEqualTo(503)
        assertThat(calculator.count(transactionId)).describedAs("calculations during the outage").isEqualTo(0)

        // when
        restate.thaw()
        val resubmitted = api.submit(bodyOf(request))

        // then: the resubmission is charged; a submit Restate read only after the thaw is the same workflow
        assertThat(resubmitted.status).describedAs("resubmission status, body %s", resubmitted.body).isEqualTo(200)
        assertThat(resubmitted.body["transaction_id"].asText()).isEqualTo(transactionId)
        assertThat(resubmitted.body["fee"].decimalValue()).isEqualByComparingTo(BigDecimal("1.50"))
        assertThat(StoredWorkflows.outcome(request)).isInstanceOf(FeeOutcome.Charged::class.java)
        assertThat(StoredFeeRecords.of(transactionId)?.status).isEqualTo(FeeRecordStatus.COMPLETED)
        assertThat(calculator.count(transactionId)).describedAs("CALCULATE executions").isEqualTo(1)
    }

    @Test
    fun `a workflow running when Restate freezes completes with one fee record once Restate thaws`() {
        // given: the workflow is inside CALCULATE, held there
        val transactionId = "it-outage-${UUID.randomUUID().toString().take(13)}"
        val request = validRequest(transactionId)
        calculator.block(transactionId)
        blockedIds += transactionId
        val post = CompletableFuture.supplyAsync { api.submit(bodyOf(request)) }
        awaitUntil("CALCULATE has started") { calculator.count(transactionId) == 1 }
        assertThat(StoredWorkflows.step(request)).isEqualTo(WorkflowStep.CALCULATE)

        // when: Restate freezes, and CALCULATE finishes while it is frozen
        restate.freeze()
        calculator.release(transactionId)
        awaitUntil("the calculation has finished") { calculator.finishedCount(transactionId) == 1 }

        // then: Restate can't have taken the result yet
        assertThat(restateIsUnreachable()).describedAs("Restate frozen").isTrue()
        assertThat(post).isNotDone

        // when
        restate.thaw()

        // then: the workflow completes as Charged
        awaitUntil("the workflow has returned", timeout = Duration.ofSeconds(90)) { StoredWorkflows.outcome(request) != null }
        val outcome = StoredWorkflows.outcome(request)
        assertThat(outcome).isInstanceOf(FeeOutcome.Charged::class.java)
        assertThat((outcome as FeeOutcome.Charged).response.fee).isEqualByComparingTo(BigDecimal("1.50"))
        assertThat(StoredWorkflows.step(request)).isEqualTo(WorkflowStep.DONE)

        // then: the caller got the result, or a 202 if the outage outlasted the attach timeout
        val response = post.get(2, TimeUnit.MINUTES)
        assertThat(response.status).describedAs("POST status, body %s", response.body).isIn(200, 202)
        if (response.status == 200) {
            assertThat(response.body["fee"].decimalValue()).isEqualByComparingTo(BigDecimal("1.50"))
        }

        // then: the record is completed with the fee
        val record = StoredFeeRecords.of(transactionId)
        assertThat(record?.status).isEqualTo(FeeRecordStatus.COMPLETED)
        assertThat(record?.response?.fee).isEqualByComparingTo(BigDecimal("1.50"))
    }

    // A frozen Restate doesn't answer its admin API: the freeze really happened
    private fun restateIsUnreachable(): Boolean =
        runCatching { RestateAdminApi(restate.adminUrl, Duration.ofSeconds(1)).deployments() }.isFailure

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
