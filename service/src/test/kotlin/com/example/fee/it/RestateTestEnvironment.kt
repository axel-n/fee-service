package com.example.fee.it

import org.springframework.test.context.DynamicPropertyRegistry
import org.testcontainers.DockerClientFactory
import org.testcontainers.Testcontainers
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.containers.wait.strategy.WaitAllStrategy
import org.testcontainers.utility.DockerImageName
import java.net.ServerSocket
import java.time.Duration

/**
 * The one Restate every IT of a test run shares: a `restatedev/restate` container (pinned to 1.8.1, the version the SDK was verified against), started on
 * first use and removed by Testcontainers when the JVM exits.
 *
 * Only random ports, so a Restate or an app already running on the machine can't interfere:
 * - the container's ingress and admin ports are mapped by Testcontainers to free host ports;
 * - the endpoint port is a free host port picked once per run, before the container starts, and exposed to the container
 *   as `host.testcontainers.internal:<port>`. It stays the same for the whole run, so an endpoint stopped and started
 *   again (`RestateEndpointLifecycle`) registers itself at the URL Restate already knows. Only one app can listen on it
 *   at a time.
 */
object RestateTestEnvironment {
    /** Restate's ingress port inside its container. */
    const val INGRESS_PORT = 8080

    /** Restate's admin API port inside its container. */
    const val ADMIN_PORT = 9070

    // How the container reaches the machine the tests run on, and so the app's endpoint (exposeHostPorts below)
    private const val ADVERTISED_HOST = "host.testcontainers.internal"

    /** The host port the app's Restate endpoint listens on, the same for the whole run. */
    val endpointPort: Int = ServerSocket(0).use { it.localPort }

    private val container: GenericContainer<*> =
        restateContainer().also {
            // before the start, so the container gets the host.testcontainers.internal entry
            Testcontainers.exposeHostPorts(endpointPort)
            it.start()
        }

    val ingressUrl: String = "http://${container.host}:${container.getMappedPort(INGRESS_PORT)}"

    val adminUrl: String = "http://${container.host}:${container.getMappedPort(ADMIN_PORT)}"

    /** The URL the app registers its endpoint at, as Restate reaches it. */
    val advertisedUrl: String = "http://$ADVERTISED_HOST:$endpointPort"

    /** The tests' view of what the app did in Restate: deployments, introspection, purging. */
    val admin: RestateAdminApi = RestateAdminApi(adminUrl)

    /**
     * Every `restate.*` setting of an app under test. Set through `@DynamicPropertySource`, which wins over environment
     * variables (`RESTATE_INGRESS_URL`, …) and config files, so a developer's local settings can't point the tests at
     * another Restate.
     */
    fun restateProperties(): Map<String, String> =
        mapOf(
            "restate.ingress-url" to ingressUrl,
            "restate.admin-url" to adminUrl,
            "restate.endpoint-port" to endpointPort.toString(),
            "restate.advertised-host" to ADVERTISED_HOST,
            "restate.attach-timeout" to "30s",
        )

    fun registerProperties(registry: DynamicPropertyRegistry) {
        restateProperties().forEach { (key, value) -> registry.add(key) { value } }
    }

    /**
     * Freezes the Restate server with SIGSTOP: connections are still accepted, but nothing answers until [thaw].
     */
    fun freeze() = signal("SIGSTOP")

    /** Thaws a [freeze]d Restate server with SIGCONT; harmless when it isn't frozen. */
    fun thaw() = signal("SIGCONT")

    private fun signal(signal: String) {
        DockerClientFactory
            .instance()
            .client()
            .killContainerCmd(container.containerId)
            .withSignal(signal)
            .exec()
    }
}

private const val RESTATE_IMAGE = "docker.io/restatedev/restate:1.8.1"

/**
 * A new, not yet started Restate container of the pinned version, ready once its admin API and ingress answer; both are
 * mapped to free host ports. [RestateTestEnvironment] starts the shared one with it, `AppContainerIT` its own. Top-level,
 * so creating one doesn't start the shared Restate.
 */
fun restateContainer(): GenericContainer<*> =
    GenericContainer(DockerImageName.parse(RESTATE_IMAGE))
        .withExposedPorts(RestateTestEnvironment.INGRESS_PORT, RestateTestEnvironment.ADMIN_PORT)
        .waitingFor(
            WaitAllStrategy()
                .withStrategy(Wait.forHttp("/health").forPort(RestateTestEnvironment.ADMIN_PORT).forStatusCode(200))
                .withStrategy(Wait.forHttp("/restate/health").forPort(RestateTestEnvironment.INGRESS_PORT).forStatusCode(200))
                .withStartupTimeout(Duration.ofMinutes(2)),
        )
