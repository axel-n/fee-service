@file:Suppress("DEPRECATION") // the ctx-based codegen API of SDK 2.9.3, kept on purpose

package com.example.fee.application

import com.example.fee.config.RestateProperties
import com.example.fee.contract.FeeRequest
import com.example.fee.contract.FeeStatusResponse
import com.example.fee.contract.error.DuplicateTransactionException
import com.example.fee.contract.error.FeeException

import com.example.fee.contract.error.ValidationException
import com.example.fee.contract.error.WorkflowUnavailableException
import com.example.fee.restate.FeeWorkflowClients
import com.example.fee.validation.ValidationError
import com.example.fee.validation.rule.TransactionIdValidator
import com.example.fee.workflow.FeeOutcome
import com.example.fee.workflow.FeeWorkflowInput
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * The thin gateway between the API and `FeeWorkflow`: it checks the `transaction_id` it needs for the key, builds
 * the [WorkflowKey], submits the workflow and attaches to it, and maps the [FeeOutcome] to a `COMPLETED`
 * [FeeStatusResponse] or a [FeeException]. Every business decision is made inside the workflow.
 *
 * It always submits, then attaches: a resubmitted payload gets `PREVIOUSLY_ACCEPTED`, its input is ignored, and the
 * attach returns the first run's result, so the same payload is charged once. Submit and attach together wait
 * at most `restate.attach-timeout`. A run still going by then is a [FeeInProgressException]; a submit Restate didn't
 * acknowledge by then, or a Restate call that failed, is a [WorkflowUnavailableException], because resending the same
 * payload is safe.
 */
@Service
class FeeService(
    private val transactionIdValidator: TransactionIdValidator,
    private val feeWorkflowClients: FeeWorkflowClients,

    private val restateProperties: RestateProperties,
) {

    fun submit(request: FeeRequest): FeeStatusResponse {
        transactionIdValidator.validate(request)?.let { throw rejected(it) }
        val transactionId = checkNotNull(request.transactionId) { "transaction_id passed the pre-check but is null" }
        val payloadHash = PayloadHash.of(request)
        val key = WorkflowKey.of(transactionId, payloadHash)

        val client = feeWorkflowClients.forKey(key.value)
        val submitted = try {
            client.submitAsync(FeeWorkflowInput(request, payloadHash.value))
        } catch (e: RuntimeException) {
            throw unavailable("workflow $key", e)
        }
        val outcome = submitted.thenCompose { it.attachAsync() }.thenApply { it.response() }

        val result = try {
            outcome.get(restateProperties.attachTimeout.toMillis(), TimeUnit.MILLISECONDS)
        } catch (e: TimeoutException) {
            if (!accepted(submitted)) throw unavailable("workflow $key", e)
            log.info("Workflow {} still running after {}; answering 202", key, restateProperties.attachTimeout)
            throw FeeInProgressException(transactionId)
        } catch (e: ExecutionException) {
            throw unavailable("workflow $key", e.cause ?: e)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw unavailable("workflow $key", e)
        }
        return toResponse(checkNotNull(result) { "Workflow $key returned no outcome" })
    }

    private fun toResponse(outcome: FeeOutcome): FeeStatusResponse = when (outcome) {
        is FeeOutcome.Charged -> FeeStatusResponse.completed(outcome.response)
        is FeeOutcome.Rejected -> throw rejected(outcome.error)
        is FeeOutcome.Conflict -> throw DuplicateTransactionException(outcome.transactionId)
    }

    // ValidationError is the workflow's durable type, ValidationException the published contract: same fields.
    private fun rejected(error: ValidationError): ValidationException {
        val kind = when (error.kind) {
            ValidationError.Kind.INVALID -> ValidationException.Kind.INVALID
            ValidationError.Kind.UNSUPPORTED -> ValidationException.Kind.UNSUPPORTED
        }
        return ValidationException(error.field, error.value, error.message, kind)
    }

    private fun accepted(submitted: CompletableFuture<*>): Boolean =
        submitted.isDone && !submitted.isCompletedExceptionally

    private fun unavailable(target: String, cause: Throwable): WorkflowUnavailableException {
        log.warn("Restate call for {} failed: {}", target, cause.toString())
        return WorkflowUnavailableException(cause)
    }

    private companion object {
        private val log = LoggerFactory.getLogger(FeeService::class.java)
    }
}

/**
 * Thrown by [FeeService.submit] when the workflow is still running after `restate.attach-timeout`. It isn't a failure, so
 * it isn't a [FeeException]: `ApiExceptionHandler` answers 202 with the `IN_PROGRESS` status as body. Resubmitting the
 * same payload attaches to the same run, so it charges once. HTTP only, never stored.
 */
class FeeInProgressException(val transactionId: String) :
    // No stack trace: an expected outcome of every slow run, not a bug to locate.
    RuntimeException("The fee for transaction '$transactionId' is still being processed", null, false, false)
