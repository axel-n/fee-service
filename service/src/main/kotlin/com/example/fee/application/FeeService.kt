package com.example.fee.application

import com.example.fee.config.FeeCacheProperties
import com.example.fee.config.RestateProperties
import com.example.fee.contract.FeeRequest
import com.example.fee.contract.FeeResponse
import com.example.fee.contract.error.FeeException
import com.example.fee.contract.error.ValidationException
import com.example.fee.contract.error.WorkflowTimeoutException
import com.example.fee.contract.error.WorkflowUnavailableException
import com.example.fee.domain.TransactionMapper
import com.example.fee.record.FeeRecord
import com.example.fee.record.FeeRecordRepository
import com.example.fee.restate.FeeWorkflowClients
import com.example.fee.validation.RequestValidator
import com.example.fee.validation.ValidationError
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.toKotlinDuration

private val log = LoggerFactory.getLogger(FeeService::class.java)

/**
 * The gateway between the API and `FeeWorkflow`: it validates the request with [RequestValidator], answers an id that
 * is already charged from [ChargedFees] (the local cache, then Postgres), starts the id's fee record with a new
 * `workflow_id`, submits the workflow under that `workflow_id` and attaches to it. The fee itself is calculated and
 * charged inside the workflow.
 *
 * Validation comes first, so a request with a problem in any field is answered (400 or 422) without a call to Postgres
 * or Restate, and starts no workflow. Only this class submits workflows, so the workflow trusts what it gets.
 *
 * The fee record is the one place a `transaction_id` gets its `workflow_id`: its insert keeps the first one, so every
 * request of the id, sequential or concurrent and whatever its payload, submits the same workflow, and Restate runs it
 * once (`PREVIOUSLY_ACCEPTED` for the others, whose payload is ignored). A record that is already charged is answered
 * without Restate. The attach waits at most `restate.attach-timeout`, suspended rather than holding a thread. Then it
 * asks Restate for the run's output, and only Restate's answer 470 (not ready) makes it a [WorkflowTimeoutException]; a
 * run that finished meanwhile answers its response. A Postgres or Restate call that failed is a
 * [WorkflowUnavailableException], because resending is safe.
 */
@Service
class FeeService(
    private val requestValidator: RequestValidator,
    private val transactionMapper: TransactionMapper,
    private val chargedFees: ChargedFees,
    private val feeRecords: FeeRecordRepository,
    private val feeWorkflowClients: FeeWorkflowClients,
    private val restateProperties: RestateProperties,
    feeCacheProperties: FeeCacheProperties,
) {
    private val databaseTimeout = feeCacheProperties.lookupTimeout

    suspend fun submit(request: FeeRequest): FeeResponse {
        requestValidator.validate(request)?.let { throw rejected(it) }
        val transactionId = checkNotNull(request.transactionId) { "transaction_id passed validation but is null" }

        chargedFees.find(transactionId)?.let { return withoutTrailingZeros(it) }

        val record = start(request)
        record.response?.let { return charged(it) }
        val workflowId = record.workflowId
        log.info("Transaction {} is processed by workflow {}", transactionId, workflowId)

        val attachTimeout = restateProperties.attachTimeout
        val run = unavailableOnFailure("Restate call for workflow $workflowId") { feeWorkflowClients.submit(workflowId, request) }
        val response =
            unavailableOnFailure("Restate call for workflow $workflowId") {
                withTimeoutOrNull(attachTimeout.toKotlinDuration()) { run.attach() } ?: run.output()
            }
        if (response == null) {
            log.info(
                "Restate reports workflow {} of transaction {} not finished (470) after {}; answering 504",
                workflowId,
                transactionId,
                attachTimeout,
            )
            throw WorkflowTimeoutException(transactionId)
        }
        return charged(response)
    }

    // The id's fee record, started now with a new workflow_id, or the one an earlier request started. Bounded by
    // fee-cache.lookup-timeout, so a database that hangs can't hold requests up.
    private suspend fun start(request: FeeRequest): FeeRecord {
        val transaction =
            checkNotNull(transactionMapper.toTransaction(request)) {
                "Request for transaction '${request.transactionId}' passed validation but could not be mapped"
            }
        return unavailableOnFailure("Starting the fee record of ${transaction.transactionId}") {
            withTimeout(databaseTimeout.toKotlinDuration()) { feeRecords.start(transaction, UUID.randomUUID().toString()) }
        }
    }

    // Only a charge is remembered, and every charge is one: it never changes.
    private fun charged(response: FeeResponse): FeeResponse = response.also(chargedFees::remember).let(::withoutTrailingZeros)

    // A failed call means Postgres or Restate is unavailable, and so does a Postgres call that outlasted its timeout. A
    // cancellation of the caller isn't a failure: the caller went away, so it passes through untouched, as coroutines
    // require.
    private suspend fun <T> unavailableOnFailure(call: String, action: suspend () -> T): T =
        try {
            action()
        } catch (e: TimeoutCancellationException) {
            throw unavailable(call, e)
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

    private fun unavailable(call: String, cause: Throwable): FeeException {
        log.warn("{} failed: {}", call, cause.toString())
        return WorkflowUnavailableException(cause)
    }
}
