package com.example.fee.api

import com.example.fee.application.FeeService
import com.example.fee.contract.FeeRequest
import com.example.fee.contract.FeeStatusResponse
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * `POST /transaction/fee`: HTTP only, everything else is [FeeService]'s. It answers a [FeeStatusResponse] or throws.
 * Failures are [com.example.fee.contract.error.FeeException]s, which `ApiExceptionHandler` turns into a `ProblemDetail`.
 */
@RestController
@RequestMapping(TransactionFeeController.PATH, produces = [MediaType.APPLICATION_JSON_VALUE])
class TransactionFeeController(private val feeService: FeeService) {

    /**
     * 200 with the `COMPLETED` status and the fee once charged. A run still going after `restate.attach-timeout` is a
     * [com.example.fee.application.FeeInProgressException], which `ApiExceptionHandler` answers with 202 and the
     * `IN_PROGRESS` status; resending the same payload is safe and returns the fee once it is charged.
     *
     * A suspend function, so Spring MVC serves it asynchronously: no servlet thread waits while the workflow runs.
     */
    @PostMapping(consumes = [MediaType.APPLICATION_JSON_VALUE])
    suspend fun submit(@RequestBody request: FeeRequest): FeeStatusResponse = feeService.submit(request)

    companion object {
        const val PATH = "/transaction/fee"
    }
}
