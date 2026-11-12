package com.example.fee.it

import com.example.fee.workflow.FeeWorkflow
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.DynamicTest.dynamicTest
import org.junit.jupiter.api.TestFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.web.server.LocalServerPort
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.UUID

/**
 * Runs `http/requests.http` top to bottom against the app under test and checks each `# Expect:` line, so the examples
 * stay true. `host` points at the test app and `run` is fresh, so the file's ids are this run's alone.
 * Afterwards every charged transaction was charged once, by one CHARGE closure, duplicate and conflict included.
 */
class HttpRequestsFileIT @Autowired constructor(
    private val wallet: TestWalletGateway,
    @LocalServerPort private val apiPort: Int,
) : RestateIntegrationTest() {

    private val http = HttpClient.newBuilder().connectTimeout(TIMEOUT).build()
    private val mapper = ObjectMapper().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
    private val chargedIds = mutableSetOf<String>()

    @TestFactory
    fun `every example request gets the documented answer and charges once`(): List<DynamicTest> {
        // given
        val run = "it${UUID.randomUUID().toString().take(8)}"
        val examples = HttpRequestsFile.load(mapOf("host" to "http://localhost:$apiPort", "run" to run))

        // when / then: in file order, because later requests build on earlier ones
        return examples.map { example -> dynamicTest(example.name) { check(example) } } +
            dynamicTest("every charged transaction was charged exactly once") { checkCharges() }
    }

    private fun check(example: HttpRequestsFile.Example) {
        // when
        val response = send(example)
        val body = response.body().takeIf { it.isNotBlank() }?.let { mapper.readTree(it) }

        // then
        assertThat(response.statusCode()).describedAs("status of '%s', body %s", example.name, body)
            .isEqualTo(example.expectedStatus)
        example.expectedBody?.let { expected ->
            assertThat(body).describedAs("body of '%s'", example.name).isNotNull
            mapper.readTree(expected).properties().forEach { (field, value) ->
                assertField(example.name, body, field, value)
            }
        }
        if (example.method == "POST" && response.statusCode() == 200) {
            body?.get("transaction_id")?.asText()?.let { chargedIds += it }
        }
    }

    private fun checkCharges() {
        // then
        assertThat(chargedIds).isNotEmpty
        chargedIds.forEach { transactionId ->
            val key = FeeWorkflow.chargeKey(transactionId)
            assertThat(wallet.chargeCount(key)).describedAs("charges of %s", transactionId).isEqualTo(1)
            assertThat(wallet.callCount(key)).describedAs("CHARGE closure runs of %s", transactionId).isEqualTo(1)
        }
    }

    private fun assertField(name: String, body: JsonNode?, field: String, expected: JsonNode) {
        val actual = body?.get(field)
        val description = "'$field' of '$name' in $body"
        when {
            expected.isNull -> assertThat(actual?.isNull).describedAs(description).isTrue()
            expected.isNumber -> {
                assertThat(actual?.isNumber).describedAs(description).isTrue()
                assertThat(actual?.decimalValue()).describedAs(description).isEqualByComparingTo(expected.decimalValue())
            }
            else -> assertThat(actual?.asText()).describedAs(description).isEqualTo(expected.asText())
        }
    }

    private fun send(example: HttpRequestsFile.Example): HttpResponse<String> {
        val publisher = example.body?.let { HttpRequest.BodyPublishers.ofString(it) }
            ?: HttpRequest.BodyPublishers.noBody()
        val request = HttpRequest.newBuilder(URI(example.url))
            .timeout(TIMEOUT)
            .method(example.method, publisher)
        example.headers.forEach { (name, value) -> request.header(name, value) }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString())
    }

    private companion object {
        val TIMEOUT: Duration = Duration.ofSeconds(60)
    }
}
