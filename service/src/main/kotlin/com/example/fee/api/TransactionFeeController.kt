package com.example.fee.api

import com.example.fee.application.FeeService
import com.example.fee.contract.FeeRequest
import com.example.fee.contract.FeeStatusResponse
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * `POST /transaction/fee` and `GET /transaction/fee/{id}`: HTTP only, everything else is [FeeService]'s.
 * Both answer a [FeeStatusResponse] or throw. Failures are [com.example.fee.contract.error.FeeException]s, which
 * `ApiExceptionHandler` turns into a `ProblemDetail`.
 */
@RestController
@RequestMapping(TransactionFeeController.PATH, produces = [MediaType.APPLICATION_JSON_VALUE])
class TransactionFeeController(private val feeService: FeeService) {

    /**
     * 200 with the `COMPLETED` status and the fee once charged. A run still going after `restate.attach-timeout` is a
     * [com.example.fee.application.FeeInProgressException], which `ApiExceptionHandler` answers with 202,
     * `Location: /transaction/fee/{id}` and the `IN_PROGRESS` status; resending the same payload is safe (D8, D77).
     */
    @PostMapping(consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun submit(@RequestBody request: FeeRequest): FeeStatusResponse = feeService.submit(request)

    @GetMapping("/{id}")
    fun status(@PathVariable("id") transactionId: String): FeeStatusResponse = feeService.status(transactionId)

    companion object {
        const val PATH = "/transaction/fee"
    }
}
