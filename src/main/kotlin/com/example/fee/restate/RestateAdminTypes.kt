package com.example.fee.restate

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty

// The parts of Restate's admin API (1.8.1, D31) that registration needs. Restate sends many more fields; they are
// ignored, so a newer server adding fields doesn't break registration.

/** One entry of `GET /deployments`: the deployment, its URI as Restate stores it (with a trailing slash) and its services. */
@JsonIgnoreProperties(ignoreUnknown = true)
data class DeploymentSummary(
    @JsonProperty("id") val id: String,
    // null for deployments that aren't reached over HTTP (e.g. Lambda, which has an ARN instead)
    @JsonProperty("uri") val uri: String? = null,
    @JsonProperty("services") val services: List<ServiceRevision> = emptyList(),
)

/** A service as `GET /deployments` lists it: only its name and revision. */
@JsonIgnoreProperties(ignoreUnknown = true)
data class ServiceRevision(
    @JsonProperty("name") val name: String,
    @JsonProperty("revision") val revision: Int? = null,
)

/** `GET /deployments/{id}`: the services the deployment registered, with their handlers. */
@JsonIgnoreProperties(ignoreUnknown = true)
data class DeploymentDetail(
    @JsonProperty("id") val id: String,
    @JsonProperty("uri") val uri: String? = null,
    @JsonProperty("services") val services: List<ServiceDescription> = emptyList(),
)

/** The answer to `POST /deployments`: the deployment's id and the services discovered at the URI (D31). */
@JsonIgnoreProperties(ignoreUnknown = true)
data class RegistrationResult(
    @JsonProperty("id") val id: String,
    @JsonProperty("services") val services: List<ServiceDescription> = emptyList(),
)

/**
 * A service with exactly the fields D31 compares to tell whether the services changed: name, type and handlers. So two
 * descriptions are `==` when the registered and the discovered service match (apart from handler order).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class ServiceDescription(
    @JsonProperty("name") val name: String,
    // "Service", "VirtualObject" or "Workflow"
    @JsonProperty("ty") val type: String? = null,
    @JsonProperty("handlers") val handlers: List<HandlerDescription> = emptyList(),
)

/** A handler with the fields D31 compares: name, type and the input and output descriptions. */
@JsonIgnoreProperties(ignoreUnknown = true)
data class HandlerDescription(
    @JsonProperty("name") val name: String,
    // "Exclusive", "Shared" or "Workflow"
    @JsonProperty("ty") val type: String? = null,
    @JsonProperty("input_description") val inputDescription: String? = null,
    @JsonProperty("output_description") val outputDescription: String? = null,
)
