package com.example.fee.it

import com.example.fee.contract.FeeRequest
import com.example.fee.contract.FeeResponse
import com.example.fee.record.FeeRecord
import com.example.fee.record.FeeRecordStatus
import com.github.benmanes.caffeine.cache.Cache
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.web.server.LocalServerPort
import java.math.BigDecimal
import java.util.UUID

/**
 * A `transaction_id` after Restate forgot its run (workflow retention, 1 day on 1.8.1). A charged id is answered by
 * `FeeService` from its cache or from Postgres, so no new run starts: the record outlives the run.
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
            val recorded = chargedRecord(request)
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
            val recorded = chargedRecord(request)
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

        private fun chargedRecord(request: FeeRequest): FeeRecord {
            val record = checkNotNull(StoredFeeRecords.of(transactionIdOf(request))) { "no record for ${request.transactionId}" }
            assertThat(record.status).isEqualTo(FeeRecordStatus.CHARGED)
            return record
        }

        private fun purgeRunOf(request: FeeRequest) {
            RestateTestEnvironment.admin.purgeInvocation(invocationIdOf(request))
            awaitUntil("the purged workflow ${StoredWorkflows.keyOf(request)} is forgotten") {
                StoredWorkflows.step(request) == null
            }
        }

        // The id of the workflow's run, from Restate's own invocation table
        private fun invocationIdOf(request: FeeRequest): String {
            val workflowId = checkNotNull(StoredWorkflows.keyOf(request)) { "no record for ${request.transactionId}" }
            return RestateTestEnvironment.admin
                .query("$INVOCATION_ID 'FeeWorkflow/$workflowId/run'")
                .single()["id"]
                .asText()
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
            // Restate's query API takes no parameters: the run's target is appended, quoted
            const val INVOCATION_ID = "SELECT id FROM sys_invocation WHERE target ="

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
