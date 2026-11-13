package com.example.fee.config

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

/**
 * The lookup of charged fees in `FeeService` (`ChargedFees`), from `fee-cache.*`: the local cache, and how long a cache
 * miss waits for Postgres. The cache holds only charged responses, which never change, so its size and expiry bound its
 * memory and nothing else. The defaults live only in the packaged `application.yml`; a missing or invalid value fails
 * startup with a message naming the key.
 */
@ConfigurationProperties(FeeCacheProperties.PREFIX)
class FeeCacheProperties(
    maximumSize: Long?,
    expireAfterWrite: Duration?,
    lookupTimeout: Duration?,
) {
    /** How many charged fees the cache holds at most; beyond that it evicts the ones least likely to be asked for. */
    val maximumSize: Long =
        requireNotNull(maximumSize) { "$PREFIX.maximum-size is missing; set it in application.yml" }
            .also { require(it > 0) { "$PREFIX.maximum-size must be positive, was $it" } }

    /** How long a charged fee stays in the cache after it was put there. */
    val expireAfterWrite: Duration =
        requireNotNull(expireAfterWrite) { "$PREFIX.expire-after-write is missing; set it in application.yml" }
            .also { require(it.isPositive) { "$PREFIX.expire-after-write must be positive, was $it" } }

    /** How long a cache miss waits for Postgres before it leaves the answer to Restate. */
    val lookupTimeout: Duration =
        requireNotNull(lookupTimeout) { "$PREFIX.lookup-timeout is missing; set it in application.yml" }
            .also { require(it.isPositive) { "$PREFIX.lookup-timeout must be positive, was $it" } }

    companion object {
        const val PREFIX = "fee-cache"
    }
}
