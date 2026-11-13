package com.example.fee.config

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.time.Duration

class RestatePropertiesTest {
    private fun properties(
        ingressUrl: String? = "http://localhost:8080",
        adminUrl: String? = "http://localhost:9070",
        endpointPort: Int? = 9_080,
        advertisedHost: String? = "localhost",
        attachTimeout: Duration? = Duration.ofSeconds(10),
    ) = RestateProperties(ingressUrl, adminUrl, endpointPort, advertisedHost, attachTimeout)

    @Test
    fun `keeps valid settings`() {
        // given / when
        val properties =
            properties(
                ingressUrl = "https://restate.example:8080",
                adminUrl = "https://restate.example:9070",
                advertisedHost = "host.docker.internal",
            )

        // then
        assertThat(properties.ingressUrl).isEqualTo("https://restate.example:8080")
        assertThat(properties.adminUrl).isEqualTo("https://restate.example:9070")
        assertThat(properties.endpointPort).isEqualTo(9_080)
        assertThat(properties.advertisedHost).isEqualTo("host.docker.internal")
        assertThat(properties.attachTimeout).isEqualTo(Duration.ofSeconds(10))
    }

    @Test
    fun `rejects a missing admin url with a readable message`() {
        // given / when / then
        assertThatThrownBy { properties(adminUrl = null) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("restate.admin-url is missing; set it in application.yml")
    }

    @ParameterizedTest(name = "rejects ''{0}''")
    @ValueSource(strings = ["restate:9070", "ftp://restate:9070", "not a url", ""])
    fun `rejects an admin url that is not an absolute http url with a readable message`(url: String) {
        // given / when / then
        assertThatThrownBy { properties(adminUrl = url) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("restate.admin-url must be an absolute http or https URL, was '$url'")
    }

    @ParameterizedTest(name = "accepts ''{0}''")
    @ValueSource(strings = ["localhost", "dev", "host.docker.internal", "10.89.0.4", "[::1]"])
    fun `accepts an advertised host that is a host name or an IP address`(host: String) {
        // given / when
        val properties = properties(advertisedHost = host)

        // then
        assertThat(properties.advertisedHost).isEqualTo(host)
    }

    @Test
    fun `rejects a missing advertised host with a readable message`() {
        // given / when / then
        assertThatThrownBy { properties(advertisedHost = null) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("restate.advertised-host is missing; set it in application.yml")
    }

    @ParameterizedTest(name = "rejects ''{0}''")
    @ValueSource(strings = ["", "http://dev", "dev:9080", "dev/path", "not a host"])
    fun `rejects an advertised host that is not a host name or an IP address with a readable message`(host: String) {
        // given / when / then
        assertThatThrownBy { properties(advertisedHost = host) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("restate.advertised-host must be a host name or an IP address, was '$host'")
    }

    @Test
    fun `rejects a missing ingress url with a readable message`() {
        // given / when / then
        assertThatThrownBy { properties(ingressUrl = null) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("restate.ingress-url is missing; set it in application.yml")
    }

    @ParameterizedTest(name = "rejects ''{0}''")
    @ValueSource(strings = ["restate:8080", "localhost:8080", "ftp://restate:8080", "http://", "http:/restate:8080", "not a url", ""])
    fun `rejects an ingress url that is not an absolute http url with a readable message`(url: String) {
        // given / when / then
        assertThatThrownBy { properties(ingressUrl = url) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("restate.ingress-url must be an absolute http or https URL, was '$url'")
    }

    @Test
    fun `rejects a missing endpoint port with a readable message`() {
        // given / when / then
        assertThatThrownBy { properties(endpointPort = null) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("restate.endpoint-port is missing; set it in application.yml")
    }

    @ParameterizedTest(name = "rejects port {0}")
    @ValueSource(ints = [-1, 65_536])
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
    fun `rejects a missing attach timeout with a readable message`() {
        // given / when / then
        assertThatThrownBy { properties(attachTimeout = null) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("restate.attach-timeout is missing; set it in application.yml")
    }

    @Test
    fun `rejects a zero attach timeout with a readable message`() {
        // given / when / then
        assertThatThrownBy { properties(attachTimeout = Duration.ZERO) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("restate.attach-timeout must be positive, was PT0S")
    }
}
