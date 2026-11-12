package com.example.fee.contract.error

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonProperty

/**
 * The body of every error response of the fee service: an RFC 7807 problem (`application/problem+json`).
 *
 * [type], [title], [status], [detail] and [instance] are always there. The service's own failures add [code] (a
 * [FeeErrorCode] name) and the properties of their [FeeException]: a rejected request [field], [value] and [message];
 * a failure about one transaction [transactionId]. Spring MVC's own errors (415, 405, an unknown path, a body that
 * isn't JSON) have no [code].
 *
 * Unknown properties are ignored, so a client keeps reading the body when a later version adds one.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
data class FeeProblem(
    @JsonProperty("type") val type: String? = null,
    @JsonProperty("title") val title: String? = null,
    @JsonProperty("status") val status: Int,
    @JsonProperty("detail") val detail: String? = null,
    @JsonProperty("instance") val instance: String? = null,
    @JsonProperty("code") val code: String? = null,
    @JsonProperty("field") val field: String? = null,
    @JsonProperty("value") val value: String? = null,
    @JsonProperty("message") val message: String? = null,
    @JsonProperty("transaction_id") val transactionId: String? = null,
) {

    /** The [FeeErrorCode] named by [code], or `null` when it is missing or unknown to this version of the contract. */
    fun errorCode(): FeeErrorCode? = FeeErrorCode.entries.firstOrNull { it.name == code }

    /**
     * The [FeeException] the service raised, rebuilt from this body so a client can throw or match it; `null` when
     * [code] is missing or unknown, or a property the exception needs is absent.
     */
    fun toException(): FeeException? = when (errorCode()) {
        FeeErrorCode.INVALID_REQUEST -> validation(ValidationException.Kind.INVALID)
        FeeErrorCode.UNSUPPORTED_REQUEST -> validation(ValidationException.Kind.UNSUPPORTED)
        FeeErrorCode.DUPLICATE_TRANSACTION -> transactionId?.let { DuplicateTransactionException(it) }

        FeeErrorCode.WORKFLOW_UNAVAILABLE -> WorkflowUnavailableException()
        null -> null
    }

    private fun validation(kind: ValidationException.Kind): ValidationException? {
        val text = message ?: detail
        return if (field != null && text != null) ValidationException(field, value, text, kind) else null
    }
}
