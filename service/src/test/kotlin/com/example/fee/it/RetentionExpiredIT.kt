package com.example.fee.it

import com.example.fee.contract.FeeRequest
import com.example.fee.contract.FeeResponse
import com.example.fee.record.FeeRecord
import com.example.fee.record.FeeRecordStatus
import com.example.fee.workflow.FeeOutcome
import com.example.fee.workflow.WorkflowStep
import com.github.benmanes.caffeine.cache.Cache
import dev.restate.client.SendResponse
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.web.server.LocalServerPort
import java.math.BigDecimal
import java.util.UUID

/**
 * A `transaction_id` after Restate forgot its run (workflow retention, 1 day on 1.8.1). A charged id is answered by
 * `FeeService` from its cache or from Postgres, so no new run starts. A run that starts anyway, sent past the app, is
 * stopped at CLAIM by the permanent record: `AlreadyCompleted` → the stored response, no second charge. A rejected id has
 * no record, so it is processed normally then.
 *
 * Waiting a day is not an option, so the tests purge the old run through the admin API, as retention would.
 */
class RetentionExpiredIT
    @Autowired
    constructor(
        private val calculator: CountingFeeCalculator,
        private val chargedFeesCache: Cache<String, FeeResponse>,
        @LocalServerPort apiPort: Int,
    ) : RestateIntegrationTest() {
        private val api = FeeApi(apiPort)

        @Test
        fun `a charged transaction_id whose run was purged is answered from the cache, without a new run or a second charge`() {
            // given: the payload charged through the API, then its run purged as retention would
            val request = validRequest(newTransactionId())
            val first = api.submit(bodyOf(request))
            assertThat(first.status).describedAs("first POST status, body %s", first.body).isEqualTo(200)
            val recorded = completedRecord(request)
            purgeRunOf(request)

            // when
            val second = api.submit(bodyOf(request))

            // then: the same response, and Restate was not asked: the purged run has no successor
            assertThat(second.status).describedAs("second POST status, body %s", second.body).isEqualTo(200)
            assertThat(second.body).isEqualTo(first.body)
            assertThat(StoredWorkflows.step(request)).isNull()
            assertThat(StoredFeeRecords.of(transactionIdOf(request))).isEqualTo(recorded)
            assertChargedOnce(request)
        }

        @Test
        fun `a charged transaction_id no longer cached is answered from Postgres, without a new run, and cached again`() {
            // given: charged, its run purged, and its cache entry gone (expired, evicted or another instance)
            val request = validRequest(newTransactionId())
            val first = api.submit(bodyOf(request))
            assertThat(first.status).describedAs("first POST status, body %s", first.body).isEqualTo(200)
            val recorded = completedRecord(request)
            purgeRunOf(request)
            chargedFeesCache.invalidate(transactionIdOf(request))

            // when: sent again with a changed amount
            val second = api.submit(bodyOf(request.copy(amount = BigDecimal("2000"))))

            // then: the stored response, and no new run
            assertThat(second.status).describedAs("second POST status, body %s", second.body).isEqualTo(200)
            assertThat(second.body).isEqualTo(first.body)
            assertThat(StoredWorkflows.step(request)).isNull()
            assertThat(chargedFeesCache.getIfPresent(transactionIdOf(request))).isEqualTo(recorded.response)
            assertChargedOnce(request)
        }

        @Test
        fun `a run sent past the app for a charged transaction_id is stopped at CLAIM with the stored response`() {
            // given: the payload charged through the API, then its run purged as retention would
            val request = validRequest(newTransactionId())
            val first = api.submit(bodyOf(request))
            assertThat(first.status).describedAs("first POST status, body %s", first.body).isEqualTo(200)
            val recorded = completedRecord(request)
            purgeRunOf(request)

            // when: a new run under the same key, straight through the ingress
            val sent = StoredWorkflows.submit(request)
            awaitUntil("the new run of ${StoredWorkflows.keyOf(request)} has finished") { StoredWorkflows.outcome(request) != null }

            // then: a new run, which CLAIM answered from the record. A workflow's invocation id is derived from its key,
            // so it is the same for both runs and proves nothing.
            assertThat(sent.sendStatus()).isEqualTo(SendResponse.SendStatus.ACCEPTED)
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
            assertThat(StoredFeeRecords.of(transactionIdOf(valid))).isNull()
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
            val sent = StoredWorkflows.submit(request)
            assertThat(sent.sendStatus()).isEqualTo(SendResponse.SendStatus.PREVIOUSLY_ACCEPTED)
            return sent.invocationId()
        }

        private fun assertChargedOnce(request: FeeRequest) {
            assertThat(calculator.count(transactionIdOf(request))).describedAs("CALCULATE executions").isEqualTo(1)
            assertThat(StoredFeeRecords.count(transactionIdOf(request))).describedAs("fee records").isEqualTo(1)
            assertThat(StoredFeeRecords.of(transactionIdOf(request))?.response?.fee).isEqualByComparingTo(BigDecimal("1.50"))
        }

        private fun transactionIdOf(request: FeeRequest): String = checkNotNull(request.transactionId)

        private fun newTransactionId() = "it-retention-${UUID.randomUUID().toString().take(13)}"

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

        private companion object {
            fun validRequest(transactionId: String) =
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
    }
