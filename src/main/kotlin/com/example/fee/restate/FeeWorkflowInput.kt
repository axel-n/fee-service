package com.example.fee.restate

import com.example.fee.contract.FeeRequest
import com.fasterxml.jackson.annotation.JsonProperty

/**
 * The input of `FeeWorkflow.run`: the [request] exactly as the client sent it and its [payloadHash], the second half of
 * the workflow key (D8). The workflow passes the hash to the claim, so the record can tell this payload from another.
 *
 * Journaled as the workflow input (D21, D35): renaming or removing a field breaks in-flight workflows; adding a nullable
 * field is safe.
 */
data class FeeWorkflowInput(
    @JsonProperty("request") val request: FeeRequest,
    @JsonProperty("payload_hash") val payloadHash: String,
)
