package com.example.fee.restate

import com.example.fee.config.RestateProperties
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.sun.net.httpserver.HttpServer
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.time.Duration

/**
 * [AdminApiRestateDeployments] against a fake of Restate's admin API on a free port: the requests it sends and how it
 * reads the answers. That Restate accepts them is the ITs' (`EndpointRegistrationIT`).
 */
class RestateDeploymentsTest {
    private val adminApi = FakeAdminApi()
    private val deployments = AdminApiRestateDeployments(properties("http://localhost:${adminApi.port}"))

    @AfterEach
    fun stopAdminApi() {
        adminApi.stop()
    }

    @Test
    fun `register posts the uri with force and returns the deployment Restate answers`() {
        // given
        adminApi.answer(201, """{"id":"dp_1","services":[{"name":"FeeWorkflow"},{"name":"TransactionFeeRecord"}]}""")

        // when
        val deployment = deployments.register("http://dev:9080")

        // then
        assertThat(deployment).isEqualTo(Deployment("dp_1", listOf("FeeWorkflow", "TransactionFeeRecord")))
        val request = adminApi.requests.single()
        assertThat(request.method).isEqualTo("POST")
        assertThat(request.path).isEqualTo("/deployments")
        assertThat(request.json["uri"].asText()).isEqualTo("http://dev:9080")
        assertThat(request.json["force"].asBoolean()).isTrue()
    }

    @Test
    fun `register fails with Restate's answer when Restate refuses it`() {
        // given
        adminApi.answer(400, """{"message":"bad uri"}""")

        // when / then
        assertThatThrownBy { deployments.register("http://dev:9080") }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("POST")
            .hasMessageContaining("/deployments")
            .hasMessageContaining("400")
            .hasMessageContaining("bad uri")
    }

    @Test
    fun `register fails when the answer has no deployment id`() {
        // given
        adminApi.answer(201, """{"services":[]}""")

        // when / then
        assertThatThrownBy { deployments.register("http://dev:9080") }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("'id'")
    }

    @Test
    fun `unfinishedInvocations counts invocations pinned to the deployment or not yet started for its services`() {
        // given
        adminApi.answer(200, """{"rows":[{"unfinished":3}]}""")

        // when
        val unfinished = deployments.unfinishedInvocations(Deployment("dp_1", listOf("FeeWorkflow", "TransactionFeeRecord")))

        // then
        assertThat(unfinished).isEqualTo(3)
        val request = adminApi.requests.single()
        assertThat(request.method).isEqualTo("POST")
        assertThat(request.path).isEqualTo("/query")
        assertThat(request.accept).isEqualTo("application/json")
        assertThat(request.json["query"].asText())
            .contains("FROM sys_invocation")
            .contains("status <> 'completed'")
            .contains("pinned_deployment_id = 'dp_1'")
            .contains("pinned_deployment_id IS NULL AND target_service_name IN ('FeeWorkflow', 'TransactionFeeRecord')")
    }

    @Test
    fun `unfinishedInvocations escapes quotes in what it puts into the query`() {
        // given
        adminApi.answer(200, """{"rows":[{"unfinished":0}]}""")

        // when
        deployments.unfinishedInvocations(Deployment("dp_'1", listOf("Fee'Workflow")))

        // then
        assertThat(
            adminApi.requests
                .single()
                .json["query"]
                .asText(),
        ).contains("pinned_deployment_id = 'dp_''1'")
            .contains("IN ('Fee''Workflow')")
    }

    @Test
    fun `unfinishedInvocations fails rather than read a missing count as none`() {
        // given
        adminApi.answer(200, """{"rows":[]}""")

        // when / then
        assertThatThrownBy { deployments.unfinishedInvocations(Deployment("dp_1", listOf("FeeWorkflow"))) }
            .isInstanceOf(IllegalStateException::class.java)
    }

    @Test
    fun `unfinishedInvocations fails rather than read a count that is not a number as none`() {
        // given
        adminApi.answer(200, """{"rows":[{"unfinished":"many"}]}""")

        // when / then
        assertThatThrownBy { deployments.unfinishedInvocations(Deployment("dp_1", listOf("FeeWorkflow"))) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("not a number")
    }

    @Test
    fun `remove deletes the deployment with force`() {
        // given
        adminApi.answer(202, "")

        // when
        deployments.remove(Deployment("dp_1", listOf("FeeWorkflow")))

        // then
        val request = adminApi.requests.single()
        assertThat(request.method).isEqualTo("DELETE")
        assertThat(request.path).isEqualTo("/deployments/dp_1")
        assertThat(request.query).isEqualTo("force=true")
    }

    @Test
    fun `remove fails with Restate's answer when Restate refuses it`() {
        // given
        adminApi.answer(404, """{"message":"unknown deployment"}""")

        // when / then
        assertThatThrownBy { deployments.remove(Deployment("dp_1", listOf("FeeWorkflow"))) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("404")
            .hasMessageContaining("unknown deployment")
    }

    @Test
    fun `a trailing slash in the admin url doesn't double the slash in the path`() {
        // given
        val withSlash = AdminApiRestateDeployments(properties("http://localhost:${adminApi.port}/"))
        adminApi.answer(202, "")

        // when
        withSlash.remove(Deployment("dp_1", listOf("FeeWorkflow")))

        // then
        assertThat(adminApi.requests.single().path).isEqualTo("/deployments/dp_1")
    }

    @Test
    fun `fails when the admin api can't be reached`() {
        // given
        val closedPort = ServerSocket(0).use { it.localPort }
        val unreachable = AdminApiRestateDeployments(properties("http://localhost:$closedPort"))

        // when / then
        assertThatThrownBy { unreachable.register("http://dev:9080") }.isInstanceOf(Exception::class.java)
    }

    private fun properties(adminUrl: String) =
        RestateProperties(
            ingressUrl = "http://localhost:8080",
            adminUrl = adminUrl,
            endpointPort = 9080,
            advertisedHost = "dev",
            attachTimeout = Duration.ofSeconds(10),
        )

    /** Restate's admin API on a free port: answers every request with the one configured answer and records it. */
    private class FakeAdminApi {
        data class Request(
            val method: String,
            val path: String,
            val query: String?,
            val accept: String?,
            val json: JsonNode,
        )

        val requests = mutableListOf<Request>()
        private var status = 500
        private var body = ""
        private val mapper = ObjectMapper()
        private val server = HttpServer.create(InetSocketAddress("localhost", 0), 0)

        val port: Int = server.address.port

        init {
            server.createContext("/") { exchange ->
                val sent = exchange.requestBody.readAllBytes().decodeToString()
                synchronized(requests) {
                    requests +=
                        Request(
                            method = exchange.requestMethod,
                            path = exchange.requestURI.path,
                            query = exchange.requestURI.query,
                            accept = exchange.requestHeaders.getFirst("Accept"),
                            json = mapper.readTree(sent.ifBlank { "{}" }),
                        )
                }
                val answer = body.toByteArray()
                exchange.responseHeaders.add("Content-Type", "application/json")
                exchange.sendResponseHeaders(status, if (answer.isEmpty()) -1 else answer.size.toLong())
                exchange.responseBody.use { it.write(answer) }
            }
            server.start()
        }

        fun answer(
            status: Int,
            body: String,
        ) {
            this.status = status
            this.body = body
        }

        fun stop() = server.stop(0)
    }
}
