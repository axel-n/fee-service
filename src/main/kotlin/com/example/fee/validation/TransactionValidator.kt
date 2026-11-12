package com.example.fee.validation

import com.example.fee.contract.FeeRequest

/**
 * One check of an incoming [FeeRequest]. Every bean of this type runs inside the workflow's VALIDATE step, in the
 * order of its `@Order` and fail-fast: the first error decides the response (D11).
 *
 * To add a check, add a `@Component @Order(ValidationOrder.X)` class implementing this interface, with a new constant
 * in [ValidationOrder]. Structural checks ([ValidationError.Kind.INVALID], 400) are ordered before business checks
 * ([ValidationError.Kind.UNSUPPORTED], 422). A validator may rely on the checks ordered before it (the fields are
 * present, the asset is known) and must not repeat them. A validator without `@Order` runs last.
 *
 * Implementations must be pure apart from an injected `Clock`: they run inside `ctx.run`, so the result is journaled.
 */
interface TransactionValidator {

    /**
     * The problem with [request], or `null` if this check passes. Never throws: a bad value is an error to return.
     * The message must be readable and name the rejected value and, where there are any, the allowed ones.
     */
    fun validate(request: FeeRequest): ValidationError?
}
