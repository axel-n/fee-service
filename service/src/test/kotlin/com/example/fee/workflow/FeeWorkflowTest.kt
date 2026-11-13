package com.example.fee.workflow

import com.example.fee.contract.FeeRequest
import com.example.fee.contract.FeeResponse
import com.example.fee.domain.Asset
import com.example.fee.domain.AssetType
import com.example.fee.domain.Transaction
import com.example.fee.domain.TransactionMapper
import com.example.fee.domain.TransactionState
import com.example.fee.domain.TransactionType
import com.example.fee.record.ClaimDecider
import com.example.fee.record.FeeRecord
import com.example.fee.record.InMemoryFeeRecordRepository
import com.example.fee.record.InMemoryFeeRecordRepository.Call
import com.example.fee.validation.RequestValidator
import com.example.fee.validation.TransactionValidator
import com.example.fee.validation.ValidationError
import com.example.fee.validation.allRulesCalculator
import com.example.fee.validation.validRequest
import dev.restate.common.Slice
import dev.restate.sdk.endpoint.definition.HandlerDefinition
import dev.restate.sdk.endpoint.definition.HandlerType
import dev.restate.sdk.endpoint.definition.ServiceDefinitionFactories
import dev.restate.sdk.endpoint.definition.ServiceType
import dev.restate.serde.TypeTag
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDateTime
import java.util.UUID

/**
 * The handlers against an in-memory [FeeWorkflowContext] (journaled actions run at once, or answer what a replay
 * would) and in-memory fee records. Journaling, replay, recovery and Postgres itself are the ITs'.
 */
class FeeWorkflowTest {
    private val hook = RecordingStepHook()

    private val expectedResponse =
        FeeResponse(
            transactionId = "txn_001",
            amount = BigDecimal("1000"),
            asset = "USD",
            type = "Mobile Top Up",
            fee = BigDecimal("1.50"),
            rate = BigDecimal("0.0015"),
            description = "Standard fee rate of 0.15%",
        )

    @Test
    fun `charges the reference request and returns the fee response`() =
        runBlocking<Unit> {
            // given
            val records = InMemoryFeeRecordRepository()

            // when
            val outcome = workflow(FakeWorkflowContext(), records).run(validRequest)

            // then
            assertThat(outcome).isEqualTo(FeeOutcome.Charged(expectedResponse))
            assertThat(records.records[KEY]?.response).isEqualTo(expectedResponse)
        }

    @Test
    fun `claims the transaction id with a fresh token and every request field, then records the response with the same token`() =
        runBlocking<Unit> {
            // given
            val context = FakeWorkflowContext()
            val records = InMemoryFeeRecordRepository()

            // when
            workflow(context, records).run(validRequest)

            // then: the claim carries the request exactly as sent, created_at parsed
            val token = context.results["claim-token"] as String
            assertThat(UUID.fromString(token)).isNotNull
            val claimed =
                Transaction(
                    transactionId = KEY,
                    amount = BigDecimal("1000"),
                    asset = Asset.USD,
                    assetType = AssetType.FIAT,
                    type = TransactionType.MOBILE_TOP_UP,
                    state = TransactionState.SETTLED_PENDING_FEE,
                    createdAt = LocalDateTime.of(2023, 8, 30, 15, 42, 17, 610_059_000),
                )
            assertThat(records.calls).containsExactly(
                Call("claim", KEY, token, transaction = claimed),
                Call("complete", KEY, token, expectedResponse),
            )
        }

    @Test
    fun `wraps every step with the step hook, in order, with the workflow key`() =
        runBlocking<Unit> {
            // given
            val context = FakeWorkflowContext()

            // when
            workflow(context).run(validRequest)

            // then
            assertThat(hook.events).containsExactly(
                "before VALIDATE $KEY",
                "after VALIDATE $KEY",
                "before MAP $KEY",
                "after MAP $KEY",
                "before CLAIM $KEY",
                "after CLAIM $KEY",
                "before CALCULATE $KEY",
                "after CALCULATE $KEY",
                "before RECORD $KEY",
                "after RECORD $KEY",
            )
        }

    @Test
    fun `journals validation, the claim token, the claim, the calculation and the record, and only those`() =
        runBlocking<Unit> {
            // given
            val context = FakeWorkflowContext()

            // when
            workflow(context).run(validRequest)

            // then
            assertThat(context.journaled).containsExactly("validate", "claim-token", "claim", "calculate", "record")
        }

    @Test
    fun `stores each step in workflow state before it runs, and DONE at the end`() =
        runBlocking<Unit> {
            // given
            val context = FakeWorkflowContext()
            val workflow = workflow(context)

            // when
            workflow.run(validRequest)

            // then
            assertThat(context.stepWrites).containsExactly(
                WorkflowStep.VALIDATE,
                WorkflowStep.MAP,
                WorkflowStep.CLAIM,
                WorkflowStep.CALCULATE,
                WorkflowStep.RECORD,
                WorkflowStep.DONE,
            )
            assertThat(workflow.status()).isEqualTo(WorkflowStep.DONE)
        }

    @Test
    fun `returns a rejection without touching the fee records`() =
        runBlocking<Unit> {
            // given
            val error = ValidationError("state", "PENDING", "Unsupported state 'PENDING'", ValidationError.Kind.UNSUPPORTED)
            val context = FakeWorkflowContext()
            val records = InMemoryFeeRecordRepository()

            // when
            val outcome = workflow(context, records, validator = validatorReturning(error)).run(validRequest)

            // then
            assertThat(outcome).isEqualTo(FeeOutcome.Rejected(error))
            assertThat(records.calls).isEmpty()
            assertThat(records.records).isEmpty()
            assertThat(context.journaled).containsExactly("validate")
            assertThat(context.stepWrites).containsExactly(WorkflowStep.VALIDATE, WorkflowStep.DONE)
            assertThat(hook.events).containsExactly("before VALIDATE $KEY", "after VALIDATE $KEY")
        }

    @Test
    fun `returns the stored response for a transaction that was already charged, without charging again`() =
        runBlocking<Unit> {
            // given
            val stored = expectedResponse.copy(description = "charged earlier")
            val context = FakeWorkflowContext()
            val records = InMemoryFeeRecordRepository(KEY to FeeRecord.inProgress("earlier-run").complete(stored))

            // when
            val outcome = workflow(context, records).run(validRequest)

            // then
            assertThat(outcome).isEqualTo(FeeOutcome.Charged(stored))
            assertThat(context.journaled).containsExactly("validate", "claim-token", "claim")
            assertThat(records.calls.map { it.name }).containsExactly("claim")
            assertThat(records.records[KEY]?.response).isEqualTo(stored)
            assertThat(context.stepWrites).containsExactly(
                WorkflowStep.VALIDATE,
                WorkflowStep.MAP,
                WorkflowStep.CLAIM,
                WorkflowStep.DONE,
            )
        }

    @Test
    fun `returns a conflict when an earlier run that never finished holds the claim, without charging`() =
        runBlocking<Unit> {
            // given
            val context = FakeWorkflowContext()
            val records = InMemoryFeeRecordRepository(KEY to FeeRecord.inProgress("earlier-run"))

            // when
            val outcome = workflow(context, records).run(validRequest)

            // then
            assertThat(outcome).isEqualTo(FeeOutcome.Conflict("txn_001"))
            assertThat(records.calls.map { it.name }).containsExactly("claim")
            assertThat(records.records[KEY]).isEqualTo(FeeRecord.inProgress("earlier-run"))
            assertThat(context.stepWrites.last()).isEqualTo(WorkflowStep.DONE)
        }

    @Test
    fun `a claim run again after a crash finds its own record by the replayed token and goes on to charge`() =
        runBlocking<Unit> {
            // given: the claim committed, the app crashed before its journal entry, and the token is replayed
            val context = FakeWorkflowContext(replayed = mapOf("claim-token" to "token-of-this-run"))
            val records = InMemoryFeeRecordRepository(KEY to FeeRecord.inProgress("token-of-this-run"))

            // when
            val outcome = workflow(context, records).run(validRequest)

            // then
            assertThat(outcome).isEqualTo(FeeOutcome.Charged(expectedResponse))
            assertThat(records.records[KEY]).isEqualTo(FeeRecord.inProgress("token-of-this-run").complete(expectedResponse))
        }

    @Test
    fun `a crash injected before RECORD stops the workflow before the fee is recorded, with status RECORD`() =
        runBlocking<Unit> {
            // given
            val crash = IllegalStateException("injected crash")
            val crashingHook =
                object : StepHook {
                    override fun before(step: WorkflowStep, workflowKey: String) {
                        if (step == WorkflowStep.RECORD) throw crash
                    }

                    override fun after(step: WorkflowStep, workflowKey: String) = Unit
                }
            val records = InMemoryFeeRecordRepository()
            val workflow = workflow(FakeWorkflowContext(), records, stepHook = crashingHook)

            // when / then
            assertThatThrownBy { runBlocking { workflow.run(validRequest) } }.isSameAs(crash)
            assertThat(records.calls.map { it.name }).containsExactly("claim")
            assertThat(workflow.status()).isEqualTo(WorkflowStep.RECORD)
        }

    @Test
    fun `a failure inside CALCULATE propagates as a plain exception, so Restate retries it, and nothing is recorded`() =
        runBlocking<Unit> {
            // given
            val outage = IllegalStateException("configuration unreadable")
            val records = InMemoryFeeRecordRepository()
            val workflow = workflow(FakeWorkflowContext(failing = mapOf("calculate" to outage)), records)

            // when / then
            assertThatThrownBy { runBlocking { workflow.run(validRequest) } }.isSameAs(outage)
            assertThat(records.calls.map { it.name }).containsExactly("claim")
            assertThat(workflow.status()).isEqualTo(WorkflowStep.CALCULATE)
        }

    @Test
    fun `a Postgres failure at RECORD propagates as a plain exception, so Restate retries it`() =
        runBlocking<Unit> {
            // given
            val outage = IllegalStateException("database unreachable")
            val records = InMemoryFeeRecordRepository(failing = mapOf("complete" to outage))
            val workflow = workflow(FakeWorkflowContext(), records)

            // when / then
            assertThatThrownBy { runBlocking { workflow.run(validRequest) } }.isSameAs(outage)
            assertThat(records.records[KEY]?.response).isNull()
            assertThat(workflow.status()).isEqualTo(WorkflowStep.RECORD)
        }

    @Test
    fun `a request that passed validation but can't be mapped is a bug, thrown before the claim`() {
        // given
        val records = InMemoryFeeRecordRepository()
        val unmappable = validRequest.copy(createdAt = null)

        // when / then
        assertThatThrownBy { runBlocking { workflow(FakeWorkflowContext(), records).run(unmappable) } }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessage("Request for transaction 'txn_001' passed validation but could not be mapped")
        assertThat(records.calls).isEmpty()
    }

    @Test
    fun `status is null for a workflow that never started`() =
        runBlocking<Unit> {
            // given
            val context = FakeWorkflowContext()

            // when
            val status = workflow(context).status()

            // then
            assertThat(status).isNull()
        }

    @Test
    fun `is bound as a workflow with run and a shared status`() {
        // given
        val handlers = workflow(FakeWorkflowContext())

        // when
        val definition = ServiceDefinitionFactories.discover(handlers).create(handlers, null)

        // then
        assertThat(definition.serviceName).isEqualTo("FeeWorkflow")
        assertThat(definition.serviceType).isEqualTo(ServiceType.WORKFLOW)
        assertThat(definition.handlers.associate { it.name to it.handlerType }).isEqualTo(
            mapOf("run" to HandlerType.WORKFLOW, "status" to HandlerType.SHARED),
        )
    }

    @Test
    fun `reads its input and writes its outcome with Restate's Jackson serde, in snake case`() {
        // given
        val handlers = workflow(FakeWorkflowContext())

        @Suppress("UNCHECKED_CAST")
        val run =
            ServiceDefinitionFactories
                .discover(handlers)
                .create(handlers, null)
                .handlers
                .single { it.name == "run" } as HandlerDefinition<FeeRequest, FeeOutcome>
        val input = """{"transaction_id":"txn_001","amount":1000}""".toByteArray()

        // when
        val request = run.requestSerde.deserialize(Slice.wrap(input))
        val output = String(run.responseSerde.serialize(FeeOutcome.Conflict("txn_001")).toByteArray())

        // then
        assertThat(request).isEqualTo(FeeRequest(transactionId = "txn_001", amount = BigDecimal("1000")))
        assertThat(output).isEqualTo("""{"@type":"CONFLICT","transaction_id":"txn_001"}""")
    }

    @Test
    fun `stores the step under the state key 'step'`() {
        // given
        val key = RestateFeeWorkflowContext.STEP

        // when
        val name = key.name()

        // then
        assertThat(name).isEqualTo("step")
    }

    private fun workflow(
        context: FeeWorkflowContext,
        records: InMemoryFeeRecordRepository = InMemoryFeeRecordRepository(),
        validator: RequestValidator = validatorReturning(null),
        stepHook: StepHook = hook,
    ) = FeeWorkflow(validator, TransactionMapper(), allRulesCalculator(), ClaimDecider(), records, stepHook, context)

    private fun validatorReturning(error: ValidationError?) =
        RequestValidator(
            listOf(
                object : TransactionValidator {
                    override fun validate(request: FeeRequest): ValidationError? = error
                },
            ),
        )

    private class RecordingStepHook : StepHook {
        val events = mutableListOf<String>()

        override fun before(step: WorkflowStep, workflowKey: String) {
            events += "before $step $workflowKey"
        }

        override fun after(step: WorkflowStep, workflowKey: String) {
            events += "after $step $workflowKey"
        }
    }

    /**
     * The workflow `txn_001` in memory: the step state is a list, and a journaled action runs at once (or throws what
     * [failing] maps its name to). An action named in [replayed] doesn't run: it answers that journaled result, as a
     * replay does. Every result is kept in [results].
     */
    private class FakeWorkflowContext(
        private val failing: Map<String, Exception> = emptyMap(),
        private val replayed: Map<String, Any?> = emptyMap(),
    ) : FeeWorkflowContext {
        val journaled = mutableListOf<String>()
        val results = mutableMapOf<String, Any?>()
        val stepWrites = mutableListOf<WorkflowStep>()

        override suspend fun key(): String = KEY

        @Suppress("UNCHECKED_CAST")
        override suspend fun <T> journaled(name: String, type: TypeTag<T>, action: suspend () -> T): T {
            journaled += name
            failing[name]?.let { throw it }
            val result = if (name in replayed) replayed[name] as T else action()
            results[name] = result
            return result
        }

        override suspend fun setStep(step: WorkflowStep) {
            stepWrites += step
        }

        override suspend fun step(): WorkflowStep? = stepWrites.lastOrNull()
    }

    private companion object {
        const val KEY = "txn_001"
    }
}
