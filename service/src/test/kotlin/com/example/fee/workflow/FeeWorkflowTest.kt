package com.example.fee.workflow

import com.example.fee.contract.FeeRequest
import com.example.fee.contract.FeeResponse
import com.example.fee.domain.TransactionMapper
import com.example.fee.record.ClaimResult
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

/**
 * The handlers against an in-memory [FeeWorkflowContext]: journaled actions run at once, calls to
 * `TransactionFeeRecord` are recorded and answered by the test. Journaling, replay and recovery against a real Restate
 * are the ITs'.
 */
class FeeWorkflowTest {

    private val hook = RecordingStepHook()

    private val expectedResponse = FeeResponse(
        transactionId = "txn_001",
        amount = BigDecimal("1000"),
        asset = "USD",
        type = "Mobile Top Up",
        fee = BigDecimal("1.50"),
        rate = BigDecimal("0.0015"),
        description = "Standard fee rate of 0.15%",
    )

    @Test
    fun `charges the reference request and returns the fee response`() = runBlocking<Unit> {
        // given
        val context = FakeWorkflowContext(claim = ClaimResult.New)

        // when
        val outcome = workflow(context).run(validRequest)

        // then
        assertThat(outcome).isEqualTo(FeeOutcome.Charged(expectedResponse))
    }

    @Test
    fun `claims the transaction id, then records the response on the same fee record`() = runBlocking<Unit> {
        // given
        val context = FakeWorkflowContext(claim = ClaimResult.New)

        // when
        workflow(context).run(validRequest)

        // then
        assertThat(context.calls).containsExactly(
            Call("claim", "txn_001", null),
            Call("complete", "txn_001", expectedResponse),
        )
    }

    @Test
    fun `wraps every step with the step hook, in order, with the workflow key`() = runBlocking<Unit> {
        // given
        val context = FakeWorkflowContext(claim = ClaimResult.New)

        // when
        workflow(context).run(validRequest)

        // then
        assertThat(hook.events).containsExactly(
            "before VALIDATE $KEY", "after VALIDATE $KEY",
            "before MAP $KEY", "after MAP $KEY",
            "before CLAIM $KEY", "after CLAIM $KEY",
            "before CALCULATE $KEY", "after CALCULATE $KEY",
            "before RECORD $KEY", "after RECORD $KEY",
        )
    }

    @Test
    fun `journals validation and calculation, and only those`() = runBlocking<Unit> {
        // given
        val context = FakeWorkflowContext(claim = ClaimResult.New)

        // when
        workflow(context).run(validRequest)

        // then
        assertThat(context.journaled).containsExactly("validate", "calculate")
    }

    @Test
    fun `stores each step in workflow state before it runs, and DONE at the end`() = runBlocking<Unit> {
        // given
        val context = FakeWorkflowContext(claim = ClaimResult.New)
        val workflow = workflow(context)

        // when
        workflow.run(validRequest)

        // then
        assertThat(context.stepWrites).containsExactly(
            WorkflowStep.VALIDATE, WorkflowStep.MAP, WorkflowStep.CLAIM, WorkflowStep.CALCULATE,
            WorkflowStep.RECORD, WorkflowStep.DONE,
        )
        assertThat(workflow.status()).isEqualTo(WorkflowStep.DONE)
    }

    @Test
    fun `returns a rejection without claiming or charging`() = runBlocking<Unit> {
        // given
        val error = ValidationError("state", "PENDING", "Unsupported state 'PENDING'", ValidationError.Kind.UNSUPPORTED)
        val context = FakeWorkflowContext(claim = ClaimResult.New)

        // when
        val outcome = workflow(context, validator = validatorReturning(error)).run(validRequest)

        // then
        assertThat(outcome).isEqualTo(FeeOutcome.Rejected(error))
        assertThat(context.calls).isEmpty()
        assertThat(context.stepWrites).containsExactly(WorkflowStep.VALIDATE, WorkflowStep.DONE)
        assertThat(hook.events).containsExactly("before VALIDATE $KEY", "after VALIDATE $KEY")
    }

    @Test
    fun `returns the stored response for a transaction that was already charged, without charging again`() = runBlocking<Unit> {
        // given
        val stored = expectedResponse.copy(fee = BigDecimal("1.50"), description = "charged earlier")
        val context = FakeWorkflowContext(claim = ClaimResult.AlreadyCompleted(stored))

        // when
        val outcome = workflow(context).run(validRequest)

        // then
        assertThat(outcome).isEqualTo(FeeOutcome.Charged(stored))
        assertThat(context.journaled).containsExactly("validate")
        assertThat(context.calls.map { it.handler }).containsExactly("claim")
        assertThat(context.stepWrites).containsExactly(
            WorkflowStep.VALIDATE, WorkflowStep.MAP, WorkflowStep.CLAIM, WorkflowStep.DONE,
        )
    }

    @Test
    fun `returns a conflict when an earlier run that never finished holds the claim, without charging`() = runBlocking<Unit> {
        // given
        val context = FakeWorkflowContext(claim = ClaimResult.InProgress)

        // when
        val outcome = workflow(context).run(validRequest)

        // then
        assertThat(outcome).isEqualTo(FeeOutcome.Conflict("txn_001"))
        assertThat(context.calls.map { it.handler }).containsExactly("claim")
        assertThat(context.stepWrites.last()).isEqualTo(WorkflowStep.DONE)
    }

    @Test
    fun `a crash injected before RECORD stops the workflow before the fee is recorded, with status RECORD`() = runBlocking<Unit> {
        // given
        val crash = IllegalStateException("injected crash")
        val crashingHook = object : StepHook {
            override fun before(step: WorkflowStep, workflowKey: String) {
                if (step == WorkflowStep.RECORD) throw crash
            }

            override fun after(step: WorkflowStep, workflowKey: String) = Unit
        }
        val context = FakeWorkflowContext(claim = ClaimResult.New)
        val workflow = workflow(context, stepHook = crashingHook)

        // when / then
        assertThatThrownBy { runBlocking { workflow.run(validRequest) } }.isSameAs(crash)
        assertThat(context.calls.map { it.handler }).containsExactly("claim")
        assertThat(workflow.status()).isEqualTo(WorkflowStep.RECORD)
    }

    @Test
    fun `a failure inside CALCULATE propagates as a plain exception, so Restate retries it, and nothing is recorded`() =
        runBlocking<Unit> {
            // given
            val outage = IllegalStateException("configuration unreadable")
            val context = FakeWorkflowContext(claim = ClaimResult.New, failing = mapOf("calculate" to outage))
            val workflow = workflow(context)

            // when / then
            assertThatThrownBy { runBlocking { workflow.run(validRequest) } }.isSameAs(outage)
            assertThat(context.calls.map { it.handler }).containsExactly("claim")
            assertThat(workflow.status()).isEqualTo(WorkflowStep.CALCULATE)
        }

    @Test
    fun `a request that passed validation but can't be mapped is a bug, thrown before the claim`() {
        // given
        val context = FakeWorkflowContext(claim = ClaimResult.New)
        val unmappable = validRequest.copy(createdAt = null)

        // when / then
        assertThatThrownBy { runBlocking { workflow(context).run(unmappable) } }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessage("Request for transaction 'txn_001' passed validation but could not be mapped")
        assertThat(context.calls).isEmpty()
    }

    @Test
    fun `status is null for a workflow that never started`() = runBlocking<Unit> {
        // given
        val context = FakeWorkflowContext(claim = ClaimResult.New)

        // when
        val status = workflow(context).status()

        // then
        assertThat(status).isNull()
    }

    @Test
    fun `is bound as a workflow with run and a shared status`() {
        // given
        val handlers = workflow(FakeWorkflowContext(claim = ClaimResult.New))

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
        val handlers = workflow(FakeWorkflowContext(claim = ClaimResult.New))
        @Suppress("UNCHECKED_CAST")
        val run = ServiceDefinitionFactories.discover(handlers).create(handlers, null).handlers
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
        validator: RequestValidator = validatorReturning(null),
        stepHook: StepHook = hook,
    ) = FeeWorkflow(validator, TransactionMapper(), allRulesCalculator(), stepHook, context)

    private fun validatorReturning(error: ValidationError?) = RequestValidator(
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

    /** A call the workflow made to `TransactionFeeRecord`: the handler, the object key, and the input. */
    private data class Call(val handler: String, val key: String, val input: Any?)

    /**
     * The workflow `txn_001` in memory: the step state is a list, a journaled action runs at once (or throws what
     * [failing] maps its name to), and `TransactionFeeRecord.claim` answers [claim].
     */
    private class FakeWorkflowContext(
        private val claim: ClaimResult,
        private val failing: Map<String, Exception> = emptyMap(),
    ) : FeeWorkflowContext {
        val journaled = mutableListOf<String>()
        val calls = mutableListOf<Call>()
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

        override suspend fun claim(transactionId: String): ClaimResult {
            calls += Call("claim", transactionId, null)
            return claim
        }

        override suspend fun complete(transactionId: String, response: FeeResponse) {
            calls += Call("complete", transactionId, response)
        }
    }

    private companion object {
        const val KEY = "txn_001"
    }
}
