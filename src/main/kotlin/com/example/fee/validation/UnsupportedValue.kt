package com.example.fee.validation

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
