@file:Suppress("DEPRECATION") // the generated clients of the ctx-based codegen API of SDK 2.9.3

package com.example.fee.it

import com.example.fee.contract.FeeRequest
import com.example.fee.record.ClaimResult
import com.example.fee.record.FeeRecord
import com.example.fee.record.FeeRecordStatus
import com.example.fee.record.TransactionFeeRecordClient
import com.example.fee.workflow.FeeOutcome
import com.example.fee.workflow.FeeWorkflow
import com.example.fee.workflow.FeeWorkflowClient
import com.example.fee.workflow.FeeWorkflowInput
import com.example.fee.workflow.WorkflowStep
import dev.restate.client.SendResponse
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.web.server.LocalServerPort
import java.math.BigDecimal
import java.util.UUID

/**
 * The same payload after Restate forgot the first run (workflow retention, 1 day on 1.8.1). The workflow key no
 * longer finds the old run, so a new workflow starts, and the permanent record stops it at CLAIM: `AlreadyCompleted` →
 * the stored response, no second charge.
 *
 * Waiting a day is not an option, so the tests reach CLAIM the two ways a forgotten run does: a new workflow key for the
 * charged payload, and the old run purged through the admin API, then the same payload sent through the app again.
 */
class RetentionExpiredIT @Autowired constructor(
    private val wallet: TestWalletGateway,
    @LocalServerPort apiPort: Int,
) : RestateIntegrationTest() {

    private val api = FeeApi(apiPort)

    @Test
    fun `CLAIM on a completed record with the same payload hash is AlreadyCompleted with the stored response`() {
        // given
        val request = chargedRequest()
        val recorded = completedRecord(request)

        // when
        val claim = TransactionFeeRecordClient.fromClient(TestIngress.client, transactionIdOf(request))
            .claim(payloadHashOf(request))

        // then: the stored response, and the record is untouched
        assertThat(claim).isEqualTo(ClaimResult.AlreadyCompleted(checkNotNull(recorded.response)))
        assertThat(StoredFeeRecords.of(transactionIdOf(request))).isEqualTo(recorded)
        assertChargedOnce(request)
    }

    @Test
    fun `a new workflow key for an already charged payload returns the stored response without charging`() {
        // given: the payload charged under its own key, and a key Restate has never seen
        val request = chargedRequest()
        val recorded = completedRecord(request)
        val newKey = "${StoredWorkflows.keyOf(request)}-after-retention"
        val workflow = FeeWorkflowClient.fromClient(TestIngress.client, newKey)

        // when
        val submitted = workflow.submit(FeeWorkflowInput(request, payloadHashOf(request)))
        val outcome = submitted.attach().response()

        // then: a run of its own, stopped at CLAIM with the stored response
        assertThat(submitted.sendStatus()).isEqualTo(SendResponse.SendStatus.ACCEPTED)
        assertThat(outcome).isEqualTo(FeeOutcome.Charged(checkNotNull(recorded.response)))
        assertThat(workflow.status()).isEqualTo(WorkflowStep.DONE)

        // then: no second charge, and the record is untouched
        assertThat(StoredFeeRecords.of(transactionIdOf(request))).isEqualTo(recorded)
        assertChargedOnce(request)
    }

    @Test
    fun `the same payload after its run was purged gets the same response through the API and is not charged again`() {
        // given: the payload charged through the API, then its run purged as retention would
        val request = validRequest(newTransactionId())
        val first = api.submit(bodyOf(request))
        assertThat(first.status).describedAs("first POST status, body %s", first.body).isEqualTo(200)
        val recorded = completedRecord(request)
        RestateTestEnvironment.admin.purgeInvocation(invocationIdOf(request))
        awaitUntil("the purged workflow ${StoredWorkflows.keyOf(request)} is forgotten") {
            StoredWorkflows.step(request) == null
        }

        // when
        val second = api.submit(bodyOf(request))

        // then: the same response as the first time
        assertThat(second.status).describedAs("second POST status, body %s", second.body).isEqualTo(200)
        assertThat(second.body).isEqualTo(first.body)

        // then: a new run under the same key (the purged one had no step left), which CLAIM answered from the record.
        // A workflow's invocation id is derived from its key, so it is the same for both runs and proves nothing.
        assertThat(StoredWorkflows.step(request)).isEqualTo(WorkflowStep.DONE)
        assertThat(StoredWorkflows.outcome(request)).isEqualTo(FeeOutcome.Charged(checkNotNull(recorded.response)))

        // then: no second charge, and the record is untouched
        assertThat(StoredFeeRecords.of(transactionIdOf(request))).isEqualTo(recorded)
        assertChargedOnce(request)
    }

    // A payload charged through the API, so its record is COMPLETED and the wallet charged once
    private fun chargedRequest(): FeeRequest {
        val request = validRequest(newTransactionId())
        val response = api.submit(bodyOf(request))
        assertThat(response.status).describedAs("POST status, body %s", response.body).isEqualTo(200)
        assertChargedOnce(request)
        return request
    }

    private fun completedRecord(request: FeeRequest): FeeRecord {
        val record = checkNotNull(StoredFeeRecords.of(transactionIdOf(request))) { "no record for ${request.transactionId}" }
        assertThat(record.status).isEqualTo(FeeRecordStatus.COMPLETED)
        assertThat(record.payloadHash).isEqualTo(payloadHashOf(request))
        return record
    }

    // Resubmitting a known key is PREVIOUSLY_ACCEPTED with the run's id, its input ignored, so this starts nothing
    private fun invocationIdOf(request: FeeRequest): String {
        val sent = FeeWorkflowClient.fromClient(TestIngress.client, StoredWorkflows.keyOf(request))
            .submit(FeeWorkflowInput(request, payloadHashOf(request)))
        assertThat(sent.sendStatus()).isEqualTo(SendResponse.SendStatus.PREVIOUSLY_ACCEPTED)
        return sent.invocationId()
    }

    private fun assertChargedOnce(request: FeeRequest) {
        val chargeKey = FeeWorkflow.chargeKey(transactionIdOf(request))
        assertThat(wallet.chargeCount(chargeKey)).isEqualTo(1)
        assertThat(wallet.callCount(chargeKey)).describedAs("CHARGE executions").isEqualTo(1)
        assertThat(wallet.receipt(chargeKey)?.amount).isEqualByComparingTo(BigDecimal("1.50"))
    }

    private fun transactionIdOf(request: FeeRequest): String = checkNotNull(request.transactionId)

    private fun payloadHashOf(request: FeeRequest): String = StoredWorkflows.keyOf(request).substringAfter(':')

    private fun newTransactionId() = "it-retention-${UUID.randomUUID().toString().take(13)}"

    private fun bodyOf(request: FeeRequest): Map<String, Any?> = mapOf(
        "transaction_id" to request.transactionId,
        "amount" to request.amount,
        "asset" to request.asset,
        "asset_type" to request.assetType,
        "type" to request.type,
        "state" to request.state,
        "created_at" to request.createdAt,
    )

    private companion object {
        fun validRequest(transactionId: String) = FeeRequest(
            transactionId = transactionId,
            amount = BigDecimal("1000"),
            asset = "USD",
            assetType = "FIAT",
            type = "Mobile Top Up",
            state = "SETTLED - PENDING FEE",
            createdAt = "2023-08-30 15:42:17.610059",
        )
    }
}
