package com.example.fee.workflow

import com.example.fee.contract.FeeRequest
import com.example.fee.contract.FeeResponse
import com.example.fee.domain.Transaction
import com.example.fee.domain.TransactionMapper
import com.example.fee.fee.FeeCalculator
import com.example.fee.fee.FeeQuote
import com.example.fee.record.ClaimDecider
import com.example.fee.record.ClaimResult
import com.example.fee.record.FeeRecordRepository
import com.example.fee.restate.serde.KotlinJacksonSerdeFactory
import com.example.fee.validation.RequestValidator
import com.example.fee.validation.ValidationError
import dev.restate.sdk.annotation.CustomSerdeFactory
import dev.restate.sdk.annotation.Shared
import dev.restate.sdk.annotation.Workflow
import dev.restate.sdk.common.StateKey
import dev.restate.sdk.kotlin.context
import dev.restate.sdk.kotlin.state
import dev.restate.sdk.kotlin.stateKey
import dev.restate.sdk.kotlin.workflowKey
import dev.restate.serde.TypeTag
import dev.restate.serde.kotlinx.typeTag
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * Processes the fee of one transaction (the workflow key is its `transaction_id`): VALIDATE → MAP → CLAIM → CALCULATE →
 * RECORD, each a journaled step wrapped by [StepHook]. CLAIM and RECORD write the `transaction_id`'s fee record in
 * Postgres; RECORD charges the fee by storing it there. On a retry Restate replays the journaled steps instead of running
 * them again.
 *
 * Business results are returned as [FeeOutcome]. The handlers are deterministic: the clock, the fee numbers, the claim
 * token and Postgres are only reached inside [FeeWorkflowContext.journaled]. They reach Restate only through
 * [FeeWorkflowContext] and Postgres only through [FeeRecordRepository], so their logic is unit-tested without either.
 *
 * Breaking changes for in-flight workflows: the class and handler names and signatures, the step order, the journaled
 * names and result types, and the state key name.
 */
@Component
@Workflow
@CustomSerdeFactory(KotlinJacksonSerdeFactory::class)
class FeeWorkflow(
    private val requestValidator: RequestValidator,
    private val transactionMapper: TransactionMapper,
    private val feeCalculator: FeeCalculator,
    private val claimDecider: ClaimDecider,
    private val feeRecords: FeeRecordRepository,
    private val stepHook: StepHook,
    private val context: FeeWorkflowContext,
) {
    /**
     * Validates, claims, calculates, then charges the fee by recording it, for [request] exactly as the client sent it.
     * The request is journaled as the workflow input.
     */
    @Workflow
    suspend fun run(request: FeeRequest): FeeOutcome {
        val outcome = process(request)
        context.setStep(WorkflowStep.DONE)
        return outcome
    }

    /** The step the workflow is at, [WorkflowStep.DONE] once it returned, or `null` if it never started. */
    @Shared
    suspend fun status(): WorkflowStep? = context.step()

    private suspend fun process(request: FeeRequest): FeeOutcome {
        val error =
            step(WorkflowStep.VALIDATE) {
                context.journaled("validate", typeTag<ValidationError?>()) { requestValidator.validate(request) }
            }
        if (error != null) return FeeOutcome.Rejected(error)

        val transaction = step(WorkflowStep.MAP) { map(request) }
        val transactionId = transaction.transactionId

        // The token names this run in the fee record. Journaled before the claim, so a claim run again after a crash
        // between its commit and its journal entry finds its own row instead of someone else's.
        val claimToken = context.journaled("claim-token", typeTag<String>()) { UUID.randomUUID().toString() }
        val claim =
            step(WorkflowStep.CLAIM) {
                context.journaled("claim", typeTag<ClaimResult>()) {
                    claimDecider.decide(feeRecords.claim(transaction, claimToken), claimToken)
                }
            }
        when (claim) {
            ClaimResult.New -> Unit
            is ClaimResult.AlreadyCompleted -> return FeeOutcome.Charged(claim.response)
            ClaimResult.InProgress -> return FeeOutcome.Conflict(transactionId)
        }

        val quote =
            step(WorkflowStep.CALCULATE) {
                context.journaled("calculate", typeTag<FeeQuote>()) { feeCalculator.calculate(transaction) }
            }
        val response = response(transaction, quote)
        step(WorkflowStep.RECORD) {
            context.journaled("record", typeTag<Unit>()) { feeRecords.complete(transactionId, claimToken, response) }
        }
        return FeeOutcome.Charged(response)
    }

    // The state write and the hooks run on every replay too: the write is deterministic, and a hook that throws is how
    // tests crash the workflow at a step boundary.
    private suspend fun <T> step(step: WorkflowStep, action: suspend () -> T): T {
        context.setStep(step)
        val key = context.key()
        stepHook.before(step, key)
        val result = action()
        stepHook.after(step, key)
        return result
    }

    // Validation passed, so the mapper can't return null; if it does, that is a bug: a plain exception, retried until
    // Restate pauses the invocation.
    private fun map(request: FeeRequest): Transaction =
        checkNotNull(transactionMapper.toTransaction(request)) {
            "Request for transaction '${request.transactionId}' passed validation but could not be mapped"
        }

    private fun response(transaction: Transaction, quote: FeeQuote) =
        FeeResponse(
            transactionId = transaction.transactionId,
            amount = transaction.amount,
            asset = transaction.asset.value,
            type = transaction.type.value,
            fee = quote.fee,
            rate = quote.rate,
            description = quote.description,
        )
}

/**
 * What [FeeWorkflow] needs from Restate: its key, journaled actions and the current step in its state. The handlers
 * reach Restate only through this, so tests replace it with an in-memory fake.
 */
interface FeeWorkflowContext {
    /** The workflow key: the `transaction_id`. */
    suspend fun key(): String

    /**
     * Runs [action] once and journals its result as [name], serialized as [type]; a replay returns the journaled
     * result without running [action]. For anything non-deterministic or with side effects, such as the clock, the fee
     * numbers, a random token or Postgres. A failed [action] is retried; one that committed to Postgres but crashed
     * before its result was journaled runs again, so its writes must be safe to repeat.
     */
    suspend fun <T> journaled(name: String, type: TypeTag<T>, action: suspend () -> T): T

    /** Stores [step] as the step the workflow is at. */
    suspend fun setStep(step: WorkflowStep)

    /** The step last stored by [setStep], or `null` if the workflow never started. */
    suspend fun step(): WorkflowStep?
}

/**
 * The [FeeWorkflowContext] of the Restate invocation the calling coroutine runs in. Stateless: the Kotlin SDK finds the
 * invocation in the coroutine context, so one bean serves every invocation.
 */
@Component
class RestateFeeWorkflowContext : FeeWorkflowContext {
    override suspend fun key(): String = workflowKey()

    // context(), not the top-level runBlock: that one takes only non-null results, and VALIDATE journals a nullable
    // ValidationError, as it did before the Kotlin SDK.
    @Suppress("DEPRECATION")
    override suspend fun <T> journaled(name: String, type: TypeTag<T>, action: suspend () -> T): T =
        context().runBlock(type, name, block = action)

    override suspend fun setStep(step: WorkflowStep) = state().set(STEP, step)

    override suspend fun step(): WorkflowStep? = state().get(STEP)

    companion object {
        /** The state key of the current step. Stored state: renaming it hides the step of in-flight workflows. */
        internal val STEP: StateKey<WorkflowStep> = stateKey("step")
    }
}
