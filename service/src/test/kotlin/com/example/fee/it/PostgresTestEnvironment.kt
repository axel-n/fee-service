package com.example.fee.it

import io.r2dbc.spi.ConnectionFactories
import io.r2dbc.spi.ConnectionFactory
import kotlinx.coroutines.reactive.awaitFirstOrNull
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.jooq.DSLContext
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.springframework.test.context.DynamicPropertyRegistry
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.utility.DockerImageName
import java.time.Duration
import java.util.UUID
import kotlin.time.toKotlinDuration

/**
 * The one Postgres every IT of a test run shares, next to [RestateTestEnvironment]: a `postgres` container started on
 * first use and removed by Testcontainers when the JVM exits. Its port is mapped to a free host port, so a database
 * already running on the machine can't interfere. The app creates its table itself at startup (`schema.sql`).
 */
object PostgresTestEnvironment {
    /** Postgres' port inside its container. */
    const val PORT = 5_432

    const val DATABASE = "fee"
    const val USERNAME = "fee"
    const val PASSWORD = "fee"

    private val container: GenericContainer<*> = postgresContainer().also { it.start() }

    /** The R2DBC URL of the database, as the app's `spring.r2dbc.url`. */
    val url: String = "r2dbc:postgresql://${container.host}:${container.getMappedPort(PORT)}/$DATABASE"

    /** [url] with the credentials, for the tests' own connections. */
    val urlWithCredentials: String = url.replace("r2dbc:postgresql://", "r2dbc:postgresql://$USERNAME:$PASSWORD@")

    /**
     * The tests' own jOOQ, past the app, to read and write `fee_record` rows directly. Not pooled: every query opens a
     * connection of its own.
     */
    val dsl: DSLContext by lazy { jooq(ConnectionFactories.get(urlWithCredentials)) }

    /**
     * The `spring.r2dbc.*` settings of an app under test. Set through `@DynamicPropertySource`, which wins over
     * environment variables (`DATABASE_URL`, …) and config files, so a developer's local settings can't point the tests at
     * another database.
     */
    fun registerProperties(registry: DynamicPropertyRegistry) {
        registry.add("spring.r2dbc.url") { url }
        registry.add("spring.r2dbc.username") { USERNAME }
        registry.add("spring.r2dbc.password") { PASSWORD }
    }

    /**
     * An outage of the database, as every client sees one: open connections are cut and new ones refused ("not currently
     * accepting connections"). The container and its port stay, so [goUp] brings the same database back.
     */
    fun goDown() = psql(REFUSE_CONNECTIONS, CUT_CONNECTIONS)

    /** Ends [goDown]: the database accepts connections again. Harmless when it is up. */
    fun goUp() = psql(ACCEPT_CONNECTIONS)

    /**
     * A database that hangs, as in a network partition: every Postgres process is stopped (SIGSTOP), so connections stay
     * open and new ones are accepted by the kernel, but nothing answers until [thaw].
     */
    fun freeze() {
        signalMain("SIGSTOP")
        signalOthers("STOP")
    }

    /** A new database next to the app's, set up with [ddl], and its R2DBC URL: for an app that must meet that schema. */
    fun databaseWith(ddl: String): String {
        val name = "fee_${UUID.randomUUID().toString().replace("-", "").take(12)}"
        psql("CREATE DATABASE $name")
        val result = container.execInContainer("psql", "-U", USERNAME, "-d", name, "-v", "ON_ERROR_STOP=1", "-c", ddl)
        check(result.exitCode == 0) { "psql failed on the schema of $name: ${result.stderr}" }
        return "r2dbc:postgresql://${container.host}:${container.getMappedPort(PORT)}/$name"
    }

    /** Whether the database answers a query within [timeout], on a new connection of the tests' own. */
    fun answersWithin(timeout: Duration): Boolean =
        runBlocking { withTimeoutOrNull(timeout.toKotlinDuration()) { dsl.selectOne().awaitFirstOrNull() } != null }

    /** Ends [freeze]; harmless when the database isn't frozen. The postmaster is resumed even if the rest failed. */
    fun thaw() {
        try {
            signalOthers("CONT")
        } finally {
            signalMain("SIGCONT")
        }
    }

    // The postmaster is the container's PID 1, which only a signal from outside the container reaches
    private fun signalMain(signal: String) {
        DockerClientFactory
            .instance()
            .client()
            .killContainerCmd(container.containerId)
            .withSignal(signal)
            .exec()
    }

    // Its backends, one per connection, and its helpers, all named `postgres`
    private fun signalOthers(signal: String) {
        val script = "for p in /proc/[0-9]*; do [ \"\$(cat \$p/comm 2>/dev/null)\" = postgres ] && kill -$signal \${p#/proc/}; done; true"
        val result = container.execInContainer("sh", "-c", script)
        check(result.exitCode == 0) { "signalling the Postgres processes failed: ${result.stderr}" }
    }

    // Through the maintenance database `postgres`, the one connection the outage leaves possible
    private fun psql(vararg statements: String) {
        statements.forEach { statement ->
            val result = container.execInContainer("psql", "-U", USERNAME, "-d", "postgres", "-v", "ON_ERROR_STOP=1", "-c", statement)
            check(result.exitCode == 0) { "psql failed on '$statement': ${result.stderr}" }
        }
    }

    private const val REFUSE_CONNECTIONS = "ALTER DATABASE $DATABASE WITH ALLOW_CONNECTIONS false"
    private const val ACCEPT_CONNECTIONS = "ALTER DATABASE $DATABASE WITH ALLOW_CONNECTIONS true"
    private const val CUT_CONNECTIONS = "SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE datname = '$DATABASE'"
}

/** jOOQ for the tests' own queries on a Postgres, past the app, over [connectionFactory]. */
fun jooq(connectionFactory: ConnectionFactory): DSLContext = DSL.using(connectionFactory, SQLDialect.POSTGRES)

// The version of jOOQ's code generation (service/pom.xml) and the dev compose: keep them together
private const val POSTGRES_IMAGE = "docker.io/library/postgres:18.7"

/**
 * A new, not yet started Postgres container of the pinned version with the app's database and user, ready once it
 * accepts connections; its port is mapped to a free host port. [PostgresTestEnvironment] starts the shared one with it,
 * `AppContainerIT` its own. Top-level, so creating one doesn't start the shared Postgres.
 */
fun postgresContainer(): GenericContainer<*> =
    GenericContainer(DockerImageName.parse(POSTGRES_IMAGE))
        .withEnv(
            mapOf(
                "POSTGRES_DB" to PostgresTestEnvironment.DATABASE,
                "POSTGRES_USER" to PostgresTestEnvironment.USERNAME,
                "POSTGRES_PASSWORD" to PostgresTestEnvironment.PASSWORD,
            ),
        ).withExposedPorts(PostgresTestEnvironment.PORT)
        .waitingFor(
            // the image starts a server to initialise the database, stops it, then starts the real one: the second "ready"
            Wait
                .forLogMessage(".*database system is ready to accept connections.*\\s", 2)
                .withStartupTimeout(Duration.ofMinutes(2)),
        )
