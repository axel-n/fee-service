@file:Suppress("DEPRECATION") // the ctx-based codegen API of SDK 2.9.3

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
import com.example.fee.workflow.FeeWorkflow.Companion.STEP
import dev.restate.common.Request
import dev.restate.common.Target
import dev.restate.common.function.ThrowingSupplier
import dev.restate.sdk.CallDurableFuture
import dev.restate.sdk.WorkflowContext
import dev.restate.sdk.endpoint.definition.HandlerType
import dev.restate.sdk.endpoint.definition.ServiceType
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyString
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mockito.`when`
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.mock
import java.math.BigDecimal
import java.util.Optional

/**
 * The handler against an in-memory workflow context: `ctx.run` executes its closure, calls to `TransactionFeeRecord`
 * are recorded and answered by the test. Journaling, replay and recovery against a real Restate are the ITs' (section 7).
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
    fun `charges the reference request and returns the fee response`() {
        // given
        val ctx = FakeWorkflowContext(claim = ClaimResult.New)

        // when
        val outcome = workflow().run(ctx.ctx, validRequest)

        // then
        assertThat(outcome).isEqualTo(FeeOutcome.Charged(expectedResponse))
    }

    @Test
    fun `claims the transaction id, then records the response on the same fee record`() {
        // given
        val ctx = FakeWorkflowContext(claim = ClaimResult.New)

        // when
        workflow().run(ctx.ctx, validRequest)

        // then
        assertThat(ctx.calls.map { it.target }).containsExactly(
            Target.virtualObject("TransactionFeeRecord", "txn_001", "claim"),
            Target.virtualObject("TransactionFeeRecord", "txn_001", "complete"),
        )
        assertThat(ctx.calls.last().request).isEqualTo(expectedResponse)
    }

    @Test
    fun `wraps every step with the step hook, in order, with the workflow key`() {
        // given
        val ctx = FakeWorkflowContext(claim = ClaimResult.New)

        // when
        workflow().run(ctx.ctx, validRequest)

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
    fun `journals validation and calculation, and only those, with ctx run`() {
        // given
        val ctx = FakeWorkflowContext(claim = ClaimResult.New)

        // when
        workflow().run(ctx.ctx, validRequest)

        // then
        assertThat(ctx.runs).containsExactly("validate", "calculate")
    }

    @Test
    fun `stores each step in workflow state before it runs, and DONE at the end`() {
        // given
        val ctx = FakeWorkflowContext(claim = ClaimResult.New)

        // when
        workflow().run(ctx.ctx, validRequest)

        // then
        assertThat(ctx.stepWrites).containsExactly(
            WorkflowStep.VALIDATE, WorkflowStep.MAP, WorkflowStep.CLAIM, WorkflowStep.CALCULATE,
            WorkflowStep.RECORD, WorkflowStep.DONE,
        )
        assertThat(workflow().status(ctx.ctx)).isEqualTo(WorkflowStep.DONE)
    }

    @Test
    fun `returns a rejection without claiming or charging`() {
        // given
        val error = ValidationError("state", "PENDING", "Unsupported state 'PENDING'", ValidationError.Kind.UNSUPPORTED)
        val ctx = FakeWorkflowContext(claim = ClaimResult.New)

        // when
        val outcome = workflow(validator = validatorReturning(error)).run(ctx.ctx, validRequest)

        // then
        assertThat(outcome).isEqualTo(FeeOutcome.Rejected(error))
        assertThat(ctx.calls).isEmpty()
        assertThat(ctx.stepWrites).containsExactly(WorkflowStep.VALIDATE, WorkflowStep.DONE)
        assertThat(hook.events).containsExactly("before VALIDATE $KEY", "after VALIDATE $KEY")
    }

    @Test
    fun `returns the stored response for a transaction that was already charged, without charging again`() {
        // given
        val stored = expectedResponse.copy(fee = BigDecimal("1.50"), description = "charged earlier")
        val ctx = FakeWorkflowContext(claim = ClaimResult.AlreadyCompleted(stored))

        // when
        val outcome = workflow().run(ctx.ctx, validRequest)

        // then
        assertThat(outcome).isEqualTo(FeeOutcome.Charged(stored))
        assertThat(ctx.runs).containsExactly("validate")
        assertThat(ctx.calls.map { it.target.handler }).containsExactly("claim")
        assertThat(ctx.stepWrites).containsExactly(
            WorkflowStep.VALIDATE, WorkflowStep.MAP, WorkflowStep.CLAIM, WorkflowStep.DONE,
        )
    }

    @Test
    fun `returns a conflict when an earlier run that never finished holds the claim, without charging`() {
        // given
        val ctx = FakeWorkflowContext(claim = ClaimResult.InProgress)

        // when
        val outcome = workflow().run(ctx.ctx, validRequest)

        // then
        assertThat(outcome).isEqualTo(FeeOutcome.Conflict("txn_001"))
        assertThat(ctx.calls.map { it.target.handler }).containsExactly("claim")
        assertThat(ctx.stepWrites.last()).isEqualTo(WorkflowStep.DONE)
    }

    @Test
    fun `a crash injected before RECORD stops the workflow before the fee is recorded, with status RECORD`() {
        // given
        val crash = IllegalStateException("injected crash")
        val crashingHook = object : StepHook {
            override fun before(step: WorkflowStep, workflowKey: String) {
                if (step == WorkflowStep.RECORD) throw crash
            }

            override fun after(step: WorkflowStep, workflowKey: String) = Unit
        }
        val ctx = FakeWorkflowContext(claim = ClaimResult.New)
        val workflow = workflow(stepHook = crashingHook)

        // when / then
        assertThatThrownBy { workflow.run(ctx.ctx, validRequest) }.isSameAs(crash)
        assertThat(ctx.calls.map { it.target.handler }).containsExactly("claim")
        assertThat(workflow.status(ctx.ctx)).isEqualTo(WorkflowStep.RECORD)
    }

    @Test
    fun `a failure inside CALCULATE propagates as a plain exception, so Restate retries it, and nothing is recorded`() {
        // given
        val outage = IllegalStateException("configuration unreadable")
        val ctx = FakeWorkflowContext(claim = ClaimResult.New)
        doThrow(outage).`when`(ctx.ctx).run(eq("calculate"), any<Class<Any>>(), any<ThrowingSupplier<Any>>())
        val workflow = workflow()

        // when / then
        assertThatThrownBy { workflow.run(ctx.ctx, validRequest) }.isSameAs(outage)
        assertThat(ctx.calls.map { it.target.handler }).containsExactly("claim")
        assertThat(workflow.status(ctx.ctx)).isEqualTo(WorkflowStep.CALCULATE)
    }

    @Test
    fun `a request that passed validation but can't be mapped is a bug, thrown before the claim`() {
        // given
        val ctx = FakeWorkflowContext(claim = ClaimResult.New)
        val unmappable = validRequest.copy(createdAt = null)

        // when / then
        assertThatThrownBy { workflow().run(ctx.ctx, unmappable) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessage("Request for transaction 'txn_001' passed validation but could not be mapped")
        assertThat(ctx.calls).isEmpty()
    }

    @Test
    fun `status is null for a workflow that never started`() {
        // given
        val ctx = FakeWorkflowContext(claim = ClaimResult.New)

        // when
        val status = workflow().status(ctx.ctx)

        // then
        assertThat(status).isNull()
    }

    @Test
    fun `is bound as a workflow with run and a shared status`() {
        // given
        val factory = FeeWorkflowServiceDefinitionFactory()
        val handlers = workflow()

        // when
        val definition = factory.create(handlers, null)

        // then
        assertThat(factory.supports(handlers)).isTrue()
        assertThat(definition.serviceName).isEqualTo("FeeWorkflow")
        assertThat(definition.serviceType).isEqualTo(ServiceType.WORKFLOW)
        assertThat(definition.handlers.associate { it.name to it.handlerType }).isEqualTo(
            mapOf("run" to HandlerType.WORKFLOW, "status" to HandlerType.SHARED),
        )
    }

    @Test
    fun `stores the step under the state key 'step'`() {
        // given
        val key = STEP

        // when
        val name = key.name()

        // then
        assertThat(name).isEqualTo("step")
    }

    private fun workflow(
        validator: RequestValidator = validatorReturning(null),
        stepHook: StepHook = hook,
    ) = FeeWorkflow(validator, TransactionMapper(), allRulesCalculator(), stepHook)

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


    /** A call the workflow made to another handler: where to, and with which input. */
    private data class Call(val target: Target, val request: Any?)

    /**
     * The workflow `txn_001` behind a mocked [WorkflowContext]: state in memory, `ctx.run` runs its closure at
     * once, and a call to `TransactionFeeRecord.claim` answers [claim].
     */
    private class FakeWorkflowContext(claim: ClaimResult) {
        val runs = mutableListOf<String>()
        val calls = mutableListOf<Call>()
        val stepWrites = mutableListOf<WorkflowStep>()

        val ctx: WorkflowContext = mock(WorkflowContext::class.java).also { ctx ->
            `when`(ctx.key()).thenReturn(KEY)
            `when`(ctx.get(STEP)).thenAnswer { Optional.ofNullable(stepWrites.lastOrNull()) }
            doAnswer { invocation ->
                check(invocation.getArgument<Any>(0) == STEP) { "unexpected state key ${invocation.getArgument<Any>(0)}" }
                stepWrites += invocation.getArgument<WorkflowStep>(1)
                null
            }.`when`(ctx).set(any(), any<WorkflowStep>())
            `when`(ctx.run(anyString(), any<Class<Any>>(), any<ThrowingSupplier<Any>>())).thenAnswer { invocation ->
                runs += invocation.getArgument<String>(0)
                invocation.getArgument<ThrowingSupplier<Any>>(2).get()
            }
            `when`(ctx.call(any<Request<Any, Any>>())).thenAnswer { invocation ->
                val request = invocation.getArgument<Request<Any, Any>>(0)
                calls += Call(request.target, request.request)
                future(if (request.target.handler == "claim") claim else null)
            }
        }

        private fun future(result: Any?): CallDurableFuture<Any> =
            @Suppress("UNCHECKED_CAST")
            (mock(CallDurableFuture::class.java) as CallDurableFuture<Any>).also { `when`(it.await()).thenReturn(result) }
    }

    private companion object {
        const val KEY = "txn_001"
    }
}
