package com.example.fee.restate

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * Starts and stops the real endpoint server on a free port picked per test (never a fixed one). Registration and
 * invocations through a real Restate are the ITs' (section 7).
 */
class RestateEndpointLifecycleTest {

    private val port = freePort()
    private val lifecycle = lifecycle(port)

    @AfterEach
    fun stopEndpoint() {
        lifecycle.stop()
    }

    @Test
    fun `starts on the configured free port`() {
        // given: the lifecycle on a free port

        // when
        lifecycle.start()

        // then
        assertThat(lifecycle.port()).isEqualTo(port)
        assertThat(canBind(port)).isFalse()
    }

    @Test
    fun `serves the FeeWorkflow and TransactionFeeRecord handlers to Restate's discovery`() {
        // given
        lifecycle.start()

        // when
        val response = discover(port)

        // then
        assertThat(response.statusCode()).isEqualTo(200)
        assertThat(response.body())
            .contains("\"${FeeWorkflowHandlers.Metadata.SERVICE_NAME}\"")
            .contains("\"${TransactionFeeRecordHandlers.Metadata.SERVICE_NAME}\"")
    }

    @Test
    fun `isRunning follows start and stop`() {
        // given
        val beforeStart = lifecycle.isRunning

        // when
        lifecycle.start()
        val afterStart = lifecycle.isRunning
        lifecycle.stop()
        val afterStop = lifecycle.isRunning

        // then
        assertThat(beforeStart).isFalse()
        assertThat(afterStart).isTrue()
        assertThat(afterStop).isFalse()
        assertThat(lifecycle.port()).isNull()
    }

    @Test
    fun `stop releases the port`() {
        // given
        lifecycle.start()

        // when
        lifecycle.stop()

        // then
        assertThat(canBind(port)).isTrue()
    }

    @Test
    fun `starts again on the same port after a stop, as after a restart`() {
        // given
        lifecycle.start()
        lifecycle.stop()

        // when
        lifecycle.start()

        // then
        assertThat(lifecycle.isRunning).isTrue()
        assertThat(lifecycle.port()).isEqualTo(port)
        assertThat(discover(port).statusCode()).isEqualTo(200)
    }

    @Test
    fun `a second start keeps the running server`() {
        // given
        lifecycle.start()

        // when
        lifecycle.start()

        // then
        assertThat(lifecycle.isRunning).isTrue()
        assertThat(discover(port).statusCode()).isEqualTo(200)
    }

    @Test
    fun `stop without a start does nothing`() {
        // given: never started

        // when
        lifecycle.stop()

        // then
        assertThat(lifecycle.isRunning).isFalse()
    }

    @Test
    fun `port 0 lets the OS pick the port`() {
        // given
        val anyPort = lifecycle(0)

        try {
            // when
            anyPort.start()

            // then
            val picked = anyPort.port()
            assertThat(picked).isNotNull().isPositive()
            assertThat(discover(checkNotNull(picked)).statusCode()).isEqualTo(200)
        } finally {
            anyPort.stop()
        }
    }

    @Test
    fun `fails with a message naming the port when it is taken, and stays stopped`() {
        // given
        ServerSocket().use { taken ->
            taken.bind(InetSocketAddress(port))

            // when / then
            assertThatThrownBy { lifecycle.start() }
                .isInstanceOf(IllegalStateException::class.java)
                .hasMessageContaining("port $port")
                .hasMessageContaining("restate.endpoint-port")
        }
        assertThat(lifecycle.isRunning).isFalse()
    }

    @Test
    fun `starts after a failed start once the port is free again`() {
        // given
        ServerSocket().use { taken ->
            taken.bind(InetSocketAddress(port))
            runCatching { lifecycle.start() }
        }

        // when
        lifecycle.start()

        // then
        assertThat(lifecycle.isRunning).isTrue()
        assertThat(discover(port).statusCode()).isEqualTo(200)
    }

    private fun lifecycle(endpointPort: Int) = RestateEndpointLifecycle(
        feeWorkflow = mock(FeeWorkflow::class.java),
        transactionFeeRecord = TransactionFeeRecord(ClaimDecider()),
        properties = RestateProperties(
            ingressUrl = "http://localhost:8080",
            adminUrl = "http://localhost:9070",
            endpointPort = endpointPort,
            advertisedUrl = "http://localhost:$endpointPort",
            attachTimeout = Duration.ofSeconds(10),
            registration = RestateProperties.Registration(true, 10, Duration.ofSeconds(2)),
        ),
    )

    private fun discover(port: Int): HttpResponse<String> =
        HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build().use { client ->
            client.send(
                HttpRequest.newBuilder(URI("http://localhost:$port/discover"))
                    .header("Accept", DISCOVERY_MEDIA_TYPE)
                    .timeout(Duration.ofSeconds(10))
                    .GET()
                    .build(),
                HttpResponse.BodyHandlers.ofString(),
            )
        }

    private companion object {
        // The manifest version Restate 1.8.1 asks for first (D28); the SDK answers it on HTTP/1.1 too.
        const val DISCOVERY_MEDIA_TYPE = "application/vnd.restate.endpointmanifest.v3+json"

        fun freePort(): Int = ServerSocket(0).use { it.localPort }

        fun canBind(port: Int): Boolean =
            runCatching { ServerSocket().use { it.bind(InetSocketAddress(port)) } }.isSuccess
    }
}
