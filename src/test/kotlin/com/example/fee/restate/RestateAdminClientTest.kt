package com.example.fee.restate

import com.example.fee.config.RestateProperties
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.match.MockRestRequestMatchers.content
import org.springframework.test.web.client.match.MockRestRequestMatchers.header
import org.springframework.test.web.client.match.MockRestRequestMatchers.method
import org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo
import org.springframework.test.web.client.response.MockRestResponseCreators.withException
import org.springframework.test.web.client.response.MockRestResponseCreators.withServerError
import org.springframework.test.web.client.response.MockRestResponseCreators.withStatus
import org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess
import org.springframework.web.client.ResourceAccessException
import org.springframework.web.client.RestClient
import java.io.IOException
import java.net.ConnectException
import java.time.Duration

class RestateAdminClientTest {

    private val adminUrl = "http://restate-admin:9070"

    private fun properties(adminUrl: String = this.adminUrl) = RestateProperties(
        ingressUrl = "http://restate-ingress:8080",
        adminUrl = adminUrl,
        endpointPort = 9080,
        advertisedUrl = "http://dev:9080",
        attachTimeout = Duration.ofSeconds(10),
        registration = RestateProperties.Registration(true, 10, Duration.ofSeconds(2)),
    )

    private val builder = RestClient.builder()
    private val server = MockRestServiceServer.bindTo(builder).build()
    private fun client(adminUrl: String = this.adminUrl) = RestateAdminClient(builder, properties(adminUrl))

    // The shape Restate 1.8.1 answers with (D31), including fields the client ignores.
    private val feeWorkflowJson = """
        {
          "name": "FeeWorkflow", "ty": "Workflow", "documentation": "Validates, prices and charges.",
          "deployment_id": "dp_1", "revision": 1, "public": true, "idempotency_retention": "1d",
          "retry_policy": {"initial_interval": "500ms", "max_attempts": 70, "on_max_attempts": "Pause"},
          "handlers": [
            {"name": "run", "ty": "Workflow", "public": true, "input_description": "JSON 'FeeWorkflowInput'",
             "output_description": "application/json", "input_json_schema": {"type": "object"},
             "retry_policy": {"max_attempts": null}},
            {"name": "status", "ty": "Shared", "input_description": "none", "output_description": "JSON string"}
          ]
        }
    """.trimIndent()

    private val feeWorkflowDescription = ServiceDescription(
        name = "FeeWorkflow",
        type = "Workflow",
        handlers = listOf(
            HandlerDescription("run", "Workflow", "JSON 'FeeWorkflowInput'", "application/json"),
            HandlerDescription("status", "Shared", "none", "JSON string"),
        ),
    )

    @Test
    fun `lists the deployments with their URIs and service revisions`() {
        // given
        server.expect(requestTo("$adminUrl/deployments"))
            .andExpect(method(HttpMethod.GET))
            .andExpect(header("Accept", MediaType.APPLICATION_JSON_VALUE))
            .andRespond(
                withSuccess(
                    """
                    {"deployments": [
                      {"id": "dp_1", "uri": "http://dev:9080/", "protocol_type": "BidiStream", "created_at": "2026-11-12T10:00:00Z",
                       "services": [{"name": "FeeWorkflow", "revision": 1}, {"name": "TransactionFeeRecord", "revision": 2}]},
                      {"id": "dp_2", "arn": "arn:aws:lambda:eu-central-1:1:function:other", "services": []}
                    ]}
                    """.trimIndent(),
                    MediaType.APPLICATION_JSON,
                ),
            )

        // when
        val deployments = client().listDeployments()

        // then
        server.verify()
        assertThat(deployments).containsExactly(
            DeploymentSummary(
                "dp_1",
                "http://dev:9080/",
                listOf(ServiceRevision("FeeWorkflow", 1), ServiceRevision("TransactionFeeRecord", 2)),
            ),
            DeploymentSummary("dp_2", null, emptyList()),
        )
    }

    @Test
    fun `lists no deployments when Restate knows none`() {
        // given
        server.expect(requestTo("$adminUrl/deployments"))
            .andRespond(withSuccess("""{"deployments": []}""", MediaType.APPLICATION_JSON))

        // when
        val deployments = client().listDeployments()

        // then
        server.verify()
        assertThat(deployments).isEmpty()
    }

    @Test
    fun `admin URL with a trailing slash still reaches the deployments path`() {
        // given
        server.expect(requestTo("$adminUrl/deployments"))
            .andRespond(withSuccess("""{"deployments": []}""", MediaType.APPLICATION_JSON))

        // when
        client("$adminUrl/").listDeployments()

        // then
        server.verify()
    }

    @Test
    fun `reads a deployment's registered services and handlers`() {
        // given
        server.expect(requestTo("$adminUrl/deployments/dp_1"))
            .andExpect(method(HttpMethod.GET))
            .andRespond(
                withSuccess(
                    """{"id": "dp_1", "uri": "http://dev:9080/", "http_version": "HTTP/2.0", "services": [$feeWorkflowJson]}""",
                    MediaType.APPLICATION_JSON,
                ),
            )

        // when
        val deployment = client().deployment("dp_1")

        // then
        server.verify()
        assertThat(deployment).isEqualTo(DeploymentDetail("dp_1", "http://dev:9080/", listOf(feeWorkflowDescription)))
    }

    @Test
    fun `registers without force by default`() {
        // given
        server.expect(requestTo("$adminUrl/deployments"))
            .andExpect(method(HttpMethod.POST))
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
            .andExpect(content().json("""{"uri": "http://dev:9080", "force": false, "dry_run": false}""", true))
            .andRespond(
                withStatus(HttpStatus.CREATED)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body("""{"id": "dp_1", "min_protocol_version": 5, "services": [$feeWorkflowJson]}"""),
            )

        // when
        val result = client().register("http://dev:9080")

        // then
        server.verify()
        assertThat(result).isEqualTo(RegistrationResult("dp_1", listOf(feeWorkflowDescription)))
    }

    @Test
    fun `registers with force when asked`() {
        // given
        server.expect(requestTo("$adminUrl/deployments"))
            .andExpect(method(HttpMethod.POST))
            .andExpect(content().json("""{"uri": "http://dev:9080", "force": true, "dry_run": false}""", true))
            .andRespond(withSuccess("""{"id": "dp_1", "services": [$feeWorkflowJson]}""", MediaType.APPLICATION_JSON))

        // when
        val result = client().register("http://dev:9080", force = true)

        // then
        server.verify()
        assertThat(result.id).isEqualTo("dp_1")
        assertThat(result.services).containsExactly(feeWorkflowDescription)
    }

    @Test
    fun `discovers the endpoint's services in a dry run`() {
        // given
        server.expect(requestTo("$adminUrl/deployments"))
            .andExpect(method(HttpMethod.POST))
            .andExpect(content().json("""{"uri": "http://dev:9080", "force": true, "dry_run": true}""", true))
            .andRespond(withSuccess("""{"id": "dp_1", "services": [$feeWorkflowJson]}""", MediaType.APPLICATION_JSON))

        // when
        val result = client().register("http://dev:9080", force = true, dryRun = true)

        // then
        server.verify()
        assertThat(result.services).containsExactly(feeWorkflowDescription)
    }

    @Test
    fun `an error status fails with a message naming the call and the admin URL`() {
        // given
        server.expect(requestTo("$adminUrl/deployments"))
            .andRespond(
                withServerError()
                    .contentType(MediaType.APPLICATION_JSON)
                    .body("""{"message": "[META0003] error when calling 'http://dev:9080/'", "restate_code": "META0003"}"""),
            )

        // when / then
        assertThatThrownBy { client().register("http://dev:9080", force = true) }
            .isInstanceOf(RestateAdminException::class.java)
            .hasMessageContaining("Restate admin API at $adminUrl failed to register 'http://dev:9080' with force")
            .hasMessageContaining("500")
    }

    @Test
    fun `an unreachable admin API fails with a RestateAdminException`() {
        // given
        server.expect(requestTo("$adminUrl/deployments"))
            .andRespond(withException(ConnectException("Connection refused")))

        // when / then
        assertThatThrownBy { client().listDeployments() }
            .isInstanceOf(RestateAdminException::class.java)
            .hasMessageContaining("failed to list deployments")
            .hasCauseInstanceOf(ResourceAccessException::class.java)
            .hasRootCauseInstanceOf(IOException::class.java)
    }

    @Test
    fun `an empty body fails instead of returning nothing`() {
        // given
        server.expect(requestTo("$adminUrl/deployments/dp_1"))
            .andRespond(withStatus(HttpStatus.OK).contentType(MediaType.APPLICATION_JSON))

        // when / then
        assertThatThrownBy { client().deployment("dp_1") }
            .isInstanceOf(RestateAdminException::class.java)
            .hasMessage("Restate admin API at $adminUrl failed to get deployment 'dp_1': empty response")
    }

    @Test
    fun `a body that is not the expected JSON fails with a RestateAdminException`() {
        // given
        server.expect(requestTo("$adminUrl/deployments"))
            .andRespond(withSuccess("""{"deployments": [{"uri": "http://dev:9080/"}]}""", MediaType.APPLICATION_JSON))

        // when / then
        assertThatThrownBy { client().listDeployments() }
            .isInstanceOf(RestateAdminException::class.java)
            .hasMessageContaining("failed to list deployments")
    }
}
