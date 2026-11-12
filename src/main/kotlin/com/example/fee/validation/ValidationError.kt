package com.example.fee.validation

import com.fasterxml.jackson.annotation.JsonProperty

/**
 * Why a request was rejected: the request [field], the rejected [value] as sent (`null` when the field is missing),
 * a readable [message], and the [kind] that decides the HTTP status (D11).
 *
 * Journaled as the result of VALIDATE and carried by `FeeOutcome.Rejected`, so this JSON shape is durable (D21, D35):
 * renaming or removing a field is breaking, adding a nullable one is safe.
 */
data class ValidationError(
    @JsonProperty("field") val field: String,
    @JsonProperty("value") val value: String?,
    @JsonProperty("message") val message: String,
    @JsonProperty("kind") val kind: Kind,
) {

    enum class Kind {
        /** The request is malformed: a missing field or a value of the wrong form → 400. */
        INVALID,

        /** The request is well-formed but asks for something the service doesn't support → 422. */
        UNSUPPORTED,
    }
}
