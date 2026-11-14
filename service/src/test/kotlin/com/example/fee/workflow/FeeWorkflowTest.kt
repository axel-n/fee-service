package com.example.fee.workflow

import com.example.fee.contract.FeeRequest
import com.example.fee.contract.FeeResponse
import com.example.fee.domain.TransactionMapper
import com.example.fee.record.FeeRecord
import com.example.fee.record.InMemoryFeeRecordRepository
import com.example.fee.record.InMemoryFeeRecordRepository.Call
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

/**
 * The handlers against an in-memory [FeeWorkflowContext] (journaled actions run at once) and in-memory fee records,
 * where `FeeService` has started the record of `txn_001` for the workflow [KEY]. Journaling, replay, recovery and
 * Postgres itself are the ITs'.
 */
class FeeWorkflowTest {
    private val hook = RecordingStepHook()

    private val expectedResponse =
        FeeResponse(
            transactionId = TRANSACTION_ID,
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
            val records = startedRecords()

            // when
            val response = workflow(FakeWorkflowContext(), records).run(validRequest)

            // then
            assertThat(response).isEqualTo(expectedResponse)
            assertThat(records.records[TRANSACTION_ID]).isEqualTo(FeeRecord.pending(KEY).charge(expectedResponse))
        }

    @Test
    fun `charges the transaction's record under the workflow key with the payload it charged, and writes nothing else`() =
        runBlocking<Unit> {
            // given
            val records = startedRecords()

            // when
            workflow(FakeWorkflowContext(), records).run(validRequest)

            // then
            val charged = TransactionMapper().toTransaction(validRequest)
            assertThat(records.calls).containsExactly(Call("charge", TRANSACTION_ID, KEY, expectedResponse, charged))
        }

    @Test
    fun `wraps every step with the step hook, in order, with the transaction id`() =
        runBlocking<Unit> {
            // given
            val context = FakeWorkflowContext()

            // when
            workflow(context).run(validRequest)

            // then
            assertThat(hook.events).containsExactly(
                "before CALCULATE $TRANSACTION_ID",
                "after CALCULATE $TRANSACTION_ID",
                "before CHARGE $TRANSACTION_ID",
                "after CHARGE $TRANSACTION_ID",
            )
        }

    @Test
    fun `journals the calculation and the charge, and only those`() =
        runBlocking<Unit> {
            // given
            val context = FakeWorkflowContext()

            // when
            workflow(context).run(validRequest)

            // then
            assertThat(context.journaled).containsExactly("calculate", "charge")
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
            assertThat(context.stepWrites).containsExactly(WorkflowStep.CALCULATE, WorkflowStep.CHARGE, WorkflowStep.DONE)
            assertThat(workflow.status()).isEqualTo(WorkflowStep.DONE)
        }

    @Test
    fun `a crash injected before CHARGE stops the workflow before the fee is charged, with status CHARGE`() =
        runBlocking<Unit> {
            // given
            val crash = IllegalStateException("injected crash")
            val crashingHook =
                object : StepHook {
                    override fun before(step: WorkflowStep, transactionId: String) {
                        if (step == WorkflowStep.CHARGE) throw crash
                    }

                    override fun after(step: WorkflowStep, transactionId: String) = Unit
                }
            val records = startedRecords()
            val workflow = workflow(FakeWorkflowContext(), records, stepHook = crashingHook)

            // when / then
            assertThatThrownBy { runBlocking { workflow.run(validRequest) } }.isSameAs(crash)
            assertThat(records.calls).isEmpty()
            assertThat(workflow.status()).isEqualTo(WorkflowStep.CHARGE)
        }

    @Test
    fun `a failure inside CALCULATE propagates as a plain exception, so Restate retries it, and nothing is charged`() =
        runBlocking<Unit> {
            // given
            val outage = IllegalStateException("configuration unreadable")
            val records = startedRecords()
            val workflow = workflow(FakeWorkflowContext(failing = mapOf("calculate" to outage)), records)

            // when / then
            assertThatThrownBy { runBlocking { workflow.run(validRequest) } }.isSameAs(outage)
            assertThat(records.calls).isEmpty()
            assertThat(workflow.status()).isEqualTo(WorkflowStep.CALCULATE)
        }

    @Test
    fun `a Postgres failure at CHARGE propagates as a plain exception, so Restate retries it`() =
        runBlocking<Unit> {
            // given
            val outage = IllegalStateException("database unreachable")
            val records = startedRecords(failing = mapOf("charge" to outage))
            val workflow = workflow(FakeWorkflowContext(), records)

            // when / then
            assertThatThrownBy { runBlocking { workflow.run(validRequest) } }.isSameAs(outage)
            assertThat(records.records[TRANSACTION_ID]).isEqualTo(FeeRecord.pending(KEY))
            assertThat(workflow.status()).isEqualTo(WorkflowStep.CHARGE)
        }

    @Test
    fun `a record started for another workflow is not charged, and the failure propagates so Restate retries it`() =
        runBlocking<Unit> {
            // given
            val records = InMemoryFeeRecordRepository(TRANSACTION_ID to FeeRecord.pending("another-workflow"))
            val workflow = workflow(FakeWorkflowContext(), records)

            // when / then
            assertThatThrownBy { runBlocking { workflow.run(validRequest) } }.isInstanceOf(IllegalStateException::class.java)
            assertThat(records.records[TRANSACTION_ID]).isEqualTo(FeeRecord.pending("another-workflow"))
        }

    @Test
    fun `a request that passed validation but can't be mapped is a bug, thrown before CALCULATE`() {
        // given
        val records = startedRecords()
        val unmappable = validRequest.copy(createdAt = null)

        // when / then
        assertThatThrownBy { runBlocking { workflow(FakeWorkflowContext(), records).run(unmappable) } }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessage("Request for transaction 'txn_001' passed validation but could not be mapped")
        assertThat(records.calls).isEmpty()
        assertThat(hook.events).isEmpty()
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
    fun `reads its input and writes its response with Restate's Jackson serde, in snake case`() {
        // given
        val handlers = workflow(FakeWorkflowContext())

        @Suppress("UNCHECKED_CAST")
        val run =
            ServiceDefinitionFactories
                .discover(handlers)
                .create(handlers, null)
                .handlers
                .single { it.name == "run" } as HandlerDefinition<FeeRequest, FeeResponse>
        val input = """{"transaction_id":"txn_001","amount":1000}""".toByteArray()

        // when
        val request = run.requestSerde.deserialize(Slice.wrap(input))
        val output = String(run.responseSerde.serialize(expectedResponse).toByteArray())

        // then
        assertThat(request).isEqualTo(FeeRequest(transactionId = "txn_001", amount = BigDecimal("1000")))
        assertThat(output).isEqualTo(
            """{"transaction_id":"txn_001","amount":1000,"asset":"USD","type":"Mobile Top Up",""" +
                """"fee":1.50,"rate":0.0015,"description":"Standard fee rate of 0.15%"}""",
        )
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

    private fun startedRecords(failing: Map<String, Exception> = emptyMap()) =
        InMemoryFeeRecordRepository(TRANSACTION_ID to FeeRecord.pending(KEY), failing = failing)

    private fun workflow(context: FeeWorkflowContext, records: InMemoryFeeRecordRepository = startedRecords(), stepHook: StepHook = hook) =
        FeeWorkflow(TransactionMapper(), allRulesCalculator(), records, stepHook, context)

    private class RecordingStepHook : StepHook {
        val events = mutableListOf<String>()

        override fun before(step: WorkflowStep, transactionId: String) {
            events += "before $step $transactionId"
        }

        override fun after(step: WorkflowStep, transactionId: String) {
            events += "after $step $transactionId"
        }
    }

    /**
     * The workflow [KEY] in memory: the step state is a list, and a journaled action runs at once (or throws what
     * [failing] maps its name to).
     */
    private class FakeWorkflowContext(
        private val failing: Map<String, Exception> = emptyMap(),
    ) : FeeWorkflowContext {
        val journaled = mutableListOf<String>()
        val stepWrites = mutableListOf<WorkflowStep>()

        override suspend fun key(): String = KEY

        override suspend fun <T> journaled(name: String, type: TypeTag<T>, action: suspend () -> T): T {
            journaled += name
            failing[name]?.let { throw it }
            return action()
        }

        override suspend fun setStep(step: WorkflowStep) {
            stepWrites += step
        }

        override suspend fun step(): WorkflowStep? = stepWrites.lastOrNull()
    }

    private companion object {
        const val KEY = "0b5c3d4e-1f2a-4b3c-8d9e-0f1a2b3c4d5e"
        const val TRANSACTION_ID = "txn_001"
    }
}
