package com.example.fee.it

import com.example.fee.restate.RestateEndpointLifecycle
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired

/**
 * The app registers its endpoint with Restate at startup, at the advertised host and the port it listens on, and removes
 * it when it stops with nothing unfinished. Keeping it while a workflow is in flight is `EndpointRestartIT`'s.
 */
class EndpointRegistrationIT
    @Autowired
    constructor(
        private val endpoint: RestateEndpointLifecycle,
    ) : RestateIntegrationTest() {
        private val admin = RestateTestEnvironment.admin

        @AfterEach
        fun restoreSharedEndpoint() {
            endpoint.start()
        }

        @Test
        fun `the started app is registered at its advertised URL with the workflow and the fee record`() {
            // given: the shared app has started

            // when
            val deployment = registered()

            // then
            assertThat(deployment).describedAs("deployments %s", admin.deployments()).isNotNull()
            assertThat(checkNotNull(deployment).services.keys).contains("FeeWorkflow", "TransactionFeeRecord")
        }

        @Test
        fun `a stop with nothing unfinished removes the deployment, and a start registers it again`() {
            // given: nothing unfinished in Restate, so nothing needs the deployment
            awaitUntil("no invocation is unfinished") { unfinishedInvocations() == 0L }

            // when
            endpoint.stop()

            // then
            assertThat(registered()).describedAs("deployments after the stop %s", admin.deployments()).isNull()

            // when
            endpoint.start()

            // then
            assertThat(registered()).describedAs("deployments after the start %s", admin.deployments()).isNotNull()
        }

        private fun registered(): RestateAdminApi.Deployment? =
            admin.deployments().singleOrNull { it.uri?.removeSuffix("/") == RestateTestEnvironment.advertisedUrl }

        private fun unfinishedInvocations(): Long =
            admin
                .query("SELECT count(*) AS unfinished FROM sys_invocation WHERE status <> 'completed'")
                .single()["unfinished"]
                .asLong()
    }
