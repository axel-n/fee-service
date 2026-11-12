package com.example.fee.it

import com.example.fee.contract.FeeRequest
import com.example.fee.record.FeeRecordStatus
import com.example.fee.workflow.FeeOutcome
import com.example.fee.workflow.WorkflowStep
import org.assertj.core.api.Assertions.assertThat
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
 * the invocation from the top, replays the journaled steps and runs the rest. The caller still gets `Charged`, and the
 * record is `COMPLETED`.
 *
 * "Not re-executed" is asserted by counting the journaled closures (validator, calculator), never hook calls: the
 * handler, hooks included, runs again on every replay. Each closure runs exactly once whichever boundary crashed: a step
 * journaled before the crash is replayed, and a step after it runs only on the retry.
 */
class CrashRecoveryIT @Autowired constructor(
    private val hook: FaultInjectingStepHook,
    private val validator: CountingValidator,
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
        assertThat(hook.callCount(transactionId, WorkflowStep.VALIDATE, FaultInjectingStepHook.Phase.BEFORE))
            .describedAs("handler runs (hook calls before VALIDATE)").isEqualTo(2)

        // then: the caller gets the charge
        assertThat(response.status).describedAs("POST status, body %s", response.body).isEqualTo(200)
        assertThat(response.body["transaction_id"].asText()).isEqualTo(transactionId)
        assertThat(response.body["fee"].decimalValue()).isEqualByComparingTo(BigDecimal("1.50"))
        assertThat(StoredWorkflows.step(request)).isEqualTo(WorkflowStep.DONE)
        assertThat(StoredWorkflows.outcome(request)).isInstanceOf(FeeOutcome.Charged::class.java)

        // then: every journaled closure ran exactly once, so nothing journaled before the crash ran again
        assertThat(validator.count(transactionId)).describedAs("VALIDATE executions").isEqualTo(1)
        assertThat(calculator.count(transactionId)).describedAs("CALCULATE executions").isEqualTo(1)

        // then: the record is completed with the charged response
        val record = StoredFeeRecords.of(transactionId)
        assertThat(record?.status).isEqualTo(FeeRecordStatus.COMPLETED)
        assertThat(record?.response?.fee).isEqualByComparingTo(BigDecimal("1.50"))
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

    private companion object {

        // Every step except DONE (it has no hook), on both sides: a new WorkflowStep gets its rows automatically
        @JvmStatic
        fun boundaries(): Stream<Arguments> =
            WorkflowStep.entries.filter { it != WorkflowStep.DONE }.flatMap { step ->
                FaultInjectingStepHook.Phase.entries.map { phase -> Arguments.of(step, phase) }
            }.stream()

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
