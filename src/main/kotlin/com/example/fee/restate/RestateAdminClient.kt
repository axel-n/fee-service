package com.example.fee.restate

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientException
import org.springframework.web.client.body

/**
 * The calls registration makes to Restate's admin API at `restate.admin-url` (D31). It only talks to Restate; deciding
 * whether to register, and with `force`, is the registrar's job.
 *
 * Built on Spring Boot's `RestClient.Builder`, so the `spring.http.client` timeouts apply. Every failure (Restate
 * unreachable, a timeout, an error status, an unreadable or empty body) is a [RestateAdminException] naming the call.
 */
@Component
class RestateAdminClient(
    restClientBuilder: RestClient.Builder,
    properties: RestateProperties,
) {

    private val adminUrl = properties.adminUrl

    private val restClient: RestClient = restClientBuilder
        .baseUrl(adminUrl)
        .defaultHeader("Accept", MediaType.APPLICATION_JSON_VALUE)
        .build()

    /** `GET /deployments`: every deployment Restate knows, with the names and revisions of its services. */
    fun listDeployments(): List<DeploymentSummary> =
        call("list deployments") {
            restClient.get().uri("/deployments").retrieve().body<DeploymentList>()
        }.deployments

    /** `GET /deployments/{id}`: the services the deployment registered, with their handlers. */
    fun deployment(id: String): DeploymentDetail =
        call("get deployment '$id'") {
            restClient.get().uri("/deployments/{id}", id).retrieve().body<DeploymentDetail>()
        }

    /**
     * `POST /deployments`: asks Restate to discover the endpoint at [uri]. Without [force], a URI Restate already knows
     * keeps its registered services, even if they changed; with [force] they are replaced in place (same id). A [dryRun]
     * returns the discovered services without registering anything (D31).
     */
    fun register(uri: String, force: Boolean = false, dryRun: Boolean = false): RegistrationResult =
        call(if (dryRun) "discover '$uri' (dry run)" else "register '$uri'${if (force) " with force" else ""}") {
            restClient.post().uri("/deployments")
                .contentType(MediaType.APPLICATION_JSON)
                .body(RegisterDeploymentRequest(uri, force, dryRun))
                .retrieve()
                .body<RegistrationResult>()
        }

    private fun <T : Any> call(operation: String, request: () -> T?): T {
        val result = try {
            request()
        } catch (e: RestClientException) {
            throw RestateAdminException("Restate admin API at $adminUrl failed to $operation: ${e.message}", e)
        }
        return result ?: throw RestateAdminException("Restate admin API at $adminUrl failed to $operation: empty response")
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class DeploymentList(
        @JsonProperty("deployments") val deployments: List<DeploymentSummary> = emptyList(),
    )

    // force and dry_run are always sent, so the request doesn't depend on the server's defaults
    private data class RegisterDeploymentRequest(
        @JsonProperty("uri") val uri: String,
        @JsonProperty("force") val force: Boolean,
        @JsonProperty("dry_run") val dryRun: Boolean,
    )
}

/** A call to Restate's admin API failed; the message names the call and the admin URL. */
class RestateAdminException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
