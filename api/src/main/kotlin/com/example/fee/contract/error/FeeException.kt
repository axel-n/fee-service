package com.example.fee.contract.error

/**
 * Why the fee service can't answer with a fee. The service answers each one with an RFC 7807 problem
 * (`application/problem+json`) whose `status` is [status] and whose `code` is [code]; a client reads that body as
 * [FeeProblem] and gets the exception back with [FeeProblem.toException].
 *
 * In the service, `FeeService` throws these at the gateway, built from the workflow's `FeeOutcome` or from a Restate
 * call that failed; the workflow itself never throws them. No Spring here: [status] is the plain HTTP status code,
 * so a consumer needs nothing but this module.
 *
 * Not durable (they never cross Restate's serde), but published: other services compile against them, so renaming a
 * class or a [FeeErrorCode] breaks those clients.
 */
sealed class FeeException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause) {
    /** What went wrong, machine-readable: the `code` of the problem body. */
    abstract val code: FeeErrorCode

    /** The HTTP status the API answers with. */
    val status: Int get() = code.status
}

/** The `code` of every problem the service raises itself, with the HTTP [status] it answers with. */
enum class FeeErrorCode(
    val status: Int,
) {
    /** A malformed request: a missing field or a value of the wrong form ([ValidationException]). */
    INVALID_REQUEST(400),

    /** A well-formed request the service doesn't support, e.g. state `PENDING` ([ValidationException]). */
    UNSUPPORTED_REQUEST(422),

    /** The transaction id is claimed by an earlier run that never finished ([DuplicateTransactionException]). */
    DUPLICATE_TRANSACTION(409),

    /** Fee processing can't be reached right now ([WorkflowUnavailableException]). */
    WORKFLOW_UNAVAILABLE(503),
}

/**
 * The request was rejected by validation: [Kind.INVALID] is a malformed request (400), [Kind.UNSUPPORTED] one the
 * service doesn't support (422). It names the request [field], the rejected [value] as sent (`null` when the field is
 * missing), and a readable message that lists the allowed values.
 */
class ValidationException(
    val field: String,
    val value: String?,
    message: String,
    val kind: Kind,
) : FeeException(message) {
    override val code: FeeErrorCode get() = kind.code

    enum class Kind(
        val code: FeeErrorCode,
    ) {
        INVALID(FeeErrorCode.INVALID_REQUEST),
        UNSUPPORTED(FeeErrorCode.UNSUPPORTED_REQUEST),
    }
}

/** [transactionId] is claimed by an earlier run that never finished (one an operator killed, say): 409. */
class DuplicateTransactionException(
    val transactionId: String,
) : FeeException(
        "Transaction '$transactionId' is claimed by an earlier run that has not finished",
    ) {
    override val code: FeeErrorCode get() = FeeErrorCode.DUPLICATE_TRANSACTION
}

/**
 * Restate couldn't be reached or failed to accept the request, so nothing is known to be processed: 503. The [cause]
 * stays in the service's log; the response never shows it.
 */
class WorkflowUnavailableException(
    cause: Throwable? = null,
) : FeeException(
        "Fee processing is temporarily unavailable. Retry the same request later",
        cause,
    ) {
    override val code: FeeErrorCode get() = FeeErrorCode.WORKFLOW_UNAVAILABLE
}
