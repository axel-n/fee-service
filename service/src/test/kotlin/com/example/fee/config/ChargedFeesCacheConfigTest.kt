package com.example.fee.config

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Duration

class ChargedFeesCacheConfigTest {
    @Test
    fun `builds the cache with the configured maximum size and expiry after write`() {
        // given
        val properties =
            FeeCacheProperties(maximumSize = 1_000, expireAfterWrite = Duration.ofDays(1), lookupTimeout = Duration.ofSeconds(1))

        // when
        val cache = ChargedFeesCacheConfig().chargedFeesCache(properties)

        // then
        val policy = cache.policy()
        assertThat(policy.eviction().map { it.maximum }).hasValue(1_000L)
        assertThat(policy.expireAfterWrite().map { it.expiresAfter }).hasValue(Duration.ofDays(1))
        assertThat(policy.expireAfterAccess()).isEmpty()
    }
}
