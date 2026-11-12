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
 * Duplicates end to end. The same payload is the same workflow key, so Restate runs it once and every caller attaches to
 * that run. A different payload for the same `transaction_id` is a new workflow, and the CLAIM on
 * `TransactionFeeRecord/{transaction_id}` stops it: `Conflict` → 409. Idempotency is asserted by the number of
 * CALCULATE executions, not just by the response.
 */
class DuplicatesIT @Autowired constructor(
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
    fun `a changed payload for an already charged transaction_id is a conflict and is not charged`() {
        // given
        val transactionId = newTransactionId()
        val original = validRequest(transactionId)
        val charged = api.submit(bodyOf(original))
        assertThat(charged.status).describedAs("first POST status, body %s", charged.body).isEqualTo(200)
        val changed = original.copy(amount = BigDecimal("2000"))

        // when
        val conflict = api.submit(bodyOf(changed))

        // then: 409 naming the transaction
        assertThat(conflict.status).describedAs("POST status, body %s", conflict.body).isEqualTo(409)
        assertThat(conflict.body["transaction_id"].asText()).isEqualTo(transactionId)

        // then: a workflow of its own, which CLAIM stopped
        assertThat(StoredWorkflows.keyOf(changed)).isNotEqualTo(StoredWorkflows.keyOf(original))
        assertThat(StoredWorkflows.step(changed)).isEqualTo(WorkflowStep.DONE)
        assertThat(StoredWorkflows.outcome(changed)).isEqualTo(FeeOutcome.Conflict(transactionId))

        // then: the original charge and record stand, and the changed payload was not charged
        assertChargedOnce(original)
        assertThat(StoredFeeRecords.of(transactionId)?.response?.amount).isEqualByComparingTo(BigDecimal("1000"))
    }

    @Test
    fun `different payloads for one transaction_id sent concurrently charge one and conflict the others`() {
        // given: the same id, each payload with its own amount
        val transactionId = newTransactionId()
        val requests = List(CONCURRENT_CALLERS) { validRequest(transactionId).copy(amount = BigDecimal(1000 + it)) }

        // when
        val responses = submitConcurrently(requests)

        // then: exactly one is charged, every other one is a conflict
        responses.forEach {
            assertThat(it.status).describedAs("POST status, body %s", it.body).isIn(200, 409)
        }
        val winners = requests.indices.filter { responses[it].status == 200 }
        assertThat(winners).describedAs("charged payloads").hasSize(1)
        val winner = requests[winners.single()]
        responses.filter { it.status == 409 }.forEach {
            assertThat(it.body["transaction_id"].asText()).isEqualTo(transactionId)
        }
        assertThat(responses[winners.single()].body["amount"].decimalValue()).isEqualByComparingTo(winner.amount)

        // then: each workflow returned its business result
        requests.forEachIndexed { index, request ->
            val outcome = StoredWorkflows.outcome(request)
            if (index == winners.single()) {
                assertThat(outcome).isInstanceOf(FeeOutcome.Charged::class.java)
            } else {
                assertThat(outcome).describedAs("outcome of amount %s", request.amount)
                    .isEqualTo(FeeOutcome.Conflict(transactionId))
            }
        }

        // then: one fee, for the winner, recorded as completed
        assertChargedOnce(winner)
    }

    private fun assertChargedOnce(request: FeeRequest) {
        val transactionId = checkNotNull(request.transactionId)
        assertThat(calculator.count(transactionId)).describedAs("CALCULATE executions").isEqualTo(1)

        val record = StoredFeeRecords.of(transactionId)
        assertThat(record?.status).isEqualTo(FeeRecordStatus.COMPLETED)
        assertThat(record?.payloadHash).isEqualTo(StoredWorkflows.keyOf(request).substringAfter(':'))
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
            val futures = requests.map { request ->
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
        const val CONCURRENT_CALLERS = 10

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
