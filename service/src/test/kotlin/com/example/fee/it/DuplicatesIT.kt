package com.example.fee.it

import com.example.fee.contract.FeeRequest
import com.example.fee.record.FeeRecordStatus
import com.example.fee.workflow.FeeOutcome
import com.example.fee.workflow.WorkflowStep
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.web.server.LocalServerPort
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Duplicates end to end. The workflow key is the `transaction_id`, so Restate runs one workflow per id and every caller
 * of that id attaches to that run, whatever valid payload it sends: the first run's result is the answer. An invalid
 * payload is rejected by the gateway before it reaches Restate, so it never joins the run. Idempotency is
 * asserted by the number of CALCULATE executions, not just by the response. Once charged, the id is answered from the
 * app's cache or Postgres, and Postgres holds exactly one record for it, the accepted payload's charge.
 */
class DuplicatesIT
    @Autowired
    constructor(
        private val calculator: CountingFeeCalculator,
        @LocalServerPort apiPort: Int,
    ) : RestateIntegrationTest() {
        private val api = FeeApi(apiPort)

        @Test
        fun `the same payload sent twice gets the same response and is charged once`() {
            // given
            val request = validRequest(newTransactionId())
            val first = api.submit(bodyOf(request))
            assertThat(first.status).describedAs("first POST status, body %s", first.body).isEqualTo(200)

            // when
            val second = api.submit(bodyOf(request))

            // then: the same response
            assertThat(second.status).describedAs("second POST status, body %s", second.body).isEqualTo(200)
            assertThat(second.body).isEqualTo(first.body)
            assertThat(second.body["fee"].decimalValue()).isEqualByComparingTo(BigDecimal("1.50"))

            // then: one CALCULATE execution, one completed record
            assertChargedOnce(request)
        }

        @Test
        fun `the same payload sent 10 times concurrently is charged once and every caller gets the same response`() {
            // given
            val request = validRequest(newTransactionId())

            // when
            val responses = submitConcurrently(List(CONCURRENT_CALLERS) { request })

            // then: every caller gets the one run's response
            responses.forEach {
                assertThat(it.status).describedAs("POST status, body %s", it.body).isEqualTo(200)
            }
            assertThat(responses.map { it.body }.distinct()).hasSize(1)
            assertThat(responses.first().body["transaction_id"].asText()).isEqualTo(request.transactionId)
            assertThat(responses.first().body["fee"].decimalValue()).isEqualByComparingTo(BigDecimal("1.50"))

            // then: one CALCULATE execution, one completed record
            assertChargedOnce(request)
        }

        @Test
        fun `a changed payload for an already charged transaction_id gets the first response and is not charged`() {
            // given
            val transactionId = newTransactionId()
            val original = validRequest(transactionId)
            val charged = api.submit(bodyOf(original))
            assertThat(charged.status).describedAs("first POST status, body %s", charged.body).isEqualTo(200)
            val changed = original.copy(amount = BigDecimal("2000"))

            // when
            val second = api.submit(bodyOf(changed))

            // then: the first run's response, for the original amount
            assertThat(second.status).describedAs("second POST status, body %s", second.body).isEqualTo(200)
            assertThat(second.body).isEqualTo(charged.body)
            assertThat(second.body["amount"].decimalValue()).isEqualByComparingTo(BigDecimal("1000"))

            // then: the one workflow of the id, which ran for the original payload
            assertThat(StoredWorkflows.keyOf(changed)).isEqualTo(StoredWorkflows.keyOf(original))
            assertThat(StoredWorkflows.step(changed)).isEqualTo(WorkflowStep.DONE)
            assertThat(StoredWorkflows.outcome(changed)).isInstanceOf(FeeOutcome.Charged::class.java)

            // then: the original charge and record stand, and the changed payload was not charged
            assertChargedOnce(original)
        }

        @Test
        fun `different payloads for one transaction_id sent concurrently are charged once and every caller gets the same response`() {
            // given: the same id, each payload with its own amount
            val transactionId = newTransactionId()
            val requests = List(CONCURRENT_CALLERS) { validRequest(transactionId).copy(amount = BigDecimal(1_000 + it)) }

            // when
            val responses = submitConcurrently(requests)

            // then: every caller gets the one run's response
            responses.forEach {
                assertThat(it.status).describedAs("POST status, body %s", it.body).isEqualTo(200)
            }
            assertThat(responses.map { it.body }.distinct()).describedAs("distinct response bodies").hasSize(1)

            // then: the payload that reached Restate first was charged, once, and Postgres holds exactly that charge
            val charged = responses.first().body
            val winner = requests.single { checkNotNull(it.amount).compareTo(charged["amount"].decimalValue()) == 0 }
            assertChargedOnce(winner)
            assertThat(StoredFeeRecords.count(transactionId)).describedAs("fee records of the id").isEqualTo(1)
            assertThat(StoredFeeRecords.of(transactionId)?.response?.fee).isEqualByComparingTo(charged["fee"].decimalValue())
        }

        @Test
        fun `valid and invalid payloads of one id sent at once, each invalid one is rejected, the valid ones charged once`() {
            // given: the same id, every other payload in an unsupported state
            val transactionId = newTransactionId()
            val requests =
                List(CONCURRENT_CALLERS) {
                    val valid = validRequest(transactionId).copy(amount = BigDecimal(1_000 + it))
                    if (it % 2 == 0) valid else valid.copy(state = "PENDING")
                }

            // when
            val responses = submitConcurrently(requests)

            // then: the gateway rejected every invalid payload on its own, before Restate
            val (valid, invalid) = requests.indices.partition { requests[it].state == "SETTLED - PENDING FEE" }
            invalid.map { responses[it] }.forEach {
                assertThat(it.status).describedAs("POST status, body %s", it.body).isEqualTo(422)
                assertThat(it.body["field"].asText()).isEqualTo("state")
            }

            // then: every valid caller got the one charge, of the valid payload that reached Restate first
            val charged = valid.map { responses[it] }
            assertThat(charged.map { it.status to it.body }.distinct()).describedAs("distinct answers to valid payloads").hasSize(1)
            val answer = charged.first()
            assertThat(answer.status).describedAs("POST status, body %s", answer.body).isEqualTo(200)
            val winner = requests.single { checkNotNull(it.amount).compareTo(answer.body["amount"].decimalValue()) == 0 }
            assertThat(winner.state).isEqualTo("SETTLED - PENDING FEE")
            assertChargedOnce(winner)
            assertThat(StoredFeeRecords.count(transactionId)).describedAs("fee records of the id").isEqualTo(1)
        }

        @Test
        fun `a transaction_id whose record is claimed by a run that never finished is a conflict and is not charged`() {
            // given: the record claimed as a run killed after CLAIM leaves it, and no workflow for the id
            val request = validRequest(newTransactionId())
            val transactionId = checkNotNull(request.transactionId)
            StoredFeeRecords.claimByARunThatNeverFinished(transactionId)

            // when
            val response = api.submit(bodyOf(request))

            // then: 409 naming the transaction
            assertThat(response.status).describedAs("POST status, body %s", response.body).isEqualTo(409)
            assertThat(response.body["code"].asText()).isEqualTo("DUPLICATE_TRANSACTION")
            assertThat(response.body["transaction_id"].asText()).isEqualTo(transactionId)

            // then: the workflow stopped at CLAIM, nothing was calculated, and the claim stands
            assertThat(StoredWorkflows.outcome(request)).isEqualTo(FeeOutcome.Conflict(transactionId))
            assertThat(calculator.count(transactionId)).describedAs("CALCULATE executions").isZero()
            assertThat(StoredFeeRecords.of(transactionId)?.status).isEqualTo(FeeRecordStatus.IN_PROGRESS)
            assertThat(StoredFeeRecords.of(transactionId)?.claimToken).isEqualTo(StoredFeeRecords.FOREIGN_TOKEN)
        }

        private fun assertChargedOnce(request: FeeRequest) {
            val transactionId = checkNotNull(request.transactionId)
            assertThat(calculator.count(transactionId)).describedAs("CALCULATE executions").isEqualTo(1)

            val record = StoredFeeRecords.of(transactionId)
            assertThat(record?.status).isEqualTo(FeeRecordStatus.COMPLETED)
            assertThat(record?.response?.amount).isEqualByComparingTo(request.amount)
            assertThat(record?.response?.fee).isEqualByComparingTo(expectedFee(request))
        }

        // Mobile Top Up at the packaged 0.15%, rounded HALF_EVEN to 2 decimals
        private fun expectedFee(request: FeeRequest): BigDecimal =
            checkNotNull(request.amount).multiply(BigDecimal("0.0015")).setScale(2, RoundingMode.HALF_EVEN)

        // All callers wait on one gate, so the requests reach the app together
        private fun submitConcurrently(requests: List<FeeRequest>): List<FeeApi.Response> {
            val executor = Executors.newFixedThreadPool(requests.size)
            try {
                val gate = CountDownLatch(1)
                val futures =
                    requests.map { request ->
                        executor.submit<FeeApi.Response> {
                            gate.await()
                            api.submit(bodyOf(request))
                        }
                    }
                gate.countDown()
                return futures.map { it.get(120, TimeUnit.SECONDS) }
            } finally {
                executor.shutdownNow()
            }
        }

        private fun newTransactionId() = "it-duplicate-${UUID.randomUUID().toString().take(13)}"

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
            const val CONCURRENT_CALLERS = 10

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
