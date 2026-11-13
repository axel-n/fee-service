package com.example.fee.it

import com.example.fee.ProjectLayout
import com.fasterxml.jackson.databind.ObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.Network
import org.testcontainers.containers.output.Slf4jLogConsumer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.utility.DockerImageName
import org.testcontainers.utility.MountableFile
import java.math.BigDecimal
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.UUID
import java.util.regex.Pattern
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name

/**
 * The packaged app (`java -jar`) in a container next to a Restate container of its own, on a private network, as both
 * run in production: the app reaches Restate as `restate`, Restate reaches the app at its network alias `fee-service`, and
 * no host alias of the machine is involved. It shows that the app connects to Restate when it starts, disconnects when it
 * stops gracefully, and that a run submitted while the app is down waits (Restate answers 470, so the API answers 504)
 * and finishes once the app is back at the same URL.
 *
 * Its own Restate, not [RestateTestEnvironment]'s: an app registered there would be the newest deployment and take the
 * in-process ITs' work. The app's ports are fixed but internal to the network; the test reaches the API and Restate
 * through mapped ports. It needs the packaged jar in `service/target`, which `mvn verify` builds before the ITs run.
 */
class AppContainerIT {
    private val apps = mutableListOf<GenericContainer<*>>()

    @AfterEach
    fun stopApps() {
        // gracefully, so each app removes its deployment and the next test starts without one
        apps.forEach { app ->
            if (app.isRunning) stopGracefully(app)
            app.stop()
        }
    }

    @Test
    fun `the app registers under its network alias and charges a fee`() {
        // given
        val app = startApp()

        // when
        val response = api(app).submit(body(newTransactionId(), "Mobile Top Up"))

        // then
        assertThat(admin.deployments().map { it.uri }).containsExactly("$ADVERTISED_URL/")
        assertThat(response.status).describedAs("POST status, body %s", response.body).isEqualTo(200)
        assertThat(
            response.body
                .fieldNames()
                .asSequence()
                .toList(),
        ).containsExactlyElementsOf(FeeApi.FEE_FIELDS)
        assertThat(response.body["fee"].decimalValue()).isEqualByComparingTo(BigDecimal("1.50"))
    }

    @Test
    fun `an unknown transaction type is rejected with 422 at once`() {
        // given
        val app = startApp()

        // when
        val response = api(app).submit(body(newTransactionId(), "Crypto Purchase"))

        // then
        assertThat(response.status).describedAs("POST status, body %s", response.body).isEqualTo(422)
        assertThat(response.body["code"].asText()).isEqualTo("UNSUPPORTED_REQUEST")
        assertThat(response.body["field"].asText()).isEqualTo("type")
        assertThat(response.body["message"].asText()).isEqualTo(
            "Unsupported type 'Crypto Purchase'. Supported: 'Mobile Top Up', 'Bank Transfer', 'Merchant Payment', " +
                "'Cash Withdrawal'",
        )
    }

    @Test
    fun `a graceful stop with nothing unfinished removes the deployment`() {
        // given: the app charged a fee, so nothing is left unfinished
        val app = startApp()
        val charged = api(app).submit(body(newTransactionId(), "Mobile Top Up"))
        assertThat(charged.status).describedAs("POST status, body %s", charged.body).isEqualTo(200)

        // when: SIGTERM, so Spring shuts down and the endpoint lifecycle stops
        stopGracefully(app)

        // then
        assertThat(admin.deployments()).isEmpty()
    }

    @Test
    fun `a run submitted while the app is down waits, then finishes once the app is back at the same URL`() {
        // given: the app is killed, so its deployment stays, and a run reaches Restate meanwhile
        val first = startApp()
        val deployment = admin.deployments().single()
        kill(first)
        val transactionId = newTransactionId()
        val sent = ingress("POST", "/FeeWorkflow/$transactionId/run/send", body(transactionId, "Mobile Top Up"))
        assertThat(sent.statusCode()).describedAs("send status, body %s", sent.body()).isIn(200, 202)

        // then: Restate retries the run and reports it as not finished, 470, which the API answers with 504
        awaitUntil("Restate retries the run") { runStatus(transactionId) == "backing-off" }
        assertThat(ingress("GET", "/restate/workflow/FeeWorkflow/$transactionId/output").statusCode()).isEqualTo(470)

        // when: the app is back at the same URL
        val second = startApp()

        // then: it is the same deployment, the run finishes, and the fee is charged once
        assertThat(admin.deployments().map { it.id }).containsExactly(deployment.id)
        awaitUntil("the run has finished", Duration.ofMinutes(3)) { runStatus(transactionId) == "completed" }
        val response = api(second).submit(body(transactionId, "Mobile Top Up"))
        assertThat(response.status).describedAs("POST status, body %s", response.body).isEqualTo(200)
        assertThat(response.body["fee"].decimalValue()).isEqualByComparingTo(BigDecimal("1.50"))
        assertThat(invocations("TransactionFeeRecord/$transactionId/complete")).describedAs("charges").isEqualTo(1)
    }

    /** Starts the packaged app on the network; it is ready once it registered its endpoint at [ADVERTISED_URL]. */
    private fun startApp(): GenericContainer<*> =
        GenericContainer(DockerImageName.parse(JAVA_IMAGE))
            .withNetwork(network)
            .withNetworkAliases(APP_ALIAS)
            .withCopyFileToContainer(MountableFile.forHostPath(appJar), "/app/fee-service.jar")
            .withCommand("java", "-jar", "/app/fee-service.jar")
            .withEnv(
                mapOf(
                    "SERVER_PORT" to "$APP_PORT",
                    "RESTATE_INGRESS_URL" to "http://$RESTATE_ALIAS:${RestateTestEnvironment.INGRESS_PORT}",
                    "RESTATE_ADMIN_URL" to "http://$RESTATE_ALIAS:${RestateTestEnvironment.ADMIN_PORT}",
                    "RESTATE_ENDPOINT_PORT" to "$ENDPOINT_PORT",
                    "RESTATE_ADVERTISED_HOST" to APP_ALIAS,
                ),
            ).withExposedPorts(APP_PORT)
            .withLogConsumer(Slf4jLogConsumer(log).withPrefix("app"))
            .waitingFor(
                // (?s): a log line ends with its newline
                Wait
                    .forLogMessage("(?s).*Restate endpoint registered as ${Pattern.quote(ADVERTISED_URL)} .*", 1)
                    .withStartupTimeout(Duration.ofMinutes(2)),
            ).also {
                apps += it
                it.start()
            }

    private fun api(app: GenericContainer<*>) = FeeApi(app.getMappedPort(APP_PORT), app.host)

    // docker stop: SIGTERM, then a wait until the app has shut down
    private fun stopGracefully(app: GenericContainer<*>) {
        DockerClientFactory
            .instance()
            .client()
            .stopContainerCmd(app.containerId)
            .withTimeout(STOP_TIMEOUT_SECONDS)
            .exec()
    }

    // SIGKILL: no shutdown, so the deployment stays; then the container is removed, so its alias resolves to nothing
    private fun kill(app: GenericContainer<*>) {
        DockerClientFactory
            .instance()
            .client()
            .killContainerCmd(app.containerId)
            .exec()
        app.stop()
    }

    private fun runStatus(transactionId: String): String? =
        admin
            .query("SELECT status FROM sys_invocation WHERE target = 'FeeWorkflow/$transactionId/run'")
            .singleOrNull()
            ?.get("status")
            ?.asText()

    private fun invocations(target: String): Long =
        admin
            .query("SELECT count(*) AS invocations FROM sys_invocation WHERE target = '$target'")
            .single()["invocations"]
            .asLong()

    /** A call to Restate's ingress past the app, with [body] as JSON if given. */
    private fun ingress(
        method: String,
        path: String,
        body: Map<String, Any?>? = null,
    ): HttpResponse<String> {
        val request =
            HttpRequest
                .newBuilder(URI("$ingressUrl$path"))
                .timeout(Duration.ofSeconds(10))
                .apply { if (body != null) header("Content-Type", "application/json") }
                .method(
                    method,
                    body?.let { HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(it)) }
                        ?: HttpRequest.BodyPublishers.noBody(),
                ).build()
        return http.send(request, HttpResponse.BodyHandlers.ofString())
    }

    private fun newTransactionId() = "it-container-${UUID.randomUUID().toString().take(13)}"

    private fun body(
        transactionId: String,
        type: String,
    ): Map<String, Any?> =
        mapOf(
            "transaction_id" to transactionId,
            "amount" to BigDecimal("1000"),
            "asset" to "USD",
            "asset_type" to "FIAT",
            "type" to type,
            "state" to "SETTLED - PENDING FEE",
            "created_at" to "2023-08-30 15:42:17.610059",
        )

    companion object {
        private const val JAVA_IMAGE = "docker.io/library/eclipse-temurin:25-jre"
        private const val RESTATE_ALIAS = "restate"
        private const val APP_ALIAS = "fee-service"
        private const val APP_PORT = 8081
        private const val ENDPOINT_PORT = 9080
        private const val ADVERTISED_URL = "http://$APP_ALIAS:$ENDPOINT_PORT"
        private const val STOP_TIMEOUT_SECONDS = 30

        private val log = LoggerFactory.getLogger(AppContainerIT::class.java)
        private val mapper = ObjectMapper()
        private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()

        private val network: Network = Network.newNetwork()

        private val restate: GenericContainer<*> =
            restateContainer()
                .withNetwork(network)
                .withNetworkAliases(RESTATE_ALIAS)
                .also { it.start() }

        private val ingressUrl = "http://${restate.host}:${restate.getMappedPort(RestateTestEnvironment.INGRESS_PORT)}"

        private val admin = RestateAdminApi("http://${restate.host}:${restate.getMappedPort(RestateTestEnvironment.ADMIN_PORT)}")

        /** The jar `mvn package` built; in an IDE, run `mvn -pl service -am package -DskipTests` first. */
        private val appJar: Path by lazy {
            val target = ProjectLayout.serviceDir.resolve("target")
            val jars = if (Files.isDirectory(target)) target.listDirectoryEntries("fee-service-*.jar") else emptyList()
            check(jars.size == 1) {
                "Expected one packaged app jar in $target, found ${jars.map { it.name }}: " +
                    "run `mvn -pl service -am package -DskipTests` first"
            }
            jars.single()
        }

        @JvmStatic
        @AfterAll
        fun stopRestate() {
            restate.stop()
            network.close()
        }
    }
}
