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
import com.example.fee.wallet.ChargeDeclinedException
import com.example.fee.wallet.ChargeReceipt
import com.example.fee.wallet.WalletGateway
import dev.restate.sdk.SharedWorkflowContext
import dev.restate.sdk.WorkflowContext
import dev.restate.sdk.annotation.Shared
import dev.restate.sdk.annotation.Workflow
import dev.restate.sdk.common.StateKey
import dev.restate.sdk.common.TerminalException
import org.springframework.stereotype.Component

/**
 * Processes the fee of one payload (the workflow key is `{transaction_id}:{payloadHash}`): VALIDATE → MAP → CLAIM →
 * CALCULATE → CHARGE → RECORD, each a journaled step wrapped by [StepHook]. On a retry Restate replays the
 * journaled steps instead of running them again, so the wallet is charged once.
 *
 * Business results are returned as [FeeOutcome], a declined charge too: it is recorded as failed and returned as
 * [FeeOutcome.Failed]. The handler is deterministic: the clock, the fee numbers and the wallet are only touched inside
 * `ctx.run`.
 */
// Breaking changes for in-flight workflows: the handler names and signatures, the step order, the ctx.run names and
// result types, the CLAIM and RECORD calls, and the state key name.
@Component
@Workflow
class FeeWorkflow(
    private val requestValidator: RequestValidator,
    private val transactionMapper: TransactionMapper,
    private val feeCalculator: FeeCalculator,
    private val walletGateway: WalletGateway,
    private val stepHook: StepHook,
) {

    /** Validates, claims, prices, charges and records the fee of the request in [input]. */
    @Workflow
    fun run(ctx: WorkflowContext, input: FeeWorkflowInput): FeeOutcome {
        val outcome = process(ctx, input)
        ctx.set(STEP, WorkflowStep.DONE)
        return outcome
    }

    /** The step the workflow is at, [WorkflowStep.DONE] once it returned, or `null` if it never started. */
    @Shared
    fun status(ctx: SharedWorkflowContext): WorkflowStep? = ctx.get(STEP).orElse(null)

    private fun process(ctx: WorkflowContext, input: FeeWorkflowInput): FeeOutcome {
        val error = step(ctx, WorkflowStep.VALIDATE) {
            ctx.run("validate", ValidationError::class.java) { requestValidator.validate(input.request) }
        }
        if (error != null) return FeeOutcome.Rejected(error)

        val transaction = step(ctx, WorkflowStep.MAP) { map(input.request) }
        val record = TransactionFeeRecordClient.fromContext(ctx, transaction.transactionId)

        val claim = step(ctx, WorkflowStep.CLAIM) { record.claim(input.payloadHash).await() }
        when (claim) {
            ClaimResult.New -> Unit
            is ClaimResult.AlreadyCompleted -> return FeeOutcome.Charged(claim.response)
            ClaimResult.InProgress, ClaimResult.Conflict -> return FeeOutcome.Conflict(transaction.transactionId)
        }

        val quote = step(ctx, WorkflowStep.CALCULATE) {
            ctx.run("calculate", FeeQuote::class.java) { feeCalculator.calculate(transaction) }
        }
        val declineReason = step(ctx, WorkflowStep.CHARGE) { charge(ctx, transaction, quote) }
        if (declineReason != null) {
            step(ctx, WorkflowStep.RECORD) { record.fail(declineReason).await() }
            return FeeOutcome.Failed(declineReason)
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

    // Returns the decline reason, or null once charged. A decline must become a TerminalException inside the closure: a
    // plain exception escaping ctx.run is retried, a terminal one is journaled and rethrown on every replay. Any
    // other failure propagates: plain ones are retried, and other terminal ones (a cancellation) are not a decline.
    private fun charge(ctx: WorkflowContext, transaction: Transaction, quote: FeeQuote): String? =
        try {
            ctx.run("charge", ChargeReceipt::class.java) {
                try {
                    walletGateway.charge(chargeKey(transaction.transactionId), quote.fee, transaction.asset)
                } catch (e: ChargeDeclinedException) {
                    throw TerminalException(CHARGE_DECLINED_CODE, e.reason)
                }
            }
            null
        } catch (e: TerminalException) {
            if (e.code != CHARGE_DECLINED_CODE) throw e
            e.message.orEmpty()
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

        /**
         * The code a declined charge is journaled with as a `TerminalException`, so a decline is told apart from other
         * terminal failures such as a cancellation (409). Journaled: changing it breaks declined in-flight workflows.
         */
        internal const val CHARGE_DECLINED_CODE: Int = 422

        /** The wallet's idempotency key: one charge per `transaction_id`, whichever payload or run asks. */
        internal fun chargeKey(transactionId: String): String = "fee-$transactionId"
    }
}
