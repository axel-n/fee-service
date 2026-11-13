package com.example.fee.it

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * The app's HTTP API as a client sees it, on the random port of the app under test (`@LocalServerPort`, or the mapped
 * port of an app container on its [host]). Bodies are read with plain Jackson, decimals as `BigDecimal`, so money
 * assertions keep the exact value.
 */
class FeeApi(
    port: Int,
    host: String = "localhost",
    private val timeout: Duration = Duration.ofSeconds(60),
) {
    /** A response: its status code and its body as JSON. */
    data class Response(
        val status: Int,
        val body: JsonNode,
    )

    private val root = "http://$host:$port"
    private val http = HttpClient.newBuilder().connectTimeout(timeout).build()
    private val mapper = ObjectMapper().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)

    /** `POST /transaction/fee` (or another [path] of the app) with [body] as the JSON request. */
    fun submit(
        body: Map<String, Any?>,
        path: String = FEE_PATH,
    ): Response =
        send(
            HttpRequest
                .newBuilder(URI("$root$path"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body))),
        )

    private fun send(request: HttpRequest.Builder): Response {
        val response = http.send(request.timeout(timeout).build(), HttpResponse.BodyHandlers.ofString())
        return Response(response.statusCode(), mapper.readTree(response.body()))
    }

    companion object {
        /** The path of the fee API. */
        const val FEE_PATH = "/transaction/fee"
    }
}
