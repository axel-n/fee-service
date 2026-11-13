package com.example.fee.api

import com.example.fee.application.FeeInProgressException
import com.example.fee.contract.FeeStatusResponse
import com.example.fee.contract.error.DuplicateTransactionException
import com.example.fee.contract.error.FeeException
import com.example.fee.contract.error.ValidationException
import com.example.fee.validation.ValueDisplay
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatusCode
import org.springframework.http.ProblemDetail
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.context.request.WebRequest
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler
import tools.jackson.core.JacksonException
import tools.jackson.databind.DatabindException
import tools.jackson.databind.exc.InvalidFormatException

/**
 * Turns every failure of the API into an RFC 7807 `ProblemDetail`: a [FeeException] answers with its own
 * status, and Spring MVC's own errors (a body that isn't JSON, a wrong media type, an unknown path) keep theirs through
 * [ResponseEntityExceptionHandler], so clients see one error shape. Clients read it as the api module's `FeeProblem`.
 *
 * A [FeeException] adds its `code`. A rejected field adds `field`, `value` (as sent, `null` when missing) and
 * `message`, which names the allowed values for a 422. A failure about one transaction adds `transaction_id`.
 *
 * The one exception that isn't a failure, [FeeInProgressException], is answered with the fee's status instead.
 */
@RestControllerAdvice
class ApiExceptionHandler : ResponseEntityExceptionHandler() {
    /** A run still going after `restate.attach-timeout`: 202 and the `IN_PROGRESS` status. */
    @ExceptionHandler(FeeInProgressException::class)
    fun handleInProgress(exception: FeeInProgressException): ResponseEntity<FeeStatusResponse> =
        ResponseEntity.accepted().body(FeeStatusResponse.inProgress(exception.transactionId))

    @ExceptionHandler(ValidationException::class)
    fun handleValidation(exception: ValidationException): ProblemDetail =
        problem(exception).apply {
            setProperty(FIELD, exception.field)
            setProperty(VALUE, exception.value)
            setProperty(MESSAGE, exception.message)
        }

    @ExceptionHandler(DuplicateTransactionException::class)
    fun handleDuplicate(exception: DuplicateTransactionException): ProblemDetail =
        problem(exception).apply { setProperty(TRANSACTION_ID, exception.transactionId) }

    // Any other FeeException, e.g. WorkflowUnavailableException: its cause is logged by FeeService and never shown.
    @ExceptionHandler(FeeException::class)
    fun handleFeeException(exception: FeeException): ProblemDetail = problem(exception)

    /**
     * A body Jackson can't read is a malformed request (400). Where Jackson knows the field, the problem names it
     * like a validation error does, instead of Spring's generic "Failed to read request".
     */
    override fun handleHttpMessageNotReadable(
        ex: HttpMessageNotReadableException,
        headers: HttpHeaders,
        status: HttpStatusCode,
        request: WebRequest,
    ): ResponseEntity<Any>? {
        val body = unreadableBodyProblem(status, ex.cause)
        return handleExceptionInternal(ex, body, headers, status, request)
    }

    private fun problem(exception: FeeException): ProblemDetail =
        ProblemDetail.forStatusAndDetail(HttpStatusCode.valueOf(exception.status), exception.message).apply {
            setProperty(CODE, exception.code.name)
        }

    private fun unreadableBodyProblem(
        status: HttpStatusCode,
        cause: Throwable?,
    ): ProblemDetail {
        val field =
            (cause as? DatabindException)?.path?.takeIf { it.isNotEmpty() }?.joinToString(".") {
                it.propertyName ?: "[${it.index}]"
            }
        if (field == null) {
            val message =
                if (cause is JacksonException) {
                    "The request body is not valid JSON"
                } else {
                    "The request body is missing or can't be read"
                }
            return ProblemDetail.forStatusAndDetail(status, message).apply { setProperty(MESSAGE, message) }
        }
        val invalid = cause as? InvalidFormatException
        val value = invalid?.value?.toString()
        val expected =
            if (invalid != null && Number::class.java.isAssignableFrom(invalid.targetType)) {
                ". Expected a number"
            } else {
                ""
            }
        val message =
            if (value == null) {
                "Field '$field' has a value of the wrong JSON type"
            } else {
                "Field '$field' has an invalid value ${ValueDisplay.quoted(value)}$expected"
            }
        return ProblemDetail.forStatusAndDetail(status, message).apply {
            setProperty(FIELD, field)
            setProperty(VALUE, value)
            setProperty(MESSAGE, message)
        }
    }

    private companion object {
        const val CODE = "code"
        const val FIELD = "field"
        const val VALUE = "value"
        const val MESSAGE = "message"
        const val TRANSACTION_ID = "transaction_id"
    }
}
