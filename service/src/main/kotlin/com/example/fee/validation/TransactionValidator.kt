package com.example.fee.validation

import com.example.fee.contract.FeeRequest

/**
 * One check of an incoming [FeeRequest]. Every bean of this type runs inside the workflow's VALIDATE step, in the
 * order of its `@Order` and fail-fast: the first error decides the response.
 *
 * To add a check, add a `@Component @Order(ValidationOrder.X)` class implementing this interface, with a new constant
 * in [ValidationOrder]. Structural checks ([ValidationError.Kind.INVALID], 400) are ordered before business checks
 * ([ValidationError.Kind.UNSUPPORTED], 422). A validator may rely on the checks ordered before it (the fields are
 * present, the asset is known) and must not repeat them. A validator without `@Order` runs last.
 *
 * Implementations must be pure apart from an injected `Clock`: they run inside a journaled step, so the result is journaled.
 */
interface TransactionValidator {

    /**
     * The problem with [request], or `null` if this check passes. Never throws: a bad value is an error to return.
     * The message must be readable and name the rejected value and, where there are any, the allowed ones.
     */
    fun validate(request: FeeRequest): ValidationError?
}

/**
 * The `@Order` of each [TransactionValidator], spaced by 100 so a new check fits between two others. `@Order` needs
 * compile-time constants, hence an object of `const val`s. Every structural check (400) comes before every business
 * check (422), and a check comes after the ones it relies on.
 */
object ValidationOrder {

    // Structural: the request is malformed (ValidationError.Kind.INVALID, 400).
    const val REQUIRED_FIELDS = 100
    const val TRANSACTION_ID = 200
    const val AMOUNT = 300
    const val CREATED_AT = 400

    // Business: well-formed but not supported (ValidationError.Kind.UNSUPPORTED, 422).
    const val STATE = 500
    const val ASSET = 600
    const val ASSET_TYPE = 700
    const val TRANSACTION_TYPE = 800

    /** After [ASSET]: the allowed decimals belong to the asset. */
    const val AMOUNT_SCALE = 900
}
