@file:Suppress("DEPRECATION") // the ctx-based codegen API of SDK 2.9.3, kept on purpose

package com.example.fee.workflow

import com.example.fee.contract.FeeRequest
import com.example.fee.contract.FeeResponse
import com.example.fee.domain.Transaction
import com.example.fee.domain.TransactionMapper
import com.example.fee.fee.FeeCalculator
import com.example.fee.fee.FeeQuote
import com.example.fee.record.ClaimResult
import com.example.fee.record.TransactionFeeRecordClient
import com.example.fee.validation.RequestValidator
import com.example.fee.validation.ValidationError
import dev.restate.sdk.SharedWorkflowContext
import dev.restate.sdk.WorkflowContext
import dev.restate.sdk.annotation.Shared
import dev.restate.sdk.annotation.Workflow
import dev.restate.sdk.common.StateKey
import org.springframework.stereotype.Component

/**
 * Processes the fee of one transaction (the workflow key is its `transaction_id`): VALIDATE → MAP → CLAIM → CALCULATE →
 * RECORD, each a journaled step wrapped by [StepHook]. RECORD charges the fee by storing it in the `transaction_id`'s
 * fee record. On a retry Restate replays the journaled steps instead of running them again.
 *
 * Business results are returned as [FeeOutcome]. The handler is deterministic: the clock and the fee numbers are only
 * read inside `ctx.run`.
 */
// Breaking changes for in-flight workflows: the handler names and signatures, the step order, the ctx.run names and
// result types, the CLAIM and RECORD calls, and the state key name.
@Component
@Workflow
class FeeWorkflow(
    private val requestValidator: RequestValidator,
    private val transactionMapper: TransactionMapper,
    private val feeCalculator: FeeCalculator,
    private val stepHook: StepHook,
) {

    /**
     * Validates, claims, calculates, then charges the fee by recording it, for [request] exactly as the client sent it.
     * The request is journaled as the workflow input.
     */
    @Workflow
    fun run(ctx: WorkflowContext, request: FeeRequest): FeeOutcome {
        val outcome = process(ctx, request)
        ctx.set(STEP, WorkflowStep.DONE)
        return outcome
    }

    /** The step the workflow is at, [WorkflowStep.DONE] once it returned, or `null` if it never started. */
    @Shared
    fun status(ctx: SharedWorkflowContext): WorkflowStep? = ctx.get(STEP).orElse(null)

    private fun process(ctx: WorkflowContext, request: FeeRequest): FeeOutcome {
        val error = step(ctx, WorkflowStep.VALIDATE) {
            ctx.run("validate", ValidationError::class.java) { requestValidator.validate(request) }
        }
        if (error != null) return FeeOutcome.Rejected(error)

        val transaction = step(ctx, WorkflowStep.MAP) { map(request) }
        val record = TransactionFeeRecordClient.fromContext(ctx, transaction.transactionId)

        val claim = step(ctx, WorkflowStep.CLAIM) { record.claim().await() }
        when (claim) {
            ClaimResult.New -> Unit
            is ClaimResult.AlreadyCompleted -> return FeeOutcome.Charged(claim.response)
            ClaimResult.InProgress -> return FeeOutcome.Conflict(transaction.transactionId)
        }

        val quote = step(ctx, WorkflowStep.CALCULATE) {
            ctx.run("calculate", FeeQuote::class.java) { feeCalculator.calculate(transaction) }
        }
        val response = response(transaction, quote)
        step(ctx, WorkflowStep.RECORD) { record.complete(response).await() }
        return FeeOutcome.Charged(response)
    }

    // The state write and the hooks run on every replay too: the write is deterministic, and a hook that throws is how
    // tests crash the workflow at a step boundary.
    private fun <T> step(ctx: WorkflowContext, step: WorkflowStep, action: () -> T): T {
        ctx.set(STEP, step)
        stepHook.before(step, ctx.key())
        val result = action()
        stepHook.after(step, ctx.key())
        return result
    }

    // Validation passed, so the mapper can't return null; if it does, that is a bug: a plain exception, retried until
    // Restate pauses the invocation.
    private fun map(request: FeeRequest): Transaction =
        checkNotNull(transactionMapper.toTransaction(request)) {
            "Request for transaction '${request.transactionId}' passed validation but could not be mapped"
        }

    private fun response(transaction: Transaction, quote: FeeQuote) = FeeResponse(
        transactionId = transaction.transactionId,
        amount = transaction.amount,
        asset = transaction.asset.value,
        type = transaction.type.value,
        fee = quote.fee,
        rate = quote.rate,
        description = quote.description,
    )

    companion object {
        /** The state key of the current step. Stored state: renaming it hides the step of in-flight workflows. */
        internal val STEP: StateKey<WorkflowStep> = StateKey.of("step", WorkflowStep::class.java)
    }
}
