package com.example.fee.restate

import com.example.fee.config.RestateProperties
import com.example.fee.record.ClaimDecider
import com.example.fee.record.RestateFeeRecordState
import com.example.fee.record.TransactionFeeRecord
import com.example.fee.workflow.FeeWorkflow
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * Starts and stops the real endpoint server on a free port picked per test (never a fixed one), with Restate's admin API
 * replaced by [FakeDeployments]. Registration and invocations through a real Restate are the ITs'.
 */
class RestateEndpointLifecycleTest {
    private val port = freePort()
    private val deployments = FakeDeployments()
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
            .contains("\"FeeWorkflow\"")
            .contains("\"TransactionFeeRecord\"")
    }

    @Test
    fun `registers the endpoint as the advertised host and its port once it answers discovery`() {
        // given: never started

        // when
        lifecycle.start()

        // then
        assertThat(deployments.registered).containsExactly("http://$ADVERTISED_HOST:$port")
        assertThat(deployments.discoverableWhenRegistered).containsExactly(true)
    }

    @Test
    fun `fails to start when Restate can't register it, naming the URL and the settings, and frees the port`() {
        // given
        deployments.registerFailure = IOException("Connection refused")

        // when / then
        assertThatThrownBy { lifecycle.start() }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("http://$ADVERTISED_HOST:$port")
            .hasMessageContaining("http://localhost:9070")
            .hasMessageContaining("restate.advertised-host")
            .hasMessageContaining("restate.admin-url")
            .hasMessageContaining("Connection refused")
        assertThat(lifecycle.isRunning).isFalse()
        assertThat(canBind(port)).isTrue()
    }

    @Test
    fun `stop removes the deployment when no unfinished invocation needs it`() {
        // given
        lifecycle.start()
        deployments.unfinished = 0

        // when
        lifecycle.stop()

        // then
        assertThat(deployments.removed).containsExactly(DEPLOYMENT)
    }

    @Test
    fun `stop keeps the deployment while unfinished invocations need it`() {
        // given
        lifecycle.start()
        deployments.unfinished = 2

        // when
        lifecycle.stop()

        // then
        assertThat(deployments.removed).isEmpty()
        assertThat(lifecycle.isRunning).isFalse()
        assertThat(canBind(port)).isTrue()
    }

    @Test
    fun `stop keeps the deployment and still stops when Restate can't be asked`() {
        // given
        lifecycle.start()
        deployments.checkFailure = IOException("Connection refused")

        // when
        lifecycle.stop()

        // then
        assertThat(deployments.removed).isEmpty()
        assertThat(lifecycle.isRunning).isFalse()
        assertThat(canBind(port)).isTrue()
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
    fun `starts again on the same port after a stop and registers the same URL again, as after a restart`() {
        // given
        lifecycle.start()
        lifecycle.stop()

        // when
        lifecycle.start()

        // then
        assertThat(lifecycle.isRunning).isTrue()
        assertThat(lifecycle.port()).isEqualTo(port)
        assertThat(discover(port).statusCode()).isEqualTo(200)
        assertThat(deployments.registered).containsExactly("http://$ADVERTISED_HOST:$port", "http://$ADVERTISED_HOST:$port")
    }

    @Test
    fun `a second start keeps the running server and its registration`() {
        // given
        lifecycle.start()

        // when
        lifecycle.start()

        // then
        assertThat(lifecycle.isRunning).isTrue()
        assertThat(discover(port).statusCode()).isEqualTo(200)
        assertThat(deployments.registered).hasSize(1)
    }

    @Test
    fun `stop without a start does nothing`() {
        // given: never started

        // when
        lifecycle.stop()

        // then
        assertThat(lifecycle.isRunning).isFalse()
        assertThat(deployments.removed).isEmpty()
    }

    @Test
    fun `port 0 lets the OS pick the port, and registers that port`() {
        // given
        val anyPort = lifecycle(0)

        try {
            // when
            anyPort.start()

            // then
            val picked = checkNotNull(anyPort.port())
            assertThat(picked).isPositive()
            assertThat(discover(picked).statusCode()).isEqualTo(200)
            assertThat(deployments.registered).containsExactly("http://$ADVERTISED_HOST:$picked")
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
        assertThat(deployments.registered).isEmpty()
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

    private fun lifecycle(endpointPort: Int) =
        RestateEndpointLifecycle(
            feeWorkflow = mock(FeeWorkflow::class.java),
            transactionFeeRecord = TransactionFeeRecord(ClaimDecider(), RestateFeeRecordState()),
            deployments = deployments,
            properties =
                RestateProperties(
                    ingressUrl = "http://localhost:8080",
                    adminUrl = "http://localhost:9070",
                    endpointPort = endpointPort,
                    advertisedHost = ADVERTISED_HOST,
                    attachTimeout = Duration.ofSeconds(10),
                ),
        )

    /** Restate's admin API in memory: records registrations and removals, answers with the configured results. */
    private class FakeDeployments : RestateDeployments {
        val registered = mutableListOf<String>()
        val discoverableWhenRegistered = mutableListOf<Boolean>()
        val removed = mutableListOf<Deployment>()
        var unfinished = 0L
        var registerFailure: Exception? = null
        var checkFailure: Exception? = null

        // Restate discovers the services at registration, so the endpoint must answer by then (on this machine).
        override fun register(uri: String): Deployment {
            registerFailure?.let { throw it }
            registered += uri
            discoverableWhenRegistered += runCatching { discover(URI(uri).port).statusCode() == 200 }.getOrDefault(false)
            return DEPLOYMENT
        }

        override fun unfinishedInvocations(deployment: Deployment): Long {
            checkFailure?.let { throw it }
            return unfinished
        }

        override fun remove(deployment: Deployment) {
            removed += deployment
        }
    }

    private companion object {
        // The manifest version Restate 1.8.1 asks for first; the SDK answers it on HTTP/1.1 too.
        const val DISCOVERY_MEDIA_TYPE = "application/vnd.restate.endpointmanifest.v3+json"

        // Not this machine's name, so a test proves the registered URL takes the setting, not a guess
        const val ADVERTISED_HOST = "fee.restate-reaches-it.test"

        val DEPLOYMENT = Deployment("dp_test", listOf("FeeWorkflow", "TransactionFeeRecord"))

        fun freePort(): Int = ServerSocket(0).use { it.localPort }

        fun canBind(port: Int): Boolean = runCatching { ServerSocket().use { it.bind(InetSocketAddress(port)) } }.isSuccess

        fun discover(port: Int): HttpResponse<String> =
            HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build().use { client ->
                client.send(
                    HttpRequest
                        .newBuilder(URI("http://localhost:$port/discover"))
                        .header("Accept", DISCOVERY_MEDIA_TYPE)
                        .timeout(Duration.ofSeconds(10))
                        .GET()
                        .build(),
                    HttpResponse.BodyHandlers.ofString(),
                )
            }
    }
}
