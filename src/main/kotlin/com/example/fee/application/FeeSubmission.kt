package com.example.fee.application

import com.example.fee.contract.FeeResponse

/**
 * What `FeeService` returns for an accepted `POST /transaction/fee`; every other result is a [FeeException].
 * HTTP only, never stored.
 */
sealed interface FeeSubmission {

    /** The fee was charged, now or by an earlier run for the same payload: 200 with [response]. */
    data class Completed(val response: FeeResponse) : FeeSubmission

    /**
     * The workflow is still running after `restate.attach-timeout`: 202 with `Location: /transaction/fee/{transactionId}`,
     * where the caller polls the status. Resubmitting the same payload attaches to the same run, so it charges once.
     */
    data class Processing(val transactionId: String) : FeeSubmission
}
