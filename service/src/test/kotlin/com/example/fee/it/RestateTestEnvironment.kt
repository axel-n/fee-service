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
 *   again (`RestateEndpointLifecycle`) is reached at the URL Restate already knows. Only one app can listen on it at a time.
 */
object RestateTestEnvironment {

    private const val IMAGE = "docker.io/restatedev/restate:1.8.1"
    private const val INGRESS_PORT = 8080
    private const val ADMIN_PORT = 9070

    /** The host port the app's Restate endpoint listens on, the same for the whole run. */
    val endpointPort: Int = ServerSocket(0).use { it.localPort }

    private val container: GenericContainer<*> = GenericContainer(DockerImageName.parse(IMAGE))
        .withExposedPorts(INGRESS_PORT, ADMIN_PORT)
        .waitingFor(
            WaitAllStrategy()
                .withStrategy(Wait.forHttp("/health").forPort(ADMIN_PORT).forStatusCode(200))
                .withStrategy(Wait.forHttp("/restate/health").forPort(INGRESS_PORT).forStatusCode(200))
                .withStartupTimeout(Duration.ofMinutes(2)),
        )
        .also {
            // before the start, so the container gets the host.testcontainers.internal entry
            Testcontainers.exposeHostPorts(endpointPort)
            it.start()
        }

    val ingressUrl: String = "http://${container.host}:${container.getMappedPort(INGRESS_PORT)}"

    val adminUrl: String = "http://${container.host}:${container.getMappedPort(ADMIN_PORT)}"

    /** The endpoint's URL as Restate reaches it, the one [registerEndpoint] registers. */
    val advertisedUrl: String = "http://host.testcontainers.internal:$endpointPort"

    /** The admin API, which the app itself never calls: registration, introspection, purging. */
    val admin: RestateAdminApi = RestateAdminApi(adminUrl)

    /**
     * Registers the run's endpoint with Restate, the setup step the README asks of whoever runs the app. Restate
     * discovers the services by calling the endpoint, so it must be listening. It keeps the registration for the whole
     * run, also across endpoint restarts, because the URL stays the same.
     */
    fun registerEndpoint() {
        admin.register(advertisedUrl)
    }

    /**
     * Every `restate.*` setting of an app under test. Set through `@DynamicPropertySource`, which wins over environment
     * variables (`RESTATE_INGRESS_URL`, …) and config files, so a developer's local settings can't point the tests at
     * another Restate.
     */
    fun restateProperties(): Map<String, String> = mapOf(
        "restate.ingress-url" to ingressUrl,
        "restate.endpoint-port" to endpointPort.toString(),
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
        DockerClientFactory.instance().client().killContainerCmd(container.containerId).withSignal(signal).exec()
    }
}
