@file:Suppress("DEPRECATION") // the ctx-based codegen API of SDK 2.9.3, kept on purpose (D29)

package com.example.fee.application

import com.example.fee.contract.FeeRequest
import com.example.fee.contract.FeeStatusResponse
import com.example.fee.restate.FeeOutcome
import com.example.fee.restate.FeeRecord
import com.example.fee.restate.FeeRecordStatus
import com.example.fee.restate.FeeWorkflowClients
import com.example.fee.restate.FeeWorkflowInput
import com.example.fee.restate.RestateProperties
import com.example.fee.restate.TransactionFeeRecordClients
import com.example.fee.validation.TransactionIdValidator
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * The thin gateway between the API and `FeeWorkflow` (D7): it checks the `transaction_id` it needs for the key, builds
 * the [WorkflowKey], submits the workflow and attaches to it, and maps the [FeeOutcome] to a [FeeSubmission] or a
 * [FeeException]. Every business decision is made inside the workflow.
 *
 * It always submits, then attaches: a resubmitted payload gets `PREVIOUSLY_ACCEPTED`, its input is ignored, and the
 * attach returns the first run's result, so the same payload is charged once (D8, D30). Submit and attach together wait
 * at most `restate.attach-timeout`. A run still going by then is [FeeSubmission.Processing]; a submit Restate didn't
 * acknowledge by then, or a Restate call that failed, is a [WorkflowUnavailableException], because resending the same
 * payload is safe (D58).
 *
 * [status] reads the permanent fee record of a `transaction_id` for `GET /transaction/fee/{id}` (D59).
 */
@Service
class FeeService(
    private val transactionIdValidator: TransactionIdValidator,
    private val feeWorkflowClients: FeeWorkflowClients,
    private val transactionFeeRecordClients: TransactionFeeRecordClients,
    private val restateProperties: RestateProperties,
) {

    fun submit(request: FeeRequest): FeeSubmission {
        transactionIdValidator.validate(request)?.let { throw ValidationException(it) }
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
            return FeeSubmission.Processing(transactionId)
        } catch (e: ExecutionException) {
            throw unavailable("workflow $key", e.cause ?: e)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw unavailable("workflow $key", e)
        }
        return toSubmission(transactionId, checkNotNull(result) { "Workflow $key returned no outcome" })
    }

    /**
     * Where the fee for [transactionId] stands, read from `TransactionFeeRecord/{transactionId}` within
     * `restate.attach-timeout`. A malformed id is a 400 before Restate is called, an id without a record a
     * [TransactionNotFoundException], and a lookup that failed or got no answer in time a [WorkflowUnavailableException].
     */
    fun status(transactionId: String): FeeStatusResponse {
        transactionIdValidator.validate(FeeRequest(transactionId = transactionId))?.let { throw ValidationException(it) }
        val target = "fee record $transactionId"
        val record = try {
            transactionFeeRecordClients.forKey(transactionId).getAsync()
                .get(restateProperties.attachTimeout.toMillis(), TimeUnit.MILLISECONDS)
        } catch (e: TimeoutException) {
            throw unavailable(target, e)
        } catch (e: ExecutionException) {
            throw unavailable(target, e.cause ?: e)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw unavailable(target, e)
        } catch (e: RuntimeException) {
            throw unavailable(target, e)
        }
        return toStatus(transactionId, record ?: throw TransactionNotFoundException(transactionId))
    }

    private fun toSubmission(transactionId: String, outcome: FeeOutcome): FeeSubmission = when (outcome) {
        is FeeOutcome.Charged -> FeeSubmission.Completed(outcome.response)
        is FeeOutcome.Rejected -> throw ValidationException(outcome.error)
        is FeeOutcome.Conflict -> throw DuplicateTransactionException(outcome.transactionId)
        is FeeOutcome.Failed -> throw ChargeFailedException(transactionId, outcome.reason)
    }

    // FeeRecord guarantees the response exactly when COMPLETED and the reason exactly when FAILED (D47).
    private fun toStatus(transactionId: String, record: FeeRecord): FeeStatusResponse = when (record.status) {
        FeeRecordStatus.IN_PROGRESS -> FeeStatusResponse.inProgress(transactionId)
        FeeRecordStatus.COMPLETED -> FeeStatusResponse.completed(
            checkNotNull(record.response) { "COMPLETED fee record of $transactionId has no response" },
        )
        FeeRecordStatus.FAILED -> FeeStatusResponse.failed(
            transactionId,
            checkNotNull(record.failureReason) { "FAILED fee record of $transactionId has no reason" },
        )
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
