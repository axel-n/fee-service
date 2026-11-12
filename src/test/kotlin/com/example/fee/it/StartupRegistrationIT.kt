package com.example.fee.it

import com.example.fee.restate.RegistrationStatus
import com.example.fee.restate.RegistrationStatus.State
import com.example.fee.restate.RestateEndpointLifecycle
import com.example.fee.restate.RestateRegistrar
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.ConfigurableApplicationContext
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * Self-registration on startup (D15, D31) against the shared Restate: the app booted by `@SpringBootTest`, then apps
 * started by the tests themselves while the shared endpoint is stopped (only one app can hold the run's endpoint port).
 */
@ExtendWith(OutputCaptureExtension::class)
class StartupRegistrationIT @Autowired constructor(
    private val sharedEndpoint: RestateEndpointLifecycle,
    private val sharedRegistrar: RestateRegistrar,
    private val sharedStatus: RegistrationStatus,
    @LocalServerPort private val apiPort: Int,
) : RestateIntegrationTest() {

    private val restate = RestateTestEnvironment
    private val advertisedUrl = restate.advertisedUrl
    private val apps = mutableListOf<ConfigurableApplicationContext>()

    @BeforeEach
    fun releaseEndpointPort() {
        sharedEndpoint.stop()
    }

    @AfterEach
    fun restoreSharedApp() {
        restate.thaw()
        apps.forEach { it.close() }
        sharedEndpoint.start()
        // a failed test may have left Restate without the deployment the other ITs rely on
        sharedRegistrar.onApplicationReady()
        awaitUntil("the shared app is registered again") { sharedStatus.state == State.REGISTERED }
    }

    @Test
    fun `the app booted for the tests registered its endpoint and serves the API on a random port`() {
        // given
        sharedEndpoint.start()
        val unknownId = HttpRequest.newBuilder(URI("http://localhost:$apiPort/transaction/fee/txn_never_sent")).GET().build()

        // when
        val response = HttpClient.newHttpClient().send(unknownId, HttpResponse.BodyHandlers.ofString())

        // then
        assertThat(sharedStatus.state).isEqualTo(State.REGISTERED)
        assertServicesServedBy(restate.admin.deploymentsFor(advertisedUrl).single().id)
        assertThat(response.statusCode()).isEqualTo(404)
    }

    @Test
    fun `a fresh Restate gets the endpoint registered on startup with its services`(output: CapturedOutput) {
        // given
        restate.admin.deleteDeploymentsFor(advertisedUrl)
        assertThat(restate.admin.services()).doesNotContainKeys(*SERVICES)

        // when
        val app = start()

        // then
        assertThat(app.getBean(RegistrationStatus::class.java).state).isEqualTo(State.REGISTERED)
        val deployment = restate.admin.deploymentsFor(advertisedUrl).single()
        assertThat(deployment.services.keys).containsExactlyInAnyOrder(*SERVICES)
        assertServicesServedBy(deployment.id)
        assertThat(output.out).contains("Restate endpoint $advertisedUrl: registered as deployment ${deployment.id}")
    }

    @Test
    fun `a second start of the same app leaves the registered deployment as it is`(output: CapturedOutput) {
        // given
        start().close()
        val registered = restate.admin.deploymentsFor(advertisedUrl).single()
        val outputBefore = output.out.length

        // when
        val app = start()

        // then
        assertThat(app.getBean(RegistrationStatus::class.java).state).isEqualTo(State.REGISTERED)
        assertThat(restate.admin.deploymentsFor(advertisedUrl)).containsExactly(registered)
        assertThat(output.out.substring(outputBefore))
            .contains("already registered as deployment ${registered.id} with the current services")
            .doesNotContain("re-registered")
    }

    @Test
    fun `an app started while Restate is frozen starts with a WARN and registers once Restate thaws`(output: CapturedOutput) {
        // given
        restate.admin.deleteDeploymentsFor(advertisedUrl)
        restate.freeze()

        // when
        val app = start(
            mapOf(
                // a frozen Restate accepts connections and never answers, so each attempt ends at the read timeout
                "spring.http.client.connect-timeout" to "1s",
                "spring.http.client.read-timeout" to "1s",
                "restate.registration.max-attempts" to "2",
                "restate.registration.backoff" to "1s",
            ),
        )

        // then
        val status = app.getBean(RegistrationStatus::class.java)
        assertThat(app.isActive).isTrue()
        assertThat(app.getBean(RestateEndpointLifecycle::class.java).isRunning).isTrue()
        awaitUntil("the registrar logs a WARN") {
            output.out.lines().any { " WARN " in it && "Restate does not know endpoint $advertisedUrl after 2 attempts" in it }
        }
        assertThat(status.state).isEqualTo(State.FAILING)
        assertThat(adminIsUnreachable()).isTrue()

        // when
        restate.thaw()

        // then
        awaitUntil("the background retry registers the endpoint") { status.state == State.REGISTERED }
        assertServicesServedBy(restate.admin.deploymentsFor(advertisedUrl).single().id)
    }

    private fun start(overrides: Map<String, String> = emptyMap()): ConfigurableApplicationContext =
        startApp(overrides).also { apps += it }

    private fun assertServicesServedBy(deploymentId: String) {
        assertThat(restate.admin.services()).containsAllEntriesOf(SERVICES.associateWith { deploymentId })
    }

    // The admin API of a frozen Restate doesn't answer: the freeze really happened
    private fun adminIsUnreachable(): Boolean =
        runCatching { RestateAdminApi(restate.adminUrl, Duration.ofSeconds(1)).deployments() }.isFailure

    private companion object {
        private val SERVICES = arrayOf("FeeWorkflow", "TransactionFeeRecord")
    }
}
