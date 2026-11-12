package com.example.fee.it

import com.example.fee.restate.RegistrationStatus
import com.example.fee.restate.RegistrationStatus.State
import com.example.fee.restate.RestateEndpointLifecycle
import com.example.fee.restate.RestateRegistrar
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.io.File
import java.net.ServerSocket
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Rehearses the live demo (task 10.2, `docs/demo.md`): runs `demo/demo.sh --no-pause` against the shared Restate, so the
 * demo can't drift from the app. The script runs the packaged jar as real processes and kills one with `kill -9` while
 * the fake wallet waits in CHARGE; it checks each of its claims itself and exits non-zero if one fails. This test then
 * checks from Restate's side that the resumed workflow journaled each step once.
 *
 * Only one app can hold the run's endpoint port, so the shared endpoint is stopped while the script's apps run.
 */
class DemoIT @Autowired constructor(
    private val sharedEndpoint: RestateEndpointLifecycle,
    private val sharedRegistrar: RestateRegistrar,
    private val sharedStatus: RegistrationStatus,
) : RestateIntegrationTest() {

    private val restate = RestateTestEnvironment
    private val logDir = File("target/demo-it")

    @BeforeEach
    fun releaseEndpointPort() {
        sharedEndpoint.stop()
    }

    @AfterEach
    fun restoreSharedApp() {
        sharedEndpoint.start()
        sharedRegistrar.onApplicationReady()
        awaitUntil("the shared app is registered again") { sharedStatus.state == State.REGISTERED }
    }

    @Test
    fun `the demo script runs start to end against Restate and charges each transaction once`() {
        // given
        val run = "it${UUID.randomUUID().toString().take(8)}"
        val txn2 = "txn_002-$run"
        logDir.mkdirs()
        val transcript = File(logDir, "demo-output.txt")
        val process = ProcessBuilder("bash", "demo/demo.sh", "--no-pause")
            .redirectErrorStream(true)
            .redirectOutput(transcript)
            .also { it.environment().putAll(demoEnvironment(run)) }

        // when
        val exitCode = runToEnd(process)

        // then
        val output = transcript.readText()
        assertThat(exitCode).describedAs("exit code of demo/demo.sh, output:\n%s", output).isZero()
        assertThat(output)
            .contains("Restate endpoint ${restate.advertisedUrl}: already registered")
            .contains("same response: the same payload is the same workflow key")
            .contains("kill -9 ")
            .contains("nothing charged yet: the wallet was still waiting")
            .contains("show exactly one charge of fee-$txn2")
            .contains("Demo finished: txn_001-$run and $txn2 were charged once each")
        val workflow = restate.admin.query(
            "SELECT id, status FROM sys_invocation " +
                "WHERE target_service_name = 'FeeWorkflow' AND target_service_key LIKE '$txn2:%'",
        ).single()
        assertThat(workflow["status"].asText()).isEqualTo("completed")
        val runs = restate.admin.query(
            "SELECT name FROM sys_journal WHERE id = '${workflow["id"].asText()}' AND entry_type = 'Command: Run'",
        ).map { it["name"].asText() }
        assertThat(runs).containsExactly("validate", "calculate", "charge")
    }

    // Every setting the script and its apps read, so the developer's own RESTATE_* variables can't point them elsewhere.
    private fun demoEnvironment(run: String): Map<String, String> = mapOf(
        "RESTATE_INGRESS_URL" to restate.ingressUrl,
        "RESTATE_ADMIN_URL" to restate.adminUrl,
        "RESTATE_ADVERTISED_URL" to restate.advertisedUrl,
        "RESTATE_ENDPOINT_PORT" to restate.endpointPort.toString(),
        "DEMO_API_PORT" to ServerSocket(0).use { it.localPort }.toString(),
        "DEMO_SLOW_WALLET" to "10s",
        "DEMO_RUN" to run,
        "DEMO_JAR" to appJar().path,
        "DEMO_LOG_DIR" to logDir.path,
        // only the packaged configuration, like every IT app: a local config/application.yml can't change the fees
        "DEMO_APP_ARGS" to "--spring.config.location=classpath:/",
    )

    // mvn verify packages the app before the ITs run
    private fun appJar(): File =
        File("target").listFiles { file -> file.name.matches(Regex("fee-service-.*\\.jar")) }
            ?.singleOrNull()
            ?: throw AssertionError("no packaged app jar in target/: the demo runs the jar that mvn package builds")

    private fun runToEnd(builder: ProcessBuilder): Int {
        val process = builder.start()
        try {
            if (!process.waitFor(DEMO_TIMEOUT_MINUTES, TimeUnit.MINUTES)) {
                throw AssertionError("demo/demo.sh did not finish within $DEMO_TIMEOUT_MINUTES minutes")
            }
            return process.exitValue()
        } finally {
            // the script stops its app on exit; this covers a script that hung or was killed
            process.descendants().forEach { it.destroyForcibly() }
            process.destroyForcibly()
        }
    }

    private companion object {
        private const val DEMO_TIMEOUT_MINUTES = 6L
    }
}
