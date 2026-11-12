package com.example.fee.api

import com.example.fee.application.FeeService
import com.example.fee.application.FeeSubmission
import com.example.fee.contract.FeeRequest
import com.example.fee.contract.FeeStatusResponse
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.util.UriComponentsBuilder

/**
 * `POST /transaction/fee` and `GET /transaction/fee/{id}`: HTTP only, everything else is [FeeService]'s.
 * Failures are [com.example.fee.application.FeeException]s, which `ApiExceptionHandler` turns into a `ProblemDetail`.
 */
@RestController
@RequestMapping(TransactionFeeController.PATH, produces = [MediaType.APPLICATION_JSON_VALUE])
class TransactionFeeController(private val feeService: FeeService) {

    /**
     * 200 with the fee once charged. A run still going after `restate.attach-timeout` is 202 with
     * `Location: /transaction/fee/{id}` and the `IN_PROGRESS` status; resending the same payload is safe (D8).
     */
    @PostMapping(consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun submit(@RequestBody request: FeeRequest): ResponseEntity<Any> =
        when (val submission = feeService.submit(request)) {
            is FeeSubmission.Completed -> ResponseEntity.ok(submission.response)
            is FeeSubmission.Processing -> ResponseEntity
                .accepted()
                .location(statusLocation(submission.transactionId))
                .body(FeeStatusResponse.inProgress(submission.transactionId))
        }

    @GetMapping("/{id}")
    fun status(@PathVariable("id") transactionId: String): FeeStatusResponse = feeService.status(transactionId)

    private fun statusLocation(transactionId: String) =
        UriComponentsBuilder.fromPath("$PATH/{id}").buildAndExpand(transactionId).encode().toUri()

    companion object {
        const val PATH = "/transaction/fee"
    }
}
