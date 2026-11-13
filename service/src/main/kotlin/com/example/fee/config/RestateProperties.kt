package com.example.fee.config

import org.springframework.boot.context.properties.ConfigurationProperties
import java.net.URI
import java.time.Duration

/**
 * How the app reaches Restate and how Restate reaches the app, from `restate.*`. The defaults live only in the packaged
 * `application.yml`; a missing or invalid value fails startup with a message naming the key, like the fee properties.
 */
@ConfigurationProperties(RestateProperties.PREFIX)
class RestateProperties(
    ingressUrl: String?,
    adminUrl: String?,
    endpointPort: Int?,
    advertisedHost: String?,
    attachTimeout: Duration?,
) {
    /** Restate ingress, used by `FeeService` through the generated ingress clients. */
    val ingressUrl: String = httpUrl("ingress-url", ingressUrl)

    /** Restate's admin API, where `RestateEndpointLifecycle` registers the endpoint at startup and removes it at shutdown. */
    val adminUrl: String = httpUrl("admin-url", adminUrl)

    /** Port the endpoint listens on, where Restate calls the workflow; 0 lets the OS pick one (tests). */
    val endpointPort: Int =
        present("endpoint-port", endpointPort)
            .also { require(it in 0..MAX_PORT) { "$PREFIX.endpoint-port must be between 0 and $MAX_PORT, was $it" } }

    /**
     * The host Restate reaches the endpoint at. The app can't find it out by itself: Restate in a container reaches an app
     * on the same machine as `host.docker.internal`, say. The endpoint is registered as `http://<this>:<the port it listens on>`.
     */
    val advertisedHost: String = host("advertised-host", advertisedHost)

    /** How long `POST /transaction/fee` waits for the workflow's result before it answers 202. */
    val attachTimeout: Duration = positive("attach-timeout", attachTimeout)

    companion object {
        const val PREFIX = "restate"
        private const val MAX_PORT = 65_535

        private fun <T : Any> present(
            key: String,
            value: T?,
        ): T = requireNotNull(value) { "$PREFIX.$key is missing; set it in application.yml" }

        private fun positive(
            key: String,
            value: Duration?,
        ): Duration =
            present(key, value).also {
                require(it.isPositive) { "$PREFIX.$key must be positive, was $it" }
            }

        private fun host(
            key: String,
            value: String?,
        ): String {
            val host = present(key, value)
            val uri = runCatching { URI("http://$host:1") }.getOrNull()
            require(uri != null && uri.host == host && uri.port == 1) {
                "$PREFIX.$key must be a host name or an IP address, was '$host'"
            }
            return host
        }

        private fun httpUrl(
            key: String,
            value: String?,
        ): String {
            val url = present(key, value)
            val uri = runCatching { URI(url) }.getOrNull()
            require(uri != null && uri.scheme in setOf("http", "https") && !uri.host.isNullOrEmpty()) {
                "$PREFIX.$key must be an absolute http or https URL, was '$url'"
            }
            return url
        }
    }
}
