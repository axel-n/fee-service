package com.example.fee.it

import com.example.fee.contract.FeeRequest
import com.example.fee.restate.FeeRecordStatus
import com.example.fee.restate.FeeWorkflow
import com.example.fee.restate.WorkflowStep
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.web.server.LocalServerPort
import java.math.BigDecimal
import java.net.URI
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/**
 * `GET /transaction/fee/{id}` while a workflow is held inside its CHARGE `ctx.run` (D59): the record claimed at CLAIM is
 * `IN_PROGRESS`, and the `POST` that outlasts `restate.attach-timeout` answers 202 with a `Location` pointing at that
 * status. Once the wallet lets the charge go, the same `GET` shows the `COMPLETED` fee, charged once.
 */
class InFlightStatusIT @Autowired constructor(
    private val wallet: TestWalletGateway,
    @LocalServerPort apiPort: Int,
) : RestateIntegrationTest() {

    private val api = FeeApi(apiPort)
    private val blockedKeys = mutableListOf<String>()

    @AfterEach
    fun releaseBlockedCharges() {
        // a failed test must not leave a wallet thread waiting for the other ITs
        blockedKeys.forEach(wallet::releaseCharges)
    }

    @Test
    fun `status is IN_PROGRESS while CHARGE is blocked and COMPLETED after the release`() {
        // given: the wallet holds the charge of this transaction, and a request is on its way into it
        val transactionId = "it-inflight-${UUID.randomUUID().toString().take(13)}"
        val request = validRequest(transactionId)
        val chargeKey = FeeWorkflow.chargeKey(transactionId)
        wallet.blockCharges(chargeKey)
        blockedKeys += chargeKey
        val post = CompletableFuture.supplyAsync { api.submit(bodyOf(request)) }
        awaitUntil("CHARGE has reached the wallet") { wallet.callCount(chargeKey) == 1 }
        assertThat(StoredWorkflows.step(request)).isEqualTo(WorkflowStep.CHARGE)

        // when
        val inFlight = api.status(transactionId)

        // then: IN_PROGRESS, without any fee fields yet
        assertThat(inFlight.status).describedAs("GET status, body %s", inFlight.body).isEqualTo(200)
        assertThat(inFlight.body["transaction_id"].asText()).isEqualTo(transactionId)
        assertThat(inFlight.body["status"].asText()).isEqualTo("IN_PROGRESS")
        assertThat(inFlight.body.has("fee")).describedAs("fee present in %s", inFlight.body).isFalse()
        assertThat(inFlight.body.has("failure_reason")).describedAs("failure_reason in %s", inFlight.body).isFalse()

        // when: the POST outlasts the attach timeout while CHARGE is still blocked
        val accepted = post.get(2, TimeUnit.MINUTES)

        // then: 202 with the IN_PROGRESS status and a Location pointing at it
        assertThat(accepted.status).describedAs("POST status, body %s", accepted.body).isEqualTo(202)
        assertThat(accepted.body["transaction_id"].asText()).isEqualTo(transactionId)
        assertThat(accepted.body["status"].asText()).isEqualTo("IN_PROGRESS")
        assertThat(accepted.location).describedAs("Location header").isNotNull()
        assertThat(URI(accepted.location.orEmpty()).path).isEqualTo("/transaction/fee/$transactionId")
        val stillInFlight = api.status(transactionId)
        assertThat(stillInFlight.body["status"].asText()).describedAs("GET body %s", stillInFlight.body)
            .isEqualTo("IN_PROGRESS")
        assertThat(wallet.chargeCount(chargeKey)).describedAs("charges while blocked").isEqualTo(0)

        // when: the wallet lets the charge go
        wallet.releaseCharges(chargeKey)

        // then: the status turns COMPLETED with the charged fee
        awaitUntil("the status is COMPLETED") { api.status(transactionId).body["status"].asText() == "COMPLETED" }
        val completed = api.status(transactionId)
        assertThat(completed.status).describedAs("GET status, body %s", completed.body).isEqualTo(200)
        assertThat(completed.body["transaction_id"].asText()).isEqualTo(transactionId)
        assertThat(completed.body["amount"].decimalValue()).isEqualByComparingTo(BigDecimal("1000"))
        assertThat(completed.body["asset"].asText()).isEqualTo("USD")
        assertThat(completed.body["type"].asText()).isEqualTo("Mobile Top Up")
        assertThat(completed.body["fee"].decimalValue()).isEqualByComparingTo(BigDecimal("1.50"))
        assertThat(completed.body["rate"].decimalValue()).isEqualByComparingTo(BigDecimal("0.0015"))
        assertThat(completed.body["description"].asText()).isEqualTo("Standard fee rate of 0.15%")
        assertThat(completed.body.has("failure_reason")).describedAs("failure_reason in %s", completed.body).isFalse()

        // then: exactly one charge, and the workflow and the record agree
        assertThat(wallet.chargeCount(chargeKey)).describedAs("charges").isEqualTo(1)
        assertThat(wallet.callCount(chargeKey)).describedAs("CHARGE executions").isEqualTo(1)
        awaitUntil("the workflow has returned") { StoredWorkflows.step(request) == WorkflowStep.DONE }
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
