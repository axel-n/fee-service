package com.example.fee.application

import com.example.fee.validation.ValidationError
import org.springframework.http.HttpStatus

/**
 * Why `POST /transaction/fee` can't answer with a fee. `FeeService` throws these at the gateway, built from the
 * workflow's `FeeOutcome` or from a Restate call that failed; the workflow itself never throws them (D9).
 * `ApiExceptionHandler` turns each into a `ProblemDetail` with [status].
 *
 * Not durable: these never cross Restate's serde, so they can change freely.
 */
sealed class FeeException(message: String, cause: Throwable? = null) : RuntimeException(message, cause) {

    /** The HTTP status the API answers with. */
    abstract val status: HttpStatus
}

/**
 * The request was rejected by validation: 400 for a malformed request, 422 for one the service doesn't support (D11).
 * The [error] carries the field, the rejected value and a message that lists the allowed values.
 */
class ValidationException(val error: ValidationError) : FeeException(error.message) {

    override val status: HttpStatus = when (error.kind) {
        ValidationError.Kind.INVALID -> HttpStatus.BAD_REQUEST
        ValidationError.Kind.UNSUPPORTED -> HttpStatus.UNPROCESSABLE_ENTITY
    }
}

/** [transactionId] is claimed by another payload, either already charged or still in progress (D8, D47): 409. */
class DuplicateTransactionException(val transactionId: String) : FeeException(
    "Transaction '$transactionId' was already charged or is being processed with a different payload",
) {
    override val status: HttpStatus = HttpStatus.CONFLICT
}

/** The wallet declined the charge for [transactionId] with [reason]; the id may be retried (D47, D51): 422. */
class ChargeFailedException(val transactionId: String, val reason: String) : FeeException(
    "The fee for transaction '$transactionId' could not be charged: $reason",
) {
    override val status: HttpStatus = HttpStatus.UNPROCESSABLE_ENTITY
}

/**
 * `GET /transaction/fee/{id}` found no fee record for [transactionId]: it was never submitted, was rejected by
 * validation, or its run hasn't reached CLAIM yet (D59): 404.
 */
class TransactionNotFoundException(val transactionId: String) : FeeException(
    "No fee record for transaction '$transactionId'",
) {
    override val status: HttpStatus = HttpStatus.NOT_FOUND
}

/** Restate couldn't be reached or failed to accept the request, so nothing is known to be processed: 503. */
class WorkflowUnavailableException(cause: Throwable? = null) : FeeException(
    "Fee processing is temporarily unavailable. Retry the same request later",
    cause,
) {
    override val status: HttpStatus = HttpStatus.SERVICE_UNAVAILABLE
}
