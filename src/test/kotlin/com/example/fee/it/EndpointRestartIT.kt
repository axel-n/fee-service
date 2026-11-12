package com.example.fee.it

import com.example.fee.contract.FeeRequest
import com.example.fee.restate.FeeOutcome
import com.example.fee.restate.FeeRecordStatus
import com.example.fee.restate.FeeWorkflow
import com.example.fee.restate.RestateEndpointLifecycle
import com.example.fee.restate.WorkflowStep
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
 * The endpoint stops while a workflow is inside its CHARGE `ctx.run` and starts again on the same port (D16, D32). The
 * step was not journaled, so Restate retries the invocation on the restarted endpoint and the closure runs again, while
 * the call from before the stop is still waiting in the wallet. Once released, both calls finish, the wallet's idempotency
 * key keeps it to one charge, and the workflow completes.
 */
class EndpointRestartIT @Autowired constructor(
    private val wallet: TestWalletGateway,
    private val endpoint: RestateEndpointLifecycle,
    @LocalServerPort apiPort: Int,
) : RestateIntegrationTest() {

    private val api = FeeApi(apiPort)
    private val blockedKeys = mutableListOf<String>()

    @AfterEach
    fun restoreSharedEndpoint() {
        // a failed test must not leave a wallet thread waiting or the shared endpoint down for the other ITs
        blockedKeys.forEach(wallet::releaseCharges)
        endpoint.start()
    }

    @Test
    fun `an endpoint restart in the middle of CHARGE completes the workflow with one charge`() {
        // given: the wallet holds the charge of this transaction, and a request is on its way into it
        val transactionId = "it-restart-${UUID.randomUUID().toString().take(13)}"
        val request = validRequest(transactionId)
        val chargeKey = FeeWorkflow.chargeKey(transactionId)
        wallet.blockCharges(chargeKey)
        blockedKeys += chargeKey
        val firstPost = CompletableFuture.supplyAsync { api.submit(bodyOf(request)) }
        awaitUntil("CHARGE has reached the wallet") { wallet.callCount(chargeKey) == 1 }
        assertThat(StoredWorkflows.step(request)).isEqualTo(WorkflowStep.CHARGE)
        assertThat(StoredFeeRecords.of(transactionId)?.status).isEqualTo(FeeRecordStatus.IN_PROGRESS)
        assertThat(wallet.chargeCount(chargeKey)).describedAs("charges while blocked").isEqualTo(0)

        // when: the endpoint restarts while CHARGE is blocked
        endpoint.stop()
        assertThat(endpoint.isRunning).isFalse()
        endpoint.start()
        assertThat(endpoint.isRunning).isTrue()

        // then: Restate retried the invocation on the restarted endpoint, and CHARGE (not journaled) runs again
        awaitUntil("Restate runs CHARGE again on the restarted endpoint") { wallet.callCount(chargeKey) >= 2 }
        assertThat(wallet.chargeCount(chargeKey)).describedAs("charges before the release").isEqualTo(0)

        // when: the wallet lets both calls go on
        wallet.releaseCharges(chargeKey)

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

        // then: exactly one charge, and the record is completed with it
        assertThat(wallet.chargeCount(chargeKey)).describedAs("charges").isEqualTo(1)
        assertThat(wallet.receipt(chargeKey)?.amount).isEqualByComparingTo(BigDecimal("1.50"))
        val record = StoredFeeRecords.of(transactionId)
        assertThat(record?.status).isEqualTo(FeeRecordStatus.COMPLETED)
        assertThat(record?.response?.fee).isEqualByComparingTo(BigDecimal("1.50"))

        // when: the same payload is sent again
        val again = api.submit(bodyOf(request))

        // then: the same response, still one charge
        assertThat(again.status).describedAs("resubmission status, body %s", again.body).isEqualTo(200)
        assertThat(again.body["transaction_id"].asText()).isEqualTo(transactionId)
        assertThat(again.body["fee"].decimalValue()).isEqualByComparingTo(BigDecimal("1.50"))
        assertThat(wallet.chargeCount(chargeKey)).describedAs("charges after the resubmission").isEqualTo(1)
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
