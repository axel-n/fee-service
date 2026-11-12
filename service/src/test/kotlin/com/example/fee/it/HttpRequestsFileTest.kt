package com.example.fee.it

import com.example.fee.contract.FeeRequest
import com.example.fee.domain.TransactionType
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/**
 * The shape of `http/requests.http` and of its parser, without the app. [HttpRequestsFileIT] runs the file.
 */
class HttpRequestsFileTest {

    private val mapper: ObjectMapper = jacksonObjectMapper()

    private val examples = HttpRequestsFile.load()

    private fun posts() = examples.filter { it.method == "POST" }

    private fun json(text: String?): JsonNode? = text?.let { runCatching { mapper.readTree(it) }.getOrNull() }

    @Test
    fun `every request targets the fee API on the host variable and expects a status`() {
        // given
        val raw = HttpRequestsFile.load(mapOf("host" to "http://example.test"))

        // when
        val urls = raw.map { it.url }

        // then
        assertThat(raw).isNotEmpty
        assertThat(urls).allSatisfy { assertThat(it).startsWith("http://example.test/transaction/fee") }
        assertThat(raw.map { it.expectedStatus }).allSatisfy { assertThat(it).isBetween(200, 599) }
        assertThat(raw.map { it.name }).doesNotHaveDuplicates().allSatisfy { assertThat(it).isNotBlank() }
    }

    @Test
    fun `every expected body is a JSON object`() {
        // given
        val expectations = examples.mapNotNull { it.expectedBody }

        // when
        val parsed = expectations.map { mapper.readTree(it) }

        // then
        assertThat(parsed).isNotEmpty.allSatisfy { assertThat(it.isObject).isTrue() }
    }

    @Test
    fun `every JSON body is a fee request with only contract fields`() {
        // given
        val bodies = posts().mapNotNull { json(it.body) }

        // when
        val requests = bodies.map { mapper.readValue<FeeRequest>(mapper.writeValueAsString(it)) }

        // then: unknown properties fail the read, so a misspelled field can't slip in
        assertThat(requests).hasSize(posts().size - 1)
        assertThat(posts().map { it.headers["Content-Type"] }).allSatisfy { assertThat(it).isEqualTo("application/json") }
    }

    @Test
    fun `charges every transaction type`() {
        // given
        val charged = posts().filter { it.expectedStatus == 200 }

        // when
        val types = charged.mapNotNull { json(it.body)?.get("type")?.asText() }.toSet()

        // then
        assertThat(types).containsExactlyInAnyOrderElementsOf(
            TransactionType.entries.filter { it != TransactionType.UNKNOWN }.map { it.value },
        )
    }

    @Test
    fun `sends a charged payload twice and the same id with another payload for a conflict`() {
        // given
        val charged = posts().filter { it.expectedStatus == 200 }.map { json(it.body) }

        // when
        val duplicates = charged.groupingBy { it }.eachCount().filterValues { it > 1 }
        val conflictIds = posts().filter { it.expectedStatus == 409 }.mapNotNull { json(it.body)?.get("transaction_id") }

        // then
        assertThat(duplicates).isNotEmpty
        assertThat(conflictIds).isNotEmpty
        assertThat(charged.mapNotNull { it?.get("transaction_id") }).containsAll(conflictIds)
    }

    @Test
    fun `corrects a rejected payload under the same id`() {
        // given
        val rejectedIds = posts().filter { it.expectedStatus == 422 }.mapNotNull { json(it.body)?.get("transaction_id") }

        // when
        val chargedIds = posts().filter { it.expectedStatus == 200 }.mapNotNull { json(it.body)?.get("transaction_id") }

        // then
        assertThat(rejectedIds.intersect(chargedIds.toSet())).isNotEmpty
    }

    @Test
    fun `covers invalid payloads and status lookups`() {
        // given
        val gets = examples.filter { it.method == "GET" }

        // when
        val invalid = posts().filter { it.expectedStatus == 400 }

        // then
        assertThat(invalid).anySatisfy { assertThat(json(it.body)).describedAs("a body that isn't JSON").isNull() }
        assertThat(invalid.size).isGreaterThanOrEqualTo(3)
        assertThat(gets.map { it.expectedStatus }).contains(200, 404)
    }

    @Test
    fun `parses variables, overrides, headers, body and expectation`() {
        // given
        val text = """
            @host = http://a
            @id = x

            ### First
            # a comment
            # Expect: 201 {"id": "{{id}}"}
            POST {{host}}/p/{{id}}
            Content-Type: application/json
            X-Other: {{id}}

            {"id": "{{id}}"}

            ### Second
            # Expect: 404
            GET {{host}}/p
        """.trimIndent()

        // when
        val parsed = HttpRequestsFile.parse(text, mapOf("id" to "y"))

        // then
        assertThat(parsed).containsExactly(
            HttpRequestsFile.Example(
                name = "First",
                expectedStatus = 201,
                expectedBody = """{"id": "y"}""",
                method = "POST",
                url = "http://a/p/y",
                headers = mapOf("Content-Type" to "application/json", "X-Other" to "y"),
                body = """{"id": "y"}""",
            ),
            HttpRequestsFile.Example("Second", 404, null, "GET", "http://a/p", emptyMap(), null),
        )
    }

    @Test
    fun `rejects a request without an expectation`() {
        // given
        val text = "### No expectation\nGET http://a/p\n"

        // when / then
        assertThatThrownBy { HttpRequestsFile.parse(text) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("No expectation")
            .hasMessageContaining("# Expect:")
    }

    @Test
    fun `rejects an unknown variable`() {
        // given
        val text = "### Unknown\n# Expect: 200\nGET {{nowhere}}/p\n"

        // when / then
        assertThatThrownBy { HttpRequestsFile.parse(text) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("{{nowhere}}")
    }
}
