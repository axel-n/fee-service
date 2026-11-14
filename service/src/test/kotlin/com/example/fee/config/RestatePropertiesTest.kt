package com.example.fee.config

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource
import java.time.Duration

class RestatePropertiesTest {
    private fun properties(
        ingressUrl: String? = "http://localhost:8080",
        adminUrl: String? = "http://localhost:9070",
        endpointUrl: String? = "http://localhost:9080",
        attachTimeout: Duration? = Duration.ofSeconds(10),
    ) = RestateProperties(ingressUrl, adminUrl, endpointUrl, attachTimeout)

    @Test
    fun `keeps valid settings`() {
        // given / when
        val properties =
            properties(
                ingressUrl = "https://restate.example:8080",
                adminUrl = "https://restate.example:9070",
                endpointUrl = "http://host.docker.internal:9080",
            )

        // then
        assertThat(properties.ingressUrl).isEqualTo("https://restate.example:8080")
        assertThat(properties.adminUrl).isEqualTo("https://restate.example:9070")
        assertThat(properties.endpointUrl).isEqualTo("http://host.docker.internal:9080")
        assertThat(properties.endpointPort).isEqualTo(9_080)
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

    @ParameterizedTest(name = "accepts ''{0}'', listening on {1}")
    @CsvSource(
        "http://localhost:9080, 9080",
        "http://dev:9080, 9080",
        "http://host.docker.internal:19080, 19080",
        "http://10.89.0.4:1, 1",
        "http://[::1]:65535, 65535",
        "http://dev:9080/, 9080",
    )
    fun `accepts an endpoint url of a host and a port, and listens on its port`(url: String, port: Int) {
        // given / when
        val properties = properties(endpointUrl = url)

        // then
        assertThat(properties.endpointUrl).isEqualTo(url)
        assertThat(properties.endpointPort).isEqualTo(port)
    }

    @Test
    fun `rejects a missing endpoint url with a readable message`() {
        // given / when / then
        assertThatThrownBy { properties(endpointUrl = null) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("restate.endpoint-url is missing; set it in application.yml")
    }

    @ParameterizedTest(name = "rejects ''{0}''")
    @ValueSource(
        strings = [
            "", "not a url", "dev", "dev:9080", "https://dev:9080", "http://dev", "http://:9080", "http://dev:0",
            "http://dev:65536", "http://dev:9080/path", "http://dev:9080?query", "http://dev:9080#fragment",
            "http://user@dev:9080",
        ],
    )
    fun `rejects an endpoint url that is not http of a host and a port with a readable message`(url: String) {
        // given / when / then
        assertThatThrownBy { properties(endpointUrl = url) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("restate.endpoint-url must be http://<host>:<port>, with a port between 1 and 65535, was '$url'")
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
