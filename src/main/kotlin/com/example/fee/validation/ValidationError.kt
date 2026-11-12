package com.example.fee.validation

import com.example.fee.contract.PlainBigDecimalSerializer
import com.fasterxml.jackson.annotation.JsonProperty
import java.math.BigDecimal

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

/**
 * The 422 error of a business check whose field must be one of a fixed list of values:
 * `Unsupported asset 'BTC'. Supported: 'USD', 'EUR'`.
 */
object UnsupportedValue {

    /** [raw] of [field] is not one of [supported]; the message names both, in the order given. */
    fun error(field: String, raw: String, supported: List<String>): ValidationError = ValidationError(
        field,
        raw,
        "Unsupported $field ${ValueDisplay.quoted(raw)}. Supported: ${supported.joinToString(", ") { "'$it'" }}",
        ValidationError.Kind.UNSUPPORTED,
    )
}

/**
 * How a rejected value appears in a [ValidationError]: [value] for the `value` field, [quoted] for the message.
 * Messages echo what the client sent, so an absurdly long value is shortened there and can't bloat the response.
 */
object ValueDisplay {

    /** Longer values are cut to this many characters in messages, followed by `…`. */
    const val MAX_MESSAGE_LENGTH = 64

    /** The amount as JSON carries it: plain (`1000`, `-1.50`), scientific only beyond the serializer's bound (D38). */
    fun value(amount: BigDecimal): String =
        if (amount.scale() in -PlainBigDecimalSerializer.MAX_PLAIN_SCALE..PlainBigDecimalSerializer.MAX_PLAIN_SCALE) {
            amount.toPlainString()
        } else {
            amount.toString()
        }

    /** [raw] shortened to [MAX_MESSAGE_LENGTH] characters, for a message that shows it without quotes. */
    fun shortened(raw: String): String =
        if (raw.length <= MAX_MESSAGE_LENGTH) raw else "${raw.take(MAX_MESSAGE_LENGTH)}…"

    /** [raw] in single quotes, shortened to [MAX_MESSAGE_LENGTH] characters. */
    fun quoted(raw: String): String = "'${shortened(raw)}'"
}
