package com.example.fee.it

import com.example.fee.contract.FeeRequest
import com.example.fee.record.FeeRecord
import com.example.fee.record.FeeRecordStatus
import com.example.fee.workflow.WorkflowStep
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.web.server.LocalServerPort
import java.math.BigDecimal
import java.util.UUID
import java.util.stream.Stream

/**
 * A crash at every step boundary: [FaultInjectingStepHook] throws once before or after a step, Restate retries
 * the invocation from the top, replays the journaled steps and runs the rest. The caller still gets the fee, and the
 * record is `CHARGED`. [FaultInjectingFeeRecordRepository] crashes right after a Postgres write committed: after the
 * CHARGE update, before its step is journaled, the step runs again and writes the same values; after the start insert,
 * before `FeeService` submitted the workflow, the request fails and the next one reuses the record's `workflow_id`.
 * Either way there is one record, `CHARGED`.
 *
 * "Not re-executed" is asserted by counting the CALCULATE closure, never hook calls: the handler, hooks included, runs
 * again on every replay. The closure runs exactly once whichever boundary crashed: journaled before the crash, it is
 * replayed; after it, it runs only on the retry.
 */
class CrashRecoveryIT
    @Autowired
    constructor(
        private val hook: FaultInjectingStepHook,
        private val records: FaultInjectingFeeRecordRepository,
        private val calculator: CountingFeeCalculator,
        @LocalServerPort apiPort: Int,
    ) : RestateIntegrationTest() {
        private val api = FeeApi(apiPort)

        @ParameterizedTest(name = "crash {1} {0}")
        @MethodSource("boundaries")
        fun `a crash at a step boundary is retried to one fee record without re-running journaled steps`(
            step: WorkflowStep,
            phase: FaultInjectingStepHook.Phase,
        ) {
            // given
            val transactionId = "it-crash-${UUID.randomUUID().toString().take(13)}"
            val request = validRequest(transactionId)
            hook.crashOnce(transactionId, step, phase)

            // when
            val response = api.submit(bodyOf(request))

            // then: the crash happened, and Restate ran the handler again from the top
            assertThat(hook.crashCount(transactionId)).describedAs("injected crashes").isEqualTo(1)
            assertThat(hook.callCount(transactionId, WorkflowStep.CALCULATE, FaultInjectingStepHook.Phase.BEFORE))
                .describedAs("handler runs (hook calls before CALCULATE)")
                .isEqualTo(2)

            // then: the caller gets the charge
            assertThat(response.status).describedAs("POST status, body %s", response.body).isEqualTo(200)
            assertThat(response.body["transaction_id"].asText()).isEqualTo(transactionId)
            assertThat(response.body["fee"].decimalValue()).isEqualByComparingTo(BigDecimal("1.50"))
            assertThat(StoredWorkflows.step(request)).isEqualTo(WorkflowStep.DONE)
            assertThat(StoredWorkflows.response(request)?.fee).isEqualByComparingTo(BigDecimal("1.50"))

            // then: the journaled calculation ran exactly once, so nothing journaled before the crash ran again
            assertThat(calculator.count(transactionId)).describedAs("CALCULATE executions").isEqualTo(1)

            // then: the record is charged with the charged response
            assertCharged(transactionId)
        }

        @Test
        fun `a crash after the CHARGE update committed but before Restate journaled it is retried to one fee record`() {
            // given: the update commits, then the app dies before the step's result is journaled
            val transactionId = "it-commit-crash-${UUID.randomUUID().toString().take(13)}"
            val request = validRequest(transactionId)
            records.crashAfterCommitOnce(transactionId, FaultInjectingFeeRecordRepository.Write.CHARGE)

            // when
            val response = api.submit(bodyOf(request))

            // then: the crash happened, and the step ran again, writing the same values to the same record
            assertThat(records.crashCount(transactionId)).describedAs("injected crashes").isEqualTo(1)
            assertThat(response.status).describedAs("POST status, body %s", response.body).isEqualTo(200)
            assertThat(response.body["fee"].decimalValue()).isEqualByComparingTo(BigDecimal("1.50"))
            assertThat(StoredWorkflows.response(request)?.fee).isEqualByComparingTo(BigDecimal("1.50"))

            // then: calculated once, one record, charged
            assertThat(calculator.count(transactionId)).describedAs("CALCULATE executions").isEqualTo(1)
            assertCharged(transactionId)
        }

        @Test
        fun `a crash after the start insert committed fails the request, and the next one reuses the record's workflow`() {
            // given: the insert commits, then the app dies before it submits the workflow
            val transactionId = "it-start-crash-${UUID.randomUUID().toString().take(13)}"
            val request = validRequest(transactionId)
            records.crashAfterCommitOnce(transactionId, FaultInjectingFeeRecordRepository.Write.START)

            // when
            val failed = api.submit(bodyOf(request))

            // then: nothing was submitted, and the record waits under its workflow id
            assertThat(records.crashCount(transactionId)).describedAs("injected crashes").isEqualTo(1)
            assertThat(failed.status).describedAs("POST status, body %s", failed.body).isEqualTo(503)
            val started = StoredFeeRecords.of(transactionId)
            assertThat(started?.status).isEqualTo(FeeRecordStatus.PENDING)
            assertThat(StoredWorkflows.step(request)).describedAs("workflow step").isNull()

            // when: the same request is sent again
            val response = api.submit(bodyOf(request))

            // then: it went to the workflow of the record, which charged it once
            assertThat(response.status).describedAs("POST status, body %s", response.body).isEqualTo(200)
            assertThat(response.body["fee"].decimalValue()).isEqualByComparingTo(BigDecimal("1.50"))
            assertThat(calculator.count(transactionId)).describedAs("CALCULATE executions").isEqualTo(1)
            assertCharged(transactionId)
            assertThat(StoredFeeRecords.of(transactionId)?.workflowId).isEqualTo(started?.workflowId)
        }

        private fun assertCharged(transactionId: String) {
            assertThat(StoredFeeRecords.count(transactionId)).describedAs("fee records").isEqualTo(1)
            val record: FeeRecord? = StoredFeeRecords.of(transactionId)
            assertThat(record?.status).isEqualTo(FeeRecordStatus.CHARGED)
            assertThat(record?.response?.fee).isEqualByComparingTo(BigDecimal("1.50"))
        }

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
            // Every step except DONE (it has no hook), on both sides: a new WorkflowStep gets its rows automatically
            @JvmStatic
            fun boundaries(): Stream<Arguments> =
                WorkflowStep.entries
                    .filter { it != WorkflowStep.DONE }
                    .flatMap { step ->
                        FaultInjectingStepHook.Phase.entries.map { phase -> Arguments.of(step, phase) }
                    }.stream()

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
