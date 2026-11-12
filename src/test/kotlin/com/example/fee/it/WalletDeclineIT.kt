package com.example.fee.it

import com.example.fee.contract.FeeRequest
import com.example.fee.restate.FeeOutcome
import com.example.fee.restate.FeeRecordStatus
import com.example.fee.restate.FeeWorkflow
import com.example.fee.restate.WorkflowStep
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.web.server.LocalServerPort
import java.math.BigDecimal
import java.util.UUID

/**
 * The wallet declines the charge: inside the CHARGE `ctx.run` the decline becomes a `TerminalException`, which Restate
 * journals instead of retrying (D33, D51). The workflow records the fee as `FAILED` and returns `Failed`, which the API
 * answers with 422. Resending the same payload attaches to that finished run (D8, D30), so the caller gets the same
 * `Failed` and the wallet is not asked again.
 */
class WalletDeclineIT @Autowired constructor(
    private val wallet: TestWalletGateway,
    private val validator: CountingValidator,
    private val calculator: CountingFeeCalculator,
    @LocalServerPort apiPort: Int,
) : RestateIntegrationTest() {

    private val api = FeeApi(apiPort)

    @Test
    fun `a declined charge is recorded as failed, answered with 422 and not retried on resubmission`() {
        // given: the wallet refuses every charge of this transaction
        val transactionId = "it-decline-${UUID.randomUUID().toString().take(13)}"
        val request = validRequest(transactionId)
        val chargeKey = FeeWorkflow.chargeKey(transactionId)
        wallet.declineCharges(chargeKey, REASON)

        // when
        val response = api.submit(bodyOf(request))

        // then: 422 naming the transaction and the wallet's reason
        assertThat(response.status).describedAs("POST status, body %s", response.body).isEqualTo(422)
        assertThat(response.body["transaction_id"].asText()).isEqualTo(transactionId)
        assertThat(response.body["reason"].asText()).isEqualTo(REASON)

        // then: the workflow returned Failed with the wallet's reason
        assertThat(StoredWorkflows.step(request)).isEqualTo(WorkflowStep.DONE)
        assertThat(StoredWorkflows.outcome(request)).isEqualTo(FeeOutcome.Failed(REASON))

        // then: the wallet was asked once (a decline is terminal, not retried) and charged nothing
        assertThat(wallet.callCount(chargeKey)).describedAs("CHARGE executions").isEqualTo(1)
        assertThat(wallet.chargeCount(chargeKey)).describedAs("charges").isEqualTo(0)
        assertThat(validator.count(transactionId)).describedAs("VALIDATE executions").isEqualTo(1)
        assertThat(calculator.count(transactionId)).describedAs("CALCULATE executions").isEqualTo(1)

        // then: the record is FAILED with the reason, also through the API
        val record = StoredFeeRecords.of(transactionId)
        assertThat(record?.status).isEqualTo(FeeRecordStatus.FAILED)
        assertThat(record?.failureReason).isEqualTo(REASON)
        assertThat(record?.response).isNull()
        val status = api.status(transactionId)
        assertThat(status.status).describedAs("GET status, body %s", status.body).isEqualTo(200)
        assertThat(status.body["status"].asText()).isEqualTo("FAILED")
        assertThat(status.body["failure_reason"].asText()).isEqualTo(REASON)

        // when: the same payload is sent again
        val again = api.submit(bodyOf(request))

        // then: the same Failed result, and no step of the workflow ran again
        assertThat(again.status).describedAs("resubmission status, body %s", again.body).isEqualTo(422)
        assertThat(again.body["transaction_id"].asText()).isEqualTo(transactionId)
        assertThat(again.body["reason"].asText()).isEqualTo(REASON)
        assertThat(StoredWorkflows.outcome(request)).isEqualTo(FeeOutcome.Failed(REASON))
        assertThat(wallet.callCount(chargeKey)).describedAs("CHARGE executions after the resubmission").isEqualTo(1)
        assertThat(wallet.chargeCount(chargeKey)).describedAs("charges after the resubmission").isEqualTo(0)
        assertThat(validator.count(transactionId)).describedAs("VALIDATE executions after the resubmission").isEqualTo(1)
        assertThat(calculator.count(transactionId)).describedAs("CALCULATE executions after the resubmission")
            .isEqualTo(1)
        assertThat(StoredFeeRecords.of(transactionId)?.status).isEqualTo(FeeRecordStatus.FAILED)
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

    private companion object {
        const val REASON = "insufficient funds (simulated)"
    }
}
