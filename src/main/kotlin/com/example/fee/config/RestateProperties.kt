package com.example.fee.config

import org.springframework.boot.context.properties.ConfigurationProperties
import java.net.URI
import java.time.Duration

/**
 * How the app reaches Restate and how Restate reaches the app, from `restate.*`. The defaults live only in the packaged
 * `application.yml` (D22, D36); a missing or invalid value fails startup with a message naming the key (like D41).
 */
@ConfigurationProperties(RestateProperties.PREFIX)
class RestateProperties(
    ingressUrl: String?,
    adminUrl: String?,
    endpointPort: Int?,
    advertisedUrl: String?,
    attachTimeout: Duration?,
    registration: Registration?,
) {

    /** Restate ingress, used by `FeeService` through the generated ingress clients. */
    val ingressUrl: String = httpUrl("ingress-url", ingressUrl)

    /** Restate admin API, used by the registrar. */
    val adminUrl: String = httpUrl("admin-url", adminUrl)

    /** Port the endpoint listens on; 0 lets the OS pick one, which only makes sense when nothing has to advertise it. */
    val endpointPort: Int = present("endpoint-port", endpointPort)
        .also { require(it in 0..MAX_PORT) { "$PREFIX.endpoint-port must be between 0 and $MAX_PORT, was $it" } }

    /** The endpoint's URL as Restate reaches it, sent at registration. */
    val advertisedUrl: String = httpUrl("advertised-url", advertisedUrl)

    /** How long `POST /transaction/fee` waits for the workflow's result before it answers 202. */
    val attachTimeout: Duration = positive("attach-timeout", attachTimeout)

    /** Self-registration on startup (D15). */
    val registration: Registration = present("registration", registration)

    /** `restate.registration.*`: the switch, the attempts before the WARN, and the wait between attempts (D15, D36). */
    class Registration(
        enabled: Boolean?,
        maxAttempts: Int?,
        backoff: Duration?,
    ) {
        val enabled: Boolean = present("registration.enabled", enabled)

        val maxAttempts: Int = present("registration.max-attempts", maxAttempts)
            .also { require(it >= 1) { "$PREFIX.registration.max-attempts must be at least 1, was $it" } }

        val backoff: Duration = positive("registration.backoff", backoff)
    }

    companion object {
        const val PREFIX = "restate"
        private const val MAX_PORT = 65535

        private fun <T : Any> present(key: String, value: T?): T =
            requireNotNull(value) { "$PREFIX.$key is missing; set it in application.yml" }

        private fun positive(key: String, value: Duration?): Duration =
            present(key, value).also {
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
    }
}
