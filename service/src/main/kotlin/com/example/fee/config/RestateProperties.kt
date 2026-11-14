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
    endpointUrl: String?,
    attachTimeout: Duration?,
) {
    /** Restate ingress, used by `FeeService` through the Kotlin SDK's ingress client. */
    val ingressUrl: String = httpUrl("ingress-url", ingressUrl)

    /** Restate's admin API, where `RestateEndpointLifecycle` registers the endpoint at startup and removes it at shutdown. */
    val adminUrl: String = httpUrl("admin-url", adminUrl)

    /**
     * The URL Restate calls the workflow at, `http://<host>:<port>`: the endpoint is registered as it is and listens on
     * its port. The app can't find the host out by itself: Restate in a container reaches an app on the same machine as
     * `host.docker.internal`, say. The port is a fixed one, never the OS's pick: after a restart the endpoint must be back
     * at the URL Restate knows, or the invocations pinned to its deployment never finish.
     */
    val endpointUrl: String = hostPortUrl("endpoint-url", endpointUrl)

    /** The port of [endpointUrl], which the endpoint listens on. */
    val endpointPort: Int = URI(this.endpointUrl).port

    /**
     * How long `POST /transaction/fee` waits for the workflow's result. Then it asks Restate once more, and answers 504
     * only if Restate says the run hasn't finished (470).
     */
    val attachTimeout: Duration = positive("attach-timeout", attachTimeout)

    companion object {
        const val PREFIX = "restate"
        private const val MAX_PORT = 65_535

        private fun <T : Any> present(key: String, value: T?): T =
            requireNotNull(value) { "$PREFIX.$key is missing; set it in application.yml" }

        private fun positive(key: String, value: Duration?): Duration = present(key, value).also {
            require(it.isPositive) { "$PREFIX.$key must be positive, was $it" }
        }

        private fun httpUrl(key: String, value: String?): String {
            val url = present(key, value)
            val uri = runCatching { URI(url) }.getOrNull()
            require(uri != null && uri.scheme in setOf("http", "https") && !uri.host.isNullOrEmpty()) {
                "$PREFIX.$key must be an absolute http or https URL, was '$url'"
            }
            return url
        }

        // Plain http (the endpoint serves no TLS) at its root, and an explicit port to listen on.
        private fun hostPortUrl(key: String, value: String?): String {
            val url = present(key, value)
            val uri = runCatching { URI(url) }.getOrNull()
            require(
                uri != null &&
                    uri.scheme == "http" &&
                    !uri.host.isNullOrEmpty() &&
                    uri.port in 1..MAX_PORT &&
                    uri.rawUserInfo == null &&
                    uri.rawPath.orEmpty() in setOf("", "/") &&
                    uri.rawQuery == null &&
                    uri.rawFragment == null,
            ) { "$PREFIX.$key must be http://<host>:<port>, with a port between 1 and $MAX_PORT, was '$url'" }
            return url
        }
    }
}
