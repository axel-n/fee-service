package com.example.fee.it

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * What the ITs read from Restate's admin API (1.8.1, D31) to check what the app did, independent of the app's own
 * `RestateAdminClient`.
 */
class RestateAdminApi(private val adminUrl: String, private val timeout: Duration = Duration.ofSeconds(10)) {

    private val http = HttpClient.newBuilder().connectTimeout(timeout).build()
    private val mapper = ObjectMapper()

    /** A deployment as `GET /deployments` lists it. */
    data class Deployment(val id: String, val uri: String?, val services: Map<String, Int>)

    fun deployments(): List<Deployment> =
        get("/deployments")["deployments"].map { deployment ->
            Deployment(
                id = deployment["id"].asText(),
                uri = deployment["uri"]?.takeUnless { it.isNull }?.asText(),
                services = deployment["services"].associate { it["name"].asText() to it["revision"].asInt() },
            )
        }

    /** The deployments registered for [uri]; Restate stores URIs with a trailing slash (D31). */
    fun deploymentsFor(uri: String): List<Deployment> =
        deployments().filter { it.uri?.trimEnd('/') == uri.trimEnd('/') }

    /** The names of the services Restate knows, with the id of the deployment that serves each. */
    fun services(): Map<String, String> =
        get("/services")["services"].associate { it["name"].asText() to it["deployment_id"].asText() }

    /** Removes the deployments of [uri], so Restate no longer knows the endpoint, as if it were fresh (D31: needs force). */
    fun deleteDeploymentsFor(uri: String) {
        deploymentsFor(uri).forEach { deployment ->
            val id = URLEncoder.encode(deployment.id, Charsets.UTF_8)
            send(HttpRequest.newBuilder(URI("$adminUrl/deployments/$id?force=true")).DELETE())
        }
    }

    /**
     * Purges a completed invocation: its journal, output and, for a workflow, its state and key, as if retention had
     * expired. The same workflow key then starts a new run.
     */
    fun purgeInvocation(invocationId: String) {
        val id = URLEncoder.encode(invocationId, Charsets.UTF_8)
        send(HttpRequest.newBuilder(URI("$adminUrl/invocations/$id/purge")).method("PATCH", HttpRequest.BodyPublishers.noBody()))
    }

    /** Rows of a query on Restate's SQL introspection (`POST /query`), e.g. `sys_invocation` or `sys_journal`. */
    fun query(sql: String): List<JsonNode> {
        val body = mapper.writeValueAsString(mapOf("query" to sql))
        val request = HttpRequest.newBuilder(URI("$adminUrl/query"))
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
        return mapper.readTree(send(request))["rows"].toList()
    }

    private fun get(path: String): JsonNode = mapper.readTree(send(HttpRequest.newBuilder(URI(adminUrl + path)).GET()))

    private fun send(request: HttpRequest.Builder): String {
        val response = http.send(request.timeout(timeout).build(), HttpResponse.BodyHandlers.ofString())
        check(response.statusCode() in 200..299) {
            "Restate admin API ${response.request().method()} ${response.uri()} answered ${response.statusCode()}: ${response.body()}"
        }
        return response.body()
    }
}
