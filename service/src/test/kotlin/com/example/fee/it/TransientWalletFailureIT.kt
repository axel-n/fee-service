package com.example.fee.it

import com.example.fee.contract.FeeRequest
import com.example.fee.record.FeeRecordStatus
import com.example.fee.workflow.FeeOutcome
import com.example.fee.workflow.FeeWorkflow
import com.example.fee.workflow.WorkflowStep
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.web.server.LocalServerPort
import java.math.BigDecimal
import java.util.UUID

/**
 * The wallet fails transiently: a plain exception escaping the CHARGE `ctx.run` is not journaled, so Restate retries it
 * until the wallet answers. The caller still gets `Charged`, the wallet is charged once, and the steps journaled
 * before CHARGE (VALIDATE, CALCULATE) are not run again.
 */
class TransientWalletFailureIT @Autowired constructor(
    private val wallet: TestWalletGateway,
    private val validator: CountingValidator,
    private val calculator: CountingFeeCalculator,
    @LocalServerPort apiPort: Int,
) : RestateIntegrationTest() {

    private val api = FeeApi(apiPort)

    @Test
    fun `a wallet that fails 3 times and then succeeds is retried to exactly one charge`() {
        // given: the wallet fails the first 3 charges of this transaction
        val transactionId = "it-transient-${UUID.randomUUID().toString().take(13)}"
        val request = validRequest(transactionId)
        val chargeKey = FeeWorkflow.chargeKey(transactionId)
        wallet.failCharges(chargeKey, times = FAILURES)

        // when
        val response = api.submit(bodyOf(request))

        // then: the caller gets the charge, within the attach timeout despite the retries
        assertThat(response.status).describedAs("POST status, body %s", response.body).isEqualTo(200)
        assertThat(response.body["transaction_id"].asText()).isEqualTo(transactionId)
        assertThat(response.body["fee"].decimalValue()).isEqualByComparingTo(BigDecimal("1.50"))
        assertThat(StoredWorkflows.step(request)).isEqualTo(WorkflowStep.DONE)
        assertThat(StoredWorkflows.outcome(request)).isInstanceOf(FeeOutcome.Charged::class.java)

        // then: CHARGE ran 3 failing times plus the successful one, and charged once
        assertThat(wallet.failuresLeft(chargeKey)).describedAs("injected failures left").isEqualTo(0)
        assertThat(wallet.callCount(chargeKey)).describedAs("CHARGE executions").isEqualTo(FAILURES + 1)
        assertThat(wallet.chargeCount(chargeKey)).describedAs("charges").isEqualTo(1)
        assertThat(wallet.receipt(chargeKey)?.amount).isEqualByComparingTo(BigDecimal("1.50"))

        // then: the steps journaled before CHARGE were replayed, not run again
        assertThat(validator.count(transactionId)).describedAs("VALIDATE executions").isEqualTo(1)
        assertThat(calculator.count(transactionId)).describedAs("CALCULATE executions").isEqualTo(1)

        // then: the record is completed with the charged response
        val record = StoredFeeRecords.of(transactionId)
        assertThat(record?.status).isEqualTo(FeeRecordStatus.COMPLETED)
        assertThat(record?.response?.fee).isEqualByComparingTo(BigDecimal("1.50"))

        // when: the same payload is sent again
        val again = api.submit(bodyOf(request))

        // then: the same response, and the wallet is not called again
        assertThat(again.status).describedAs("resubmission status, body %s", again.body).isEqualTo(200)
        assertThat(again.body["fee"].decimalValue()).isEqualByComparingTo(BigDecimal("1.50"))
        assertThat(wallet.callCount(chargeKey)).describedAs("CHARGE executions after the resubmission")
            .isEqualTo(FAILURES + 1)
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

    private companion object {
        const val FAILURES = 3
    }
}
