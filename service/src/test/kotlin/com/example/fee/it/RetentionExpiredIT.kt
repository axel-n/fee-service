@file:Suppress("DEPRECATION") // the generated clients of the ctx-based codegen API of SDK 2.9.3

package com.example.fee.it

import com.example.fee.contract.FeeRequest
import com.example.fee.record.ClaimResult
import com.example.fee.record.FeeRecord
import com.example.fee.record.FeeRecordStatus
import com.example.fee.record.TransactionFeeRecordClient
import com.example.fee.workflow.FeeOutcome
import com.example.fee.workflow.FeeWorkflowClient
import com.example.fee.workflow.WorkflowStep
import dev.restate.client.SendResponse
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.web.server.LocalServerPort
import java.math.BigDecimal
import java.util.UUID

/**
 * A `transaction_id` after Restate forgot its run (workflow retention, 1 day on 1.8.1). The workflow key no longer finds
 * the old run, so a new workflow starts. A charged id is stopped at CLAIM by its permanent record: `AlreadyCompleted` →
 * the stored response, no second charge. A rejected id has no record, so it is processed normally then.
 *
 * Waiting a day is not an option, so the tests purge the old run through the admin API, as retention would.
 */
class RetentionExpiredIT @Autowired constructor(
    private val calculator: CountingFeeCalculator,
    @LocalServerPort apiPort: Int,
) : RestateIntegrationTest() {

    private val api = FeeApi(apiPort)

    @Test
    fun `CLAIM on a completed record is AlreadyCompleted with the stored response`() {
        // given
        val request = chargedRequest()
        val recorded = completedRecord(request)

        // when
        val claim = TransactionFeeRecordClient.fromClient(TestIngress.client, transactionIdOf(request)).claim()

        // then: the stored response, and the record is untouched
        assertThat(claim).isEqualTo(ClaimResult.AlreadyCompleted(checkNotNull(recorded.response)))
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
        purgeRunOf(request)

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

    @Test
    fun `a rejected transaction_id is charged with a corrected payload once its run was purged`() {
        // given: the id rejected through the API, then its run purged as retention would
        val valid = validRequest(newTransactionId())
        val rejected = valid.copy(state = "PENDING")
        val rejection = api.submit(bodyOf(rejected))
        assertThat(rejection.status).describedAs("first POST status, body %s", rejection.body).isEqualTo(422)
        purgeRunOf(rejected)

        // when
        val corrected = api.submit(bodyOf(valid))

        // then: a new run charged it, once
        assertThat(corrected.status).describedAs("second POST status, body %s", corrected.body).isEqualTo(200)
        assertThat(corrected.body["fee"].decimalValue()).isEqualByComparingTo(BigDecimal("1.50"))
        assertThat(StoredWorkflows.outcome(valid)).isInstanceOf(FeeOutcome.Charged::class.java)
        assertThat(completedRecord(valid).response?.amount).isEqualByComparingTo(valid.amount)
        assertChargedOnce(valid)
    }

    // A payload charged through the API, so its record is COMPLETED and its fee calculated once
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
        return record
    }

    private fun purgeRunOf(request: FeeRequest) {
        RestateTestEnvironment.admin.purgeInvocation(invocationIdOf(request))
        awaitUntil("the purged workflow ${StoredWorkflows.keyOf(request)} is forgotten") {
            StoredWorkflows.step(request) == null
        }
    }

    // Resubmitting a known key is PREVIOUSLY_ACCEPTED with the run's id, its input ignored, so this starts nothing
    private fun invocationIdOf(request: FeeRequest): String {
        val sent = FeeWorkflowClient.fromClient(TestIngress.client, StoredWorkflows.keyOf(request)).submit(request)
        assertThat(sent.sendStatus()).isEqualTo(SendResponse.SendStatus.PREVIOUSLY_ACCEPTED)
        return sent.invocationId()
    }

    private fun assertChargedOnce(request: FeeRequest) {
        assertThat(calculator.count(transactionIdOf(request))).describedAs("CALCULATE executions").isEqualTo(1)
        assertThat(StoredFeeRecords.of(transactionIdOf(request))?.response?.fee).isEqualByComparingTo(BigDecimal("1.50"))
    }

    private fun transactionIdOf(request: FeeRequest): String = checkNotNull(request.transactionId)

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
