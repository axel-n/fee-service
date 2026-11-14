package com.example.fee.application

import com.example.fee.config.RestateProperties
import com.example.fee.contract.FeeRequest
import com.example.fee.contract.FeeResponse
import com.example.fee.contract.error.FeeException
import com.example.fee.contract.error.ValidationException
import com.example.fee.contract.error.WorkflowTimeoutException
import com.example.fee.contract.error.WorkflowUnavailableException
import com.example.fee.restate.FeeWorkflowClients
import com.example.fee.validation.RequestValidator
import com.example.fee.validation.ValidationError
import kotlinx.coroutines.withTimeoutOrNull
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.toKotlinDuration

private val log = LoggerFactory.getLogger(FeeService::class.java)

/**
 * The gateway between the API and `FeeWorkflow`: it validates the request with [RequestValidator], answers an id that
 * is already charged from [ChargedFees] (the local cache, then Postgres), and otherwise submits the workflow under the
 * `transaction_id` and attaches to it. The fee itself is calculated and charged inside the workflow.
 *
 * Validation comes first, so a request with a problem in any field is answered (400 or 422) without a call to Postgres
 * or Restate, and starts no workflow. Only this class submits workflows, so the workflow trusts what it gets.
 *
 * The workflow key is the `transaction_id`, so every request of the id, sequential or concurrent and whatever its
 * payload, submits the same workflow, and Restate runs it once (`PREVIOUSLY_ACCEPTED` for the others, whose payload is
 * ignored). The attach waits at most `restate.attach-timeout`, suspended rather than holding a thread. Then it asks
 * Restate for the run's output, and only Restate's answer 470 (not ready) makes it a [WorkflowTimeoutException]; a run
 * that finished meanwhile answers its response. A Postgres or Restate call that failed is a
 * [WorkflowUnavailableException], because resending is safe: so is a Postgres lookup that failed or timed out, since
 * without it the gateway can't tell whether the id is charged already.
 */
@Service
class FeeService(
    private val requestValidator: RequestValidator,
    private val chargedFees: ChargedFees,
    private val feeWorkflowClients: FeeWorkflowClients,
    restateProperties: RestateProperties,
) {
    private val attachTimeout = restateProperties.attachTimeout.toKotlinDuration()

    suspend fun submit(request: FeeRequest): FeeResponse {
        requestValidator.validate(request)?.let { throw rejected(it) }
        val transactionId = checkNotNull(request.transactionId) { "transaction_id passed validation but is null" }

        val charged = unavailableOnFailure("Reading the fee record of $transactionId") { chargedFees.find(transactionId) }
        if (charged != null) return withoutTrailingZeros(charged)

        val response = unavailableOnFailure("Restate call for transaction $transactionId") { process(transactionId, request) }
        if (response == null) throw notFinished(transactionId)
        return charged(response)
    }

    // Submits the workflow, or joins the run an earlier request of the id submitted. After restate.attach-timeout,
    // Restate's own answer decides: null only when it says the run hasn't finished (470).
    private suspend fun process(transactionId: String, request: FeeRequest): FeeResponse? {
        val run = feeWorkflowClients.submit(transactionId, request)
        val attached = withTimeoutOrNull(attachTimeout) { run.attach() }
        return attached ?: run.output()
    }

    // Only a charge is remembered, and every charge is one: it never changes.
    private fun charged(response: FeeResponse): FeeResponse = response.also(chargedFees::remember).let(::withoutTrailingZeros)

    // A failed call means Postgres or Restate is unavailable, and so does a Postgres lookup that outlasted its timeout
    // (ChargedFees throws a TimeoutException). A cancellation of the caller isn't a failure: the caller went away, so it
    // passes through untouched, as coroutines require.
    private suspend fun <T> unavailableOnFailure(call: String, action: suspend () -> T): T = try {
        action()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        throw unavailable(call, e)
    }

    // The fee is stored at the asset's decimals (1.50); the API answers it without trailing zeros (1.5), as the
    // challenge's sample does. Same value, so nothing is rounded.
    private fun withoutTrailingZeros(response: FeeResponse): FeeResponse = response.copy(fee = response.fee.stripTrailingZeros())

    // ValidationError is the validators' result, ValidationException the published contract: same fields.
    private fun rejected(error: ValidationError): ValidationException {
        val kind =
            when (error.kind) {
                ValidationError.Kind.INVALID -> ValidationException.Kind.INVALID
                ValidationError.Kind.UNSUPPORTED -> ValidationException.Kind.UNSUPPORTED
            }
        return ValidationException(error.field, error.value, error.message, kind)
    }

    private fun notFinished(transactionId: String): FeeException {
        log.info("Workflow of transaction {} not finished after {} (Restate: 470): 504", transactionId, attachTimeout)
        return WorkflowTimeoutException(transactionId)
    }

    private fun unavailable(call: String, cause: Throwable): FeeException {
        log.warn("{} failed: {}", call, cause.toString())
        return WorkflowUnavailableException(cause)
    }
}
