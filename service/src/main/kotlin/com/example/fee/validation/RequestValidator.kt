package com.example.fee.validation

import com.example.fee.contract.FeeRequest
import org.springframework.core.annotation.AnnotationAwareOrderComparator
import org.springframework.stereotype.Component

/**
 * Runs every [TransactionValidator] in `@Order` order and stops at the first error.
 *
 * Spring injects the list already sorted, but the list is sorted again here, so a hand-built list (unit tests, a
 * future caller) runs in the same order. Called inside the workflow's VALIDATE journaled step.
 */
@Component
class RequestValidator(validators: List<TransactionValidator>) {

    private val ordered: List<TransactionValidator> = validators.sortedWith(AnnotationAwareOrderComparator.INSTANCE)

    /** The first error in `@Order` order, or `null` if the request passes every check. */
    fun validate(request: FeeRequest): ValidationError? = ordered.firstNotNullOfOrNull { it.validate(request) }
}
