package com.example.fee.application

import com.example.fee.config.RestateProperties
import com.example.fee.contract.FeeRequest
import com.example.fee.contract.FeeResponse
import com.example.fee.contract.error.DuplicateTransactionException
import com.example.fee.contract.error.FeeException
import com.example.fee.contract.error.ValidationException
import com.example.fee.contract.error.WorkflowTimeoutException
import com.example.fee.contract.error.WorkflowUnavailableException
import com.example.fee.restate.FeeWorkflowClients
import com.example.fee.validation.ValidationError
import com.example.fee.validation.rule.TransactionIdValidator
import com.example.fee.workflow.FeeOutcome
import kotlinx.coroutines.withTimeoutOrNull
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.toKotlinDuration

/**
 * The thin gateway between the API and `FeeWorkflow`: it checks the `transaction_id`, which is the workflow key,
 * submits the workflow of that id and attaches to it, and maps the [FeeOutcome] to the charged [FeeResponse] or a
 * [FeeException]. Every business decision is made inside the workflow.
 *
 * It always submits, then attaches: a `transaction_id` sent again gets `PREVIOUSLY_ACCEPTED`, its payload is ignored,
 * and the attach returns the first run's result, so a transaction is processed once. The attach waits at most
 * `restate.attach-timeout`, suspended rather than holding a thread. Then it asks Restate for the run's output, and only
 * Restate's answer 470 (not ready) makes it a [WorkflowTimeoutException]; a run that finished meanwhile answers its
 * outcome. A Restate call that failed is a [WorkflowUnavailableException], because resending is safe.
 */
private val log = LoggerFactory.getLogger(FeeService::class.java)

@Service
class FeeService(
    private val transactionIdValidator: TransactionIdValidator,
    private val feeWorkflowClients: FeeWorkflowClients,
    private val restateProperties: RestateProperties,
) {
    suspend fun submit(request: FeeRequest): FeeResponse {
        transactionIdValidator.validate(request)?.let { throw rejected(it) }
        val transactionId = checkNotNull(request.transactionId) { "transaction_id passed the pre-check but is null" }

        val attachTimeout = restateProperties.attachTimeout
        val run = restateCall(transactionId) { feeWorkflowClients.submit(transactionId, request) }
        val outcome = restateCall(transactionId) {
            withTimeoutOrNull(attachTimeout.toKotlinDuration()) { run.attach() } ?: run.output()
        }
        if (outcome == null) {
            log.info(
                "Restate reports workflow {} not finished (470) after {}; answering 504",
                transactionId,
                attachTimeout
            )
            throw WorkflowTimeoutException(transactionId)
        }
        return toResponse(outcome)
    }

    // A failed Restate call means Restate is unavailable. A cancellation isn't a failure: the caller went away, so it
    // passes through untouched, as coroutines require.
    private suspend fun <T> restateCall(
        transactionId: String,
        call: suspend () -> T,
    ): T =
        try {
            call()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw unavailable(transactionId, e)
        }

    // The fee is stored at the asset's decimals (1.50); the API answers it without trailing zeros (1.5), as the
    // challenge's sample does. Same value, so nothing is rounded.
    private fun toResponse(outcome: FeeOutcome): FeeResponse =
        when (outcome) {
            is FeeOutcome.Charged -> outcome.response.let { it.copy(fee = it.fee.stripTrailingZeros()) }
            is FeeOutcome.Rejected -> throw rejected(outcome.error)
            is FeeOutcome.Conflict -> throw DuplicateTransactionException(outcome.transactionId)
        }

    // ValidationError is the workflow's durable type, ValidationException the published contract: same fields.
    private fun rejected(error: ValidationError): ValidationException {
        val kind =
            when (error.kind) {
                ValidationError.Kind.INVALID -> ValidationException.Kind.INVALID
                ValidationError.Kind.UNSUPPORTED -> ValidationException.Kind.UNSUPPORTED
            }
        return ValidationException(error.field, error.value, error.message, kind)
    }

    private fun unavailable(
        transactionId: String,
        cause: Throwable,
    ): WorkflowUnavailableException {
        log.warn("Restate call for workflow {} failed: {}", transactionId, cause.toString())
        return WorkflowUnavailableException(cause)
    }
}
