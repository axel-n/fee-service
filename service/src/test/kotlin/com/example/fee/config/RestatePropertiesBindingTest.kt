package com.example.fee.config

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import org.springframework.boot.context.properties.bind.BindException
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.boot.env.YamlPropertySourceLoader
import org.springframework.core.env.AbstractEnvironment
import org.springframework.core.env.ConfigurableEnvironment
import org.springframework.core.env.StandardEnvironment
import org.springframework.core.env.SystemEnvironmentPropertySource
import org.springframework.core.io.ByteArrayResource
import org.springframework.core.io.ClassPathResource
import org.springframework.core.io.Resource
import java.time.Duration

/**
 * Binds `restate.*` to [RestateProperties] the way Spring does at startup (`bindOrCreate`, constructor binding), without
 * a Spring context. The environment variables are simulated, so the real ones of the machine (the dev container sets
 * `RESTATE_*`) can't change the result.
 */
class RestatePropertiesBindingTest {
    private fun environment(
        variables: Map<String, Any> = emptyMap(),
        resource: Resource = ClassPathResource("application.yml"),
    ): ConfigurableEnvironment {
        val environment = object : AbstractEnvironment() {}
        environment.propertySources.addLast(
            SystemEnvironmentPropertySource(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, variables),
        )
        YamlPropertySourceLoader().load("application.yml", resource).forEach { environment.propertySources.addLast(it) }
        return environment
    }

    companion object {
        private fun override(
            variable: String,
            value: String,
            setting: (RestateProperties) -> Any,
            expected: Any,
        ) = Arguments.of(variable, value, setting, expected)

        @JvmStatic
        fun overrides(): List<Arguments> =
            listOf(
                override("RESTATE_INGRESS_URL", "http://restate:8080", { it.ingressUrl }, "http://restate:8080"),
                override("RESTATE_ADMIN_URL", "http://restate:9070", { it.adminUrl }, "http://restate:9070"),
                override("RESTATE_ENDPOINT_PORT", "19080", { it.endpointPort }, 19080),
                override("RESTATE_ADVERTISED_HOST", "dev", { it.advertisedHost }, "dev"),
                override("RESTATE_ATTACH_TIMEOUT", "45s", { it.attachTimeout }, Duration.ofSeconds(45)),
            )
    }

    private fun bind(environment: ConfigurableEnvironment): RestateProperties =
        Binder.get(environment).bindOrCreate(RestateProperties.PREFIX, RestateProperties::class.java)

    @Test
    fun `packaged restate defaults bind to their typed values`() {
        // given
        val environment = environment()

        // when
        val properties = bind(environment)

        // then
        assertThat(properties.ingressUrl).isEqualTo("http://localhost:8080")
        assertThat(properties.adminUrl).isEqualTo("http://localhost:9070")
        assertThat(properties.endpointPort).isEqualTo(9080)
        assertThat(properties.advertisedHost).isEqualTo("localhost")
        assertThat(properties.attachTimeout).isEqualTo(Duration.ofSeconds(30))
    }

    @ParameterizedTest(name = "{0}={1} overrides its placeholder")
    @MethodSource("overrides")
    fun `a RESTATE_ variable overrides its placeholder in the bound properties`(
        variable: String,
        override: String,
        setting: (RestateProperties) -> Any,
        expected: Any,
    ) {
        // given
        val environment = environment(mapOf(variable to override))

        // when
        val properties = bind(environment)

        // then
        assertThat(setting(properties)).isEqualTo(expected)
    }

    @Test
    fun `a variable overrides only its own setting`() {
        // given
        val environment = environment(mapOf("RESTATE_INGRESS_URL" to "http://restate:8080"))

        // when
        val properties = bind(environment)

        // then
        assertThat(properties.ingressUrl).isEqualTo("http://restate:8080")
        assertThat(properties.adminUrl).isEqualTo("http://localhost:9070")
        assertThat(properties.endpointPort).isEqualTo(9080)
        assertThat(properties.advertisedHost).isEqualTo("localhost")
        assertThat(properties.attachTimeout).isEqualTo(Duration.ofSeconds(30))
    }

    @Test
    fun `a missing restate section fails binding with a message naming the key`() {
        // given
        val environment = environment(resource = ByteArrayResource("fee: {}".toByteArray()))

        // when / then
        assertThatThrownBy { bind(environment) }
            .isInstanceOf(BindException::class.java)
            .rootCause()
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageStartingWith("restate.ingress-url is missing")
    }

    @Test
    fun `a missing restate setting fails binding with a message naming the key`() {
        // given
        val yaml =
            """
            restate:
              ingress-url: http://localhost:8080
              admin-url: http://localhost:9070
              endpoint-port: 9080
              advertised-host: localhost
            """.trimIndent()
        val environment = environment(resource = ByteArrayResource(yaml.toByteArray()))

        // when / then
        assertThatThrownBy { bind(environment) }
            .isInstanceOf(BindException::class.java)
            .rootCause()
            .hasMessageStartingWith("restate.attach-timeout is missing")
    }

    @Test
    fun `a variable that is not a number fails binding naming the key`() {
        // given
        val environment = environment(mapOf("RESTATE_ENDPOINT_PORT" to "eighty"))

        // when / then
        assertThatThrownBy { bind(environment) }
            .isInstanceOf(BindException::class.java)
            .hasMessageContaining("restate.endpoint-port")
    }

    @Test
    fun `an invalid variable fails binding with a readable message`() {
        // given
        val environment = environment(mapOf("RESTATE_INGRESS_URL" to "restate:8080"))

        // when / then
        assertThatThrownBy { bind(environment) }
            .isInstanceOf(BindException::class.java)
            .rootCause()
            .hasMessage("restate.ingress-url must be an absolute http or https URL, was 'restate:8080'")
    }
}
