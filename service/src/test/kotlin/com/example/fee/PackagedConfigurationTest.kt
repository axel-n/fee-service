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
 * Checks the packaged `application.yml` without a Spring context. The environment variables are simulated, so the
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
        "restate.endpoint-port, 9080",
        "restate.attach-timeout, 10s",
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
        "restate.endpoint-port, RESTATE_ENDPOINT_PORT, 19080",
        "restate.attach-timeout, RESTATE_ATTACH_TIMEOUT, 30s",
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

        // then
        assertThat(endpointPort).isEqualTo(9080)
        assertThat(attachTimeout).isEqualTo(Duration.ofSeconds(10))
    }

    @Test
    fun `the app needs only the ingress url, the endpoint port and the attach timeout to work with Restate`() {
        // given
        val yaml = packagedYaml()

        // when
        val restateKeys = yaml.propertyNames.filter { it.startsWith("restate.") }

        // then: registration is a setup step outside the app, so no admin API settings
        assertThat(restateKeys).containsExactlyInAnyOrder("restate.ingress-url", "restate.endpoint-port", "restate.attach-timeout")
        assertThat(yaml.propertyNames.filter { it.startsWith("spring.http.client.") }).isEmpty()
    }
}
