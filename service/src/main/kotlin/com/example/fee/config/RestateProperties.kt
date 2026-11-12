package com.example.fee.config

import org.springframework.boot.context.properties.ConfigurationProperties
import java.net.URI
import java.time.Duration

/**
 * How the app reaches Restate and how Restate reaches the app, from `restate.*`. The defaults live only in the packaged
 * `application.yml`; a missing or invalid value fails startup with a message naming the key, like the fee properties.
 * Registering the endpoint with Restate is a setup step outside the app, so there are no admin API settings.
 */
@ConfigurationProperties(RestateProperties.PREFIX)
class RestateProperties(
    ingressUrl: String?,
    endpointPort: Int?,
    attachTimeout: Duration?,
) {

    /** Restate ingress, used by `FeeService` through the generated ingress clients. */
    val ingressUrl: String = httpUrl("ingress-url", ingressUrl)

    /** Port the endpoint listens on, where Restate calls the workflow; 0 lets the OS pick one (tests). */
    val endpointPort: Int = present("endpoint-port", endpointPort)
        .also { require(it in 0..MAX_PORT) { "$PREFIX.endpoint-port must be between 0 and $MAX_PORT, was $it" } }

    /** How long `POST /transaction/fee` waits for the workflow's result before it answers 202. */
    val attachTimeout: Duration = positive("attach-timeout", attachTimeout)

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
