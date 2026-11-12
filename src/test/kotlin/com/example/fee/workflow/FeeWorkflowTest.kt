@file:Suppress("DEPRECATION") // the ctx-based codegen API of SDK 2.9.3 (D29)

package com.example.fee.workflow

import com.example.fee.config.FakeWalletProperties
import com.example.fee.contract.FeeRequest
import com.example.fee.contract.FeeResponse
import com.example.fee.domain.Asset
import com.example.fee.domain.TransactionMapper
import com.example.fee.record.ClaimResult
import com.example.fee.record.FeeRecords.HASH
import com.example.fee.validation.RequestValidator
import com.example.fee.validation.TransactionValidator
import com.example.fee.validation.ValidationError
import com.example.fee.validation.allRulesCalculator
import com.example.fee.validation.validRequest
import com.example.fee.wallet.ChargeDeclinedException
import com.example.fee.wallet.ChargeReceipt
import com.example.fee.wallet.FakeWalletGateway
import com.example.fee.wallet.WalletGateway
import com.example.fee.workflow.FeeWorkflow.Companion.STEP
import dev.restate.common.Request
import dev.restate.common.Target
import dev.restate.common.function.ThrowingSupplier
import dev.restate.sdk.CallDurableFuture
import dev.restate.sdk.WorkflowContext
import dev.restate.sdk.common.TerminalException
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
import java.time.Duration
import java.util.Optional

/**
 * The handler against an in-memory workflow context: `ctx.run` executes its closure, calls to `TransactionFeeRecord`
 * are recorded and answered by the test. Journaling, replay and recovery against a real Restate are the ITs' (section 7).
 */
class FeeWorkflowTest {

    private val wallet = FakeWalletGateway(FakeWalletProperties(Duration.ZERO))
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
        val outcome = workflow().run(ctx.ctx, input())

        // then
        assertThat(outcome).isEqualTo(FeeOutcome.Charged(expectedResponse))
        assertThat(wallet.chargeCount("fee-txn_001")).isEqualTo(1)
    }

    @Test
    fun `charges the calculated fee in the transaction's asset under the transaction's idempotency key`() {
        // given
        val recordingWallet = RecordingWallet()
        val ctx = FakeWorkflowContext(claim = ClaimResult.New)

        // when
        workflow(walletGateway = recordingWallet).run(ctx.ctx, input())

        // then
        assertThat(recordingWallet.charges).hasSize(1)
        val (key, amount, asset) = recordingWallet.charges.single()
        assertThat(key).isEqualTo("fee-txn_001")
        assertThat(amount).isEqualByComparingTo(BigDecimal("1.5"))
        assertThat(asset).isEqualTo(Asset.USD)
    }

    @Test
    fun `claims the transaction id for the payload hash, then records the response on the same fee record`() {
        // given
        val ctx = FakeWorkflowContext(claim = ClaimResult.New)

        // when
        workflow().run(ctx.ctx, input())

        // then
        assertThat(ctx.calls.map { it.target }).containsExactly(
            Target.virtualObject("TransactionFeeRecord", "txn_001", "claim"),
            Target.virtualObject("TransactionFeeRecord", "txn_001", "complete"),
        )
        assertThat(ctx.calls.map { it.request }).containsExactly(HASH, expectedResponse)
    }

    @Test
    fun `wraps every step with the step hook, in order, with the workflow key`() {
        // given
        val ctx = FakeWorkflowContext(claim = ClaimResult.New)

        // when
        workflow().run(ctx.ctx, input())

        // then
        assertThat(hook.events).containsExactly(
            "before VALIDATE $KEY", "after VALIDATE $KEY",
            "before MAP $KEY", "after MAP $KEY",
            "before CLAIM $KEY", "after CLAIM $KEY",
            "before CALCULATE $KEY", "after CALCULATE $KEY",
            "before CHARGE $KEY", "after CHARGE $KEY",
            "before RECORD $KEY", "after RECORD $KEY",
        )
    }

    @Test
    fun `journals validation, calculation and the charge, and only those, with ctx run`() {
        // given
        val ctx = FakeWorkflowContext(claim = ClaimResult.New)

        // when
        workflow().run(ctx.ctx, input())

        // then
        assertThat(ctx.runs).containsExactly("validate", "calculate", "charge")
    }

    @Test
    fun `stores each step in workflow state before it runs, and DONE at the end`() {
        // given
        val ctx = FakeWorkflowContext(claim = ClaimResult.New)

        // when
        workflow().run(ctx.ctx, input())

        // then
        assertThat(ctx.stepWrites).containsExactly(
            WorkflowStep.VALIDATE, WorkflowStep.MAP, WorkflowStep.CLAIM, WorkflowStep.CALCULATE,
            WorkflowStep.CHARGE, WorkflowStep.RECORD, WorkflowStep.DONE,
        )
        assertThat(workflow().status(ctx.ctx)).isEqualTo(WorkflowStep.DONE)
    }

    @Test
    fun `returns a rejection without claiming or charging, and the id stays free`() {
        // given
        val error = ValidationError("state", "PENDING", "Unsupported state 'PENDING'", ValidationError.Kind.UNSUPPORTED)
        val ctx = FakeWorkflowContext(claim = ClaimResult.New)

        // when
        val outcome = workflow(validator = validatorReturning(error)).run(ctx.ctx, input())

        // then
        assertThat(outcome).isEqualTo(FeeOutcome.Rejected(error))
        assertThat(ctx.calls).isEmpty()
        assertThat(wallet.chargeCount("fee-txn_001")).isZero()
        assertThat(ctx.stepWrites).containsExactly(WorkflowStep.VALIDATE, WorkflowStep.DONE)
        assertThat(hook.events).containsExactly("before VALIDATE $KEY", "after VALIDATE $KEY")
    }

    @Test
    fun `returns the stored response for a payload that was already charged, without charging again`() {
        // given
        val stored = expectedResponse.copy(fee = BigDecimal("1.50"), description = "charged earlier")
        val ctx = FakeWorkflowContext(claim = ClaimResult.AlreadyCompleted(stored))

        // when
        val outcome = workflow().run(ctx.ctx, input())

        // then
        assertThat(outcome).isEqualTo(FeeOutcome.Charged(stored))
        assertThat(wallet.chargeCount("fee-txn_001")).isZero()
        assertThat(ctx.calls.map { it.target.handler }).containsExactly("claim")
        assertThat(ctx.stepWrites).containsExactly(
            WorkflowStep.VALIDATE, WorkflowStep.MAP, WorkflowStep.CLAIM, WorkflowStep.DONE,
        )
    }

    @Test
    fun `returns a conflict when another payload was charged for the id, without charging`() {
        // given
        val ctx = FakeWorkflowContext(claim = ClaimResult.Conflict)

        // when
        val outcome = workflow().run(ctx.ctx, input())

        // then
        assertThat(outcome).isEqualTo(FeeOutcome.Conflict("txn_001"))
        assertThat(wallet.chargeCount("fee-txn_001")).isZero()
        assertThat(ctx.calls.map { it.target.handler }).containsExactly("claim")
    }

    @Test
    fun `returns a conflict when another payload holds the claim, without charging`() {
        // given
        val ctx = FakeWorkflowContext(claim = ClaimResult.InProgress)

        // when
        val outcome = workflow().run(ctx.ctx, input())

        // then
        assertThat(outcome).isEqualTo(FeeOutcome.Conflict("txn_001"))
        assertThat(wallet.chargeCount("fee-txn_001")).isZero()
        assertThat(ctx.stepWrites.last()).isEqualTo(WorkflowStep.DONE)
    }

    @Test
    fun `a crash injected before CHARGE stops the workflow before the wallet is called, with status CHARGE`() {
        // given
        val crash = IllegalStateException("injected crash")
        val crashingHook = object : StepHook {
            override fun before(step: WorkflowStep, workflowKey: String) {
                if (step == WorkflowStep.CHARGE) throw crash
            }

            override fun after(step: WorkflowStep, workflowKey: String) = Unit
        }
        val ctx = FakeWorkflowContext(claim = ClaimResult.New)
        val workflow = workflow(stepHook = crashingHook)

        // when / then
        assertThatThrownBy { workflow.run(ctx.ctx, input()) }.isSameAs(crash)
        assertThat(wallet.chargeCount("fee-txn_001")).isZero()
        assertThat(workflow.status(ctx.ctx)).isEqualTo(WorkflowStep.CHARGE)
    }

    @Test
    fun `a declined charge is recorded as failed and returned as Failed with the wallet's reason`() {
        // given
        val ctx = FakeWorkflowContext(claim = ClaimResult.New)

        // when
        val outcome = workflow(walletGateway = DecliningWallet("insufficient funds")).run(ctx.ctx, input())

        // then
        assertThat(outcome).isEqualTo(FeeOutcome.Failed("insufficient funds"))
        assertThat(ctx.calls).containsExactly(
            Call(Target.virtualObject("TransactionFeeRecord", "txn_001", "claim"), HASH),
            Call(Target.virtualObject("TransactionFeeRecord", "txn_001", "fail"), "insufficient funds"),
        )
        assertThat(ctx.stepWrites).containsExactly(
            WorkflowStep.VALIDATE, WorkflowStep.MAP, WorkflowStep.CLAIM, WorkflowStep.CALCULATE,
            WorkflowStep.CHARGE, WorkflowStep.RECORD, WorkflowStep.DONE,
        )
        assertThat(hook.events.takeLast(4)).containsExactly(
            "before CHARGE $KEY", "after CHARGE $KEY", "before RECORD $KEY", "after RECORD $KEY",
        )
    }

    @Test
    fun `a decline leaves the charge closure as a terminal exception with the decline code, so Restate doesn't retry it`() {
        // given
        val ctx = FakeWorkflowContext(claim = ClaimResult.New)

        // when
        workflow(walletGateway = DecliningWallet("card blocked")).run(ctx.ctx, input())

        // then
        val thrown = ctx.runFailures.getValue("charge")
        assertThat(thrown).isInstanceOf(TerminalException::class.java).hasMessage("card blocked")
        assertThat((thrown as TerminalException).code).isEqualTo(FeeWorkflow.CHARGE_DECLINED_CODE)
    }

    @Test
    fun `a decline replayed from the journal as a terminal exception is recorded as failed without calling the wallet`() {
        // given
        val ctx = FakeWorkflowContext(claim = ClaimResult.New)
        doThrow(TerminalException(FeeWorkflow.CHARGE_DECLINED_CODE, "insufficient funds"))
            .`when`(ctx.ctx).run(eq("charge"), any<Class<Any>>(), any<ThrowingSupplier<Any>>())
        val recordingWallet = RecordingWallet()

        // when
        val outcome = workflow(walletGateway = recordingWallet).run(ctx.ctx, input())

        // then
        assertThat(outcome).isEqualTo(FeeOutcome.Failed("insufficient funds"))
        assertThat(recordingWallet.charges).isEmpty()
        assertThat(ctx.calls.map { it.target.handler }).containsExactly("claim", "fail")
    }

    @Test
    fun `a transient wallet failure propagates as a plain exception, so Restate retries it, and nothing is recorded`() {
        // given
        val outage = IllegalStateException("wallet unreachable")
        val ctx = FakeWorkflowContext(claim = ClaimResult.New)
        val failingWallet = object : WalletGateway {
            override fun charge(idempotencyKey: String, amount: BigDecimal, asset: Asset): ChargeReceipt = throw outage
        }
        val workflow = workflow(walletGateway = failingWallet)

        // when / then
        assertThatThrownBy { workflow.run(ctx.ctx, input()) }.isSameAs(outage)
        assertThat(ctx.runFailures.getValue("charge")).isSameAs(outage)
        assertThat(ctx.calls.map { it.target.handler }).containsExactly("claim")
        assertThat(workflow.status(ctx.ctx)).isEqualTo(WorkflowStep.CHARGE)
    }

    @Test
    fun `a terminal failure of the charge that isn't a decline propagates and isn't recorded as failed`() {
        // given
        val cancelled = TerminalException(TerminalException.CANCELLED_CODE, "cancelled")
        val ctx = FakeWorkflowContext(claim = ClaimResult.New)
        doThrow(cancelled).`when`(ctx.ctx).run(eq("charge"), any<Class<Any>>(), any<ThrowingSupplier<Any>>())

        // when / then
        assertThatThrownBy { workflow().run(ctx.ctx, input()) }.isSameAs(cancelled)
        assertThat(ctx.calls.map { it.target.handler }).containsExactly("claim")
        assertThat(wallet.chargeCount("fee-txn_001")).isZero()
    }

    @Test
    fun `a request that passed validation but can't be mapped is a bug, thrown before the claim`() {
        // given
        val ctx = FakeWorkflowContext(claim = ClaimResult.New)
        val unmappable = validRequest.copy(createdAt = null)

        // when / then
        assertThatThrownBy { workflow().run(ctx.ctx, FeeWorkflowInput(unmappable, HASH)) }
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
        walletGateway: WalletGateway = wallet,
        stepHook: StepHook = hook,
    ) = FeeWorkflow(validator, TransactionMapper(), allRulesCalculator(), walletGateway, stepHook)

    private fun input(request: FeeRequest = validRequest) = FeeWorkflowInput(request, HASH)

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

    private class RecordingWallet : WalletGateway {
        val charges = mutableListOf<Triple<String, BigDecimal, Asset>>()

        override fun charge(idempotencyKey: String, amount: BigDecimal, asset: Asset): ChargeReceipt {
            charges += Triple(idempotencyKey, amount, asset)
            return ChargeReceipt("charge-1", idempotencyKey, amount, asset)
        }
    }

    private class DecliningWallet(private val reason: String) : WalletGateway {
        override fun charge(idempotencyKey: String, amount: BigDecimal, asset: Asset): ChargeReceipt =
            throw ChargeDeclinedException(idempotencyKey, reason)
    }

    /** A call the workflow made to another handler: where to, and with which input. */
    private data class Call(val target: Target, val request: Any?)

    /**
     * The workflow `txn_001:hash-a` behind a mocked [WorkflowContext]: state in memory, `ctx.run` runs its closure at
     * once, and a call to `TransactionFeeRecord.claim` answers [claim].
     */
    private class FakeWorkflowContext(claim: ClaimResult) {
        val runs = mutableListOf<String>()

        /** What each failed `ctx.run` closure threw, by run name: what Restate would journal or retry. */
        val runFailures = mutableMapOf<String, Throwable>()
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
                val name = invocation.getArgument<String>(0)
                runs += name
                try {
                    invocation.getArgument<ThrowingSupplier<Any>>(2).get()
                } catch (e: Throwable) {
                    runFailures[name] = e
                    throw e
                }
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
        const val KEY = "txn_001:$HASH"
    }
}
