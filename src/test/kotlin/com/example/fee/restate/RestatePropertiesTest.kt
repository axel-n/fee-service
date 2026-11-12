package com.example.fee.restate

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.time.Duration

class RestatePropertiesTest {

    private fun registration(
        enabled: Boolean? = true,
        maxAttempts: Int? = 10,
        backoff: Duration? = Duration.ofSeconds(2),
    ) = RestateProperties.Registration(enabled, maxAttempts, backoff)

    private fun properties(
        ingressUrl: String? = "http://localhost:8080",
        adminUrl: String? = "http://localhost:9070",
        endpointPort: Int? = 9080,
        advertisedUrl: String? = "http://host.docker.internal:9080",
        attachTimeout: Duration? = Duration.ofSeconds(10),
        registration: RestateProperties.Registration? = registration(),
    ) = RestateProperties(ingressUrl, adminUrl, endpointPort, advertisedUrl, attachTimeout, registration)

    @Test
    fun `keeps valid settings`() {
        // given / when
        val properties = properties(
            ingressUrl = "https://restate.example:8080",
            registration = registration(enabled = false, maxAttempts = 1, backoff = Duration.ofMillis(500)),
        )

        // then
        assertThat(properties.ingressUrl).isEqualTo("https://restate.example:8080")
        assertThat(properties.adminUrl).isEqualTo("http://localhost:9070")
        assertThat(properties.endpointPort).isEqualTo(9080)
        assertThat(properties.advertisedUrl).isEqualTo("http://host.docker.internal:9080")
        assertThat(properties.attachTimeout).isEqualTo(Duration.ofSeconds(10))
        assertThat(properties.registration.enabled).isFalse()
        assertThat(properties.registration.maxAttempts).isEqualTo(1)
        assertThat(properties.registration.backoff).isEqualTo(Duration.ofMillis(500))
    }

    @Test
    fun `rejects a missing ingress url with a readable message`() {
        // given / when / then
        assertThatThrownBy { properties(ingressUrl = null) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("restate.ingress-url is missing; set it in application.yml")
    }

    @ParameterizedTest(name = "rejects ''{0}''")
    @ValueSource(strings = ["restate:8080", "localhost:8080", "ftp://restate:8080", "http://", "not a url", ""])
    fun `rejects an admin url that is not an absolute http url with a readable message`(url: String) {
        // given / when / then
        assertThatThrownBy { properties(adminUrl = url) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("restate.admin-url must be an absolute http or https URL, was '$url'")
    }

    @Test
    fun `rejects an advertised url without a host with a readable message`() {
        // given / when / then
        assertThatThrownBy { properties(advertisedUrl = "http:/dev:9080") }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("restate.advertised-url must be an absolute http or https URL, was 'http:/dev:9080'")
    }

    @Test
    fun `rejects a missing endpoint port with a readable message`() {
        // given / when / then
        assertThatThrownBy { properties(endpointPort = null) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("restate.endpoint-port is missing; set it in application.yml")
    }

    @ParameterizedTest(name = "rejects port {0}")
    @ValueSource(ints = [-1, 65536])
    fun `rejects an endpoint port out of range with a readable message`(port: Int) {
        // given / when / then
        assertThatThrownBy { properties(endpointPort = port) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("restate.endpoint-port must be between 0 and 65535, was $port")
    }

    @Test
    fun `accepts endpoint port 0 so the OS picks a free port`() {
        // given / when
        val properties = properties(endpointPort = 0)

        // then
        assertThat(properties.endpointPort).isZero()
    }

    @Test
    fun `rejects a zero attach timeout with a readable message`() {
        // given / when / then
        assertThatThrownBy { properties(attachTimeout = Duration.ZERO) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("restate.attach-timeout must be positive, was PT0S")
    }

    @Test
    fun `rejects a missing registration section with a readable message`() {
        // given / when / then
        assertThatThrownBy { properties(registration = null) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("restate.registration is missing; set it in application.yml")
    }

    @Test
    fun `rejects a missing registration switch with a readable message`() {
        // given / when / then
        assertThatThrownBy { registration(enabled = null) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("restate.registration.enabled is missing; set it in application.yml")
    }

    @Test
    fun `rejects fewer than one registration attempt with a readable message`() {
        // given / when / then
        assertThatThrownBy { registration(maxAttempts = 0) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("restate.registration.max-attempts must be at least 1, was 0")
    }

    @Test
    fun `rejects a negative registration backoff with a readable message`() {
        // given / when / then
        assertThatThrownBy { registration(backoff = Duration.ofSeconds(-1)) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("restate.registration.backoff must be positive, was PT-1S")
    }
}
