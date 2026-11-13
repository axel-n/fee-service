package com.example.fee.it

import com.example.fee.FeeServiceApplication
import com.example.fee.restate.RestateEndpointLifecycle
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.catchThrowable
import org.junit.jupiter.api.Test
import org.springframework.boot.builder.SpringApplicationBuilder
import java.net.ConnectException
import java.net.ServerSocket

/**
 * The app with a database it can't reach, or whose `fee_record` table lacks a column, doesn't start: `schema.sql`
 * runs while the context starts, before the Restate endpoint starts or registers. Restate's admin API is unreachable
 * too, so even a broken order couldn't register this app anywhere, and the test tells the two failures apart.
 */
class DatabaseStartupIT {
    @Test
    fun `the app doesn't start when its database can't be reached, and its Restate endpoint never starts`() {
        // given: a free port nothing listens on, for the database and for Restate
        val unreachable = ServerSocket(0).use { it.localPort }
        val app = SpringApplicationBuilder(FeeServiceApplication::class.java)

        // when: command-line arguments, so they win over the packaged placeholders and any environment variable
        val failure =
            catchThrowable {
                app
                    .run(
                        "--spring.config.location=classpath:/",
                        "--server.port=0",
                        "--spring.r2dbc.url=r2dbc:postgresql://localhost:$unreachable/fee",
                        "--restate.ingress-url=http://localhost:$unreachable",
                        "--restate.admin-url=http://localhost:$unreachable",
                        "--restate.endpoint-port=0",
                    ).close()
            }

        // then: the database's connection failure while schema.sql runs, before the endpoint lifecycle ever started
        assertThat(failure).isNotNull()
        assertThat(failure).hasRootCauseInstanceOf(ConnectException::class.java)
        assertThat(failure).hasStackTraceContaining("r2dbcScriptDatabaseInitializer")
        assertThat(failure).hasStackTraceContaining("Failed to obtain R2DBC Connection")
        assertThat(failure).hasStackTraceContaining(":$unreachable")
        assertThat(failure.stackTraceToString())
            .doesNotContain(RestateEndpointLifecycle::class.java.name)
            .doesNotContain("Could not register the Restate endpoint")
    }

    @Test
    fun `the app doesn't start on a fee_record table that lacks a column, and names it`() {
        // given: a database whose fee_record lacks columns the app writes (an older shape, which IF NOT EXISTS keeps)
        val outdated = PostgresTestEnvironment.databaseWith(OUTDATED_TABLE)
        val unreachable = ServerSocket(0).use { it.localPort }
        val app = SpringApplicationBuilder(FeeServiceApplication::class.java)

        // when
        val failure =
            catchThrowable {
                app
                    .run(
                        "--spring.config.location=classpath:/",
                        "--server.port=0",
                        "--spring.r2dbc.url=$outdated",
                        "--spring.r2dbc.username=${PostgresTestEnvironment.USERNAME}",
                        "--spring.r2dbc.password=${PostgresTestEnvironment.PASSWORD}",
                        "--restate.ingress-url=http://localhost:$unreachable",
                        "--restate.admin-url=http://localhost:$unreachable",
                        "--restate.endpoint-port=0",
                    ).close()
            }

        // then: schema.sql failed on the missing column, before the endpoint lifecycle ever started
        assertThat(failure).isNotNull()
        assertThat(failure).hasStackTraceContaining("r2dbcScriptDatabaseInitializer")
        assertThat(failure).hasStackTraceContaining("column \"asset_type\" does not exist")
        assertThat(failure.stackTraceToString()).doesNotContain(RestateEndpointLifecycle::class.java.name)
    }

    private companion object {
        // every column the index needs, but not the request's asset_type, state and created_at
        const val OUTDATED_TABLE = """
            CREATE TABLE fee_record (
                transaction_id TEXT PRIMARY KEY, status TEXT NOT NULL, claim_token TEXT NOT NULL,
                claimed_at TIMESTAMPTZ NOT NULL, amount NUMERIC, asset TEXT, type TEXT, fee NUMERIC, rate NUMERIC,
                description TEXT, charged_at TIMESTAMPTZ
            )
        """
    }
}
