package com.example.fee.restate

import com.example.fee.config.RestateProperties
import org.springframework.stereotype.Component
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

/**
 * The endpoint's registration with Restate: what `RestateEndpointLifecycle` needs to register the endpoint at startup and
 * remove it at shutdown. An interface, so the lifecycle is tested without Restate.
 */
interface RestateDeployments {
    /**
     * Registers the endpoint at [uri], replacing a deployment with the same URI in place (it keeps its id). Restate
     * discovers the services by calling [uri], so the endpoint must be listening.
     */
    fun register(uri: String): Deployment

    /**
     * How many invocations that haven't finished need [deployment]: those pinned to it, and those of its services that
     * haven't started yet, because they go to their service's newest deployment, which may be this one.
     */
    fun unfinishedInvocations(deployment: Deployment): Long

    /** Removes [deployment]. Invocations pinned to it never run again, so check [unfinishedInvocations] first. */
    fun remove(deployment: Deployment)
}

/** A deployment Restate registered: its [id] and the names of the [services] it serves. */
data class Deployment(
    val id: String,
    val services: List<String>,
)

/** [RestateDeployments] through Restate's admin API at `restate.admin-url`, with the JDK's HTTP client. */
@Component
class AdminApiRestateDeployments(
    properties: RestateProperties,
) : RestateDeployments {
    private val adminUrl = properties.adminUrl.removeSuffix("/")
    private val http = HttpClient.newHttpClient()
    private val json = JsonMapper.builder().build()

    // force: a changed handler signature updates the services instead of being refused.
    override fun register(uri: String): Deployment {
        val answer = send("POST", "/deployments", mapOf("uri" to uri, "force" to true))
        return Deployment(
            id = answer.present("id").asString(),
            services = answer.present("services").values().map { it.present("name").asString() },
        )
    }

    override fun unfinishedInvocations(deployment: Deployment): Long {
        val needed =
            listOfNotNull(
                "pinned_deployment_id = ${sqlString(deployment.id)}",
                deployment.services
                    .takeIf { it.isNotEmpty() }
                    ?.joinToString(", ", "(pinned_deployment_id IS NULL AND target_service_name IN (", "))") { sqlString(it) },
            ).joinToString(" OR ")
        val sql = "SELECT count(*) AS unfinished FROM sys_invocation WHERE status <> 'completed' AND ($needed)"
        val unfinished = send("POST", "/query", mapOf("query" to sql)).present("rows").present(0).present("unfinished")
        // A missing or odd count must never read as 0: that would remove a deployment invocations still need.
        check(unfinished.isNumber) { "Restate admin API answered a count that is not a number: $unfinished" }
        return unfinished.longValue()
    }

    override fun remove(deployment: Deployment) {
        send("DELETE", "/deployments/${URLEncoder.encode(deployment.id, Charsets.UTF_8)}?force=true", body = null)
    }

    private fun send(
        method: String,
        path: String,
        body: Any?,
    ): JsonNode {
        val request =
            HttpRequest
                .newBuilder(URI(adminUrl + path))
                .header("Accept", "application/json")
                .apply { if (body != null) header("Content-Type", "application/json") }
                .method(
                    method,
                    body?.let { HttpRequest.BodyPublishers.ofString(json.writeValueAsString(it)) }
                        ?: HttpRequest.BodyPublishers.noBody(),
                ).build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofString())
        check(response.statusCode() in 200..299) {
            "Restate admin API $method $adminUrl$path answered ${response.statusCode()}: ${response.body()}"
        }
        return json.readTree(response.body().ifBlank { "{}" })
    }

    private fun JsonNode.present(field: String): JsonNode =
        path(field).also { check(!it.isMissingNode && !it.isNull) { "Restate admin API answered without '$field': $this" } }

    private fun JsonNode.present(index: Int): JsonNode =
        path(index).also { check(!it.isMissingNode && !it.isNull) { "Restate admin API answered without item $index: $this" } }

    private fun sqlString(value: String) = "'" + value.replace("'", "''") + "'"
}
