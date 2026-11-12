package com.example.fee

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.boot.env.OriginTrackedMapPropertySource
import org.springframework.boot.env.YamlPropertySourceLoader
import org.springframework.core.env.AbstractEnvironment
import org.springframework.core.env.ConfigurableEnvironment
import org.springframework.core.env.StandardEnvironment
import org.springframework.core.env.SystemEnvironmentPropertySource
import org.springframework.core.io.ClassPathResource
import java.time.Duration

/**
 * Checks the packaged `application.yml` (D22) without a Spring context. The environment variables are simulated, so the
 * real ones of the machine (the dev container sets `RESTATE_*`) can't change the result.
 */
class PackagedConfigurationTest {

    private fun packagedYaml(): OriginTrackedMapPropertySource =
        YamlPropertySourceLoader().load("application.yml", ClassPathResource("application.yml"))
            .single() as OriginTrackedMapPropertySource

    private fun environment(variables: Map<String, Any> = emptyMap()): ConfigurableEnvironment {
        val environment = object : AbstractEnvironment() {}
        environment.propertySources.addLast(
            SystemEnvironmentPropertySource(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, variables),
        )
        environment.propertySources.addLast(packagedYaml())
        return environment
    }

    @Test
    fun `serves the API on port 8081 by default`() {
        // given
        val environment = environment()

        // when
        val port = Binder.get(environment).bind("server.port", Int::class.java).get()

        // then
        assertThat(port).isEqualTo(8081)
    }

    @Test
    fun `SERVER_PORT overrides the API port`() {
        // given
        val environment = environment(mapOf("SERVER_PORT" to "9999"))

        // when
        val port = Binder.get(environment).bind("server.port", Int::class.java).get()

        // then
        assertThat(port).isEqualTo(9999)
    }

    @ParameterizedTest(name = "{0} defaults to {1}")
    @CsvSource(
        "restate.ingress-url, http://localhost:8080",
        "restate.admin-url, http://localhost:9070",
        "restate.endpoint-port, 9080",
        "restate.advertised-url, http://host.docker.internal:9080",
        "restate.attach-timeout, 10s",
        "restate.registration.enabled, true",
        "restate.registration.max-attempts, 10",
        "restate.registration.backoff, 2s",
    )
    fun `restate setting has its packaged default when no variable is set`(key: String, expected: String) {
        // given
        val environment = environment()

        // when
        val value = environment.getProperty(key)

        // then
        assertThat(value).isEqualTo(expected)
    }

    @ParameterizedTest(name = "{1} overrides {0}")
    @CsvSource(
        "restate.ingress-url, RESTATE_INGRESS_URL, http://restate:8080",
        "restate.admin-url, RESTATE_ADMIN_URL, http://restate:9070",
        "restate.endpoint-port, RESTATE_ENDPOINT_PORT, 19080",
        "restate.advertised-url, RESTATE_ADVERTISED_URL, http://dev:19080",
        "restate.attach-timeout, RESTATE_ATTACH_TIMEOUT, 30s",
        "restate.registration.enabled, RESTATE_REGISTRATION_ENABLED, false",
        "restate.registration.max-attempts, RESTATE_REGISTRATION_MAX_ATTEMPTS, 3",
        "restate.registration.backoff, RESTATE_REGISTRATION_BACKOFF, 500ms",
    )
    fun `restate setting is overridden by its readable environment variable`(
        key: String,
        variable: String,
        override: String,
    ) {
        // given
        val environment = environment(mapOf(variable to override))

        // when
        val value = environment.getProperty(key)

        // then
        assertThat(value).isEqualTo(override)
    }

    @Test
    fun `every restate setting goes through an explicit RESTATE_ placeholder`() {
        // given
        val yaml = packagedYaml()

        // when
        val restateKeys = yaml.propertyNames.filter { it.startsWith("restate.") }

        // then
        assertThat(restateKeys).isNotEmpty
        assertThat(restateKeys).allSatisfy { key ->
            assertThat(yaml.getProperty(key).toString()).describedAs(key).matches("""\$\{RESTATE_[A-Z_]+:.+}""")
        }
    }

    @Test
    fun `restate defaults bind to their typed values`() {
        // given
        val binder = Binder.get(environment())

        // when
        val endpointPort = binder.bind("restate.endpoint-port", Int::class.java).get()
        val attachTimeout = binder.bind("restate.attach-timeout", Duration::class.java).get()
        val enabled = binder.bind("restate.registration.enabled", Boolean::class.java).get()
        val maxAttempts = binder.bind("restate.registration.max-attempts", Int::class.java).get()
        val backoff = binder.bind("restate.registration.backoff", Duration::class.java).get()

        // then
        assertThat(endpointPort).isEqualTo(9080)
        assertThat(attachTimeout).isEqualTo(Duration.ofSeconds(10))
        assertThat(enabled).isTrue()
        assertThat(maxAttempts).isEqualTo(10)
        assertThat(backoff).isEqualTo(Duration.ofSeconds(2))
    }

    @Test
    fun `the admin API client gives up on a connect after 5s and on an answer after 30s`() {
        // given
        val binder = Binder.get(environment())

        // when
        val connectTimeout = binder.bind("spring.http.client.connect-timeout", Duration::class.java).get()
        val readTimeout = binder.bind("spring.http.client.read-timeout", Duration::class.java).get()

        // then
        assertThat(connectTimeout).isEqualTo(Duration.ofSeconds(5))
        assertThat(readTimeout).isEqualTo(Duration.ofSeconds(30))
    }

    @Test
    fun `fake wallet waits 0s by default`() {
        // given
        val binder = Binder.get(environment())

        // when
        val delay = binder.bind("wallet.fake.delay", Duration::class.java).get()

        // then
        assertThat(delay).isZero()
    }

    @Test
    fun `WALLET_FAKE_DELAY overrides the fake wallet delay`() {
        // given
        val binder = Binder.get(environment(mapOf("WALLET_FAKE_DELAY" to "15s")))

        // when
        val delay = binder.bind("wallet.fake.delay", Duration::class.java).get()

        // then
        assertThat(delay).isEqualTo(Duration.ofSeconds(15))
    }
}
