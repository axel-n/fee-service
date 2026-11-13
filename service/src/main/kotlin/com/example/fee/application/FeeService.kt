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
import com.example.fee.validation.RequestValidator
import com.example.fee.validation.ValidationError
import com.example.fee.workflow.FeeOutcome
import kotlinx.coroutines.withTimeoutOrNull
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.toKotlinDuration

/**
 * The thin gateway between the API and `FeeWorkflow`: it validates the request with [RequestValidator], answers an id
 * that is already charged from [ChargedFees] (the local cache, then Postgres), submits the workflow of any other id and
 * attaches to it, and maps the [FeeOutcome] to the charged [FeeResponse] or a [FeeException]. Every other business
 * decision is made inside the workflow.
 *
 * Validation comes first, so a request with a problem in any field is answered (400 or 422) without a call to Postgres
 * or Restate, and starts no workflow. The workflow runs the same checks again in its VALIDATE step, so a run started past
 * the app is validated too.
 *
 * A charged id sent with a valid payload is answered without Restate, whatever that payload is: its charged response.
 * Otherwise it submits, then attaches: a `transaction_id` sent again gets `PREVIOUSLY_ACCEPTED`, its payload is ignored,
 * and the attach returns the first run's result, so a transaction is processed once. The attach waits at most
 * `restate.attach-timeout`, suspended rather than holding a thread. Then it asks Restate for the run's output, and only
 * Restate's answer 470 (not ready) makes it a [WorkflowTimeoutException]; a run that finished meanwhile answers its
 * outcome. A Restate call that failed is a [WorkflowUnavailableException], because resending is safe.
 */
@Service
class FeeService(
    private val requestValidator: RequestValidator,
    private val chargedFees: ChargedFees,
    private val feeWorkflowClients: FeeWorkflowClients,
    private val restateProperties: RestateProperties,
) {
    suspend fun submit(request: FeeRequest): FeeResponse {
        requestValidator.validate(request)?.let { throw rejected(it) }
        val transactionId = checkNotNull(request.transactionId) { "transaction_id passed validation but is null" }

        chargedFees.find(transactionId)?.let { return withoutTrailingZeros(it) }

        val attachTimeout = restateProperties.attachTimeout
        val run = restateCall(transactionId) { feeWorkflowClients.submit(transactionId, request) }
        val outcome =
            restateCall(transactionId) {
                withTimeoutOrNull(attachTimeout.toKotlinDuration()) { run.attach() } ?: run.output()
            }
        if (outcome == null) {
            log.info(
                "Restate reports workflow {} not finished (470) after {}; answering 504",
                transactionId,
                attachTimeout,
            )
            throw WorkflowTimeoutException(transactionId)
        }
        return toResponse(outcome)
    }

    // A failed Restate call means Restate is unavailable. A cancellation isn't a failure: the caller went away, so it
    // passes through untouched, as coroutines require.
    private suspend fun <T> restateCall(transactionId: String, call: suspend () -> T): T =
        try {
            call()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw unavailable(transactionId, e)
        }

    // Only a charge is remembered: a rejection or a conflict stays Restate's answer. The workflow still rejects when
    // the run this call attached to was started past the app with another payload, or when its created_at check meets
    // a clock behind this instance's.
    private fun toResponse(outcome: FeeOutcome): FeeResponse =
        when (outcome) {
            is FeeOutcome.Charged -> outcome.response.also(chargedFees::remember).let(::withoutTrailingZeros)
            is FeeOutcome.Rejected -> throw rejected(outcome.error)
            is FeeOutcome.Conflict -> throw DuplicateTransactionException(outcome.transactionId)
        }

    // The fee is stored at the asset's decimals (1.50); the API answers it without trailing zeros (1.5), as the
    // challenge's sample does. Same value, so nothing is rounded.
    private fun withoutTrailingZeros(response: FeeResponse): FeeResponse = response.copy(fee = response.fee.stripTrailingZeros())

    // ValidationError is the workflow's durable type, ValidationException the published contract: same fields.
    private fun rejected(error: ValidationError): ValidationException {
        val kind =
            when (error.kind) {
                ValidationError.Kind.INVALID -> ValidationException.Kind.INVALID
                ValidationError.Kind.UNSUPPORTED -> ValidationException.Kind.UNSUPPORTED
            }
        return ValidationException(error.field, error.value, error.message, kind)
    }

    private fun unavailable(transactionId: String, cause: Throwable): WorkflowUnavailableException {
        log.warn("Restate call for workflow {} failed: {}", transactionId, cause.toString())
        return WorkflowUnavailableException(cause)
    }

    private companion object {
        private val log = LoggerFactory.getLogger(FeeService::class.java)
    }
}
