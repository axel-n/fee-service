package com.example.fee.config

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.boot.env.YamlPropertySourceLoader
import org.springframework.core.env.AbstractEnvironment
import org.springframework.core.env.ConfigurableEnvironment
import org.springframework.core.env.StandardEnvironment
import org.springframework.core.env.SystemEnvironmentPropertySource
import org.springframework.core.io.ClassPathResource
import java.time.Duration

/**
 * [FeeCacheProperties]' checks, and `fee-cache.*` bound the way Spring does at startup, without a Spring context. The
 * environment variables are simulated, so the real ones of the machine can't change the result.
 */
class FeeCachePropertiesTest {
    private fun environment(variables: Map<String, Any> = emptyMap()): ConfigurableEnvironment {
        val environment = object : AbstractEnvironment() {}
        environment.propertySources.addLast(
            SystemEnvironmentPropertySource(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, variables),
        )
        YamlPropertySourceLoader()
            .load("application.yml", ClassPathResource("application.yml"))
            .forEach { environment.propertySources.addLast(it) }
        return environment
    }

    private fun bind(environment: ConfigurableEnvironment): FeeCacheProperties =
        Binder.get(environment).bindOrCreate(FeeCacheProperties.PREFIX, FeeCacheProperties::class.java)

    @Test
    fun `packaged defaults hold up to 1000 charged fees for a day, and wait 1 s for Postgres on a miss`() {
        // given
        val environment = environment()

        // when
        val properties = bind(environment)

        // then
        assertThat(properties.maximumSize).isEqualTo(1_000L)
        assertThat(properties.expireAfterWrite).isEqualTo(Duration.ofDays(1))
        assertThat(properties.lookupTimeout).isEqualTo(Duration.ofSeconds(1))
    }

    @Test
    fun `FEE_CACHE_ variables override the packaged defaults`() {
        // given
        val variables =
            mapOf("FEE_CACHE_MAXIMUM_SIZE" to "50", "FEE_CACHE_EXPIRE_AFTER_WRITE" to "2h", "FEE_CACHE_LOOKUP_TIMEOUT" to "300ms")
        val environment = environment(variables)

        // when
        val properties = bind(environment)

        // then
        assertThat(properties.maximumSize).isEqualTo(50L)
        assertThat(properties.expireAfterWrite).isEqualTo(Duration.ofHours(2))
        assertThat(properties.lookupTimeout).isEqualTo(Duration.ofMillis(300))
    }

    @ParameterizedTest(name = "maximum-size {0}")
    @CsvSource("0", "-1")
    fun `rejects a maximum size that is not positive, naming the key`(maximumSize: Long) {
        // when / then
        assertThatThrownBy { FeeCacheProperties(maximumSize, Duration.ofDays(1), Duration.ofSeconds(1)) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("fee-cache.maximum-size must be positive, was $maximumSize")
    }

    @ParameterizedTest(name = "expire-after-write {0}")
    @CsvSource("PT0S", "-PT1S")
    fun `rejects an expiry that is not positive, naming the key`(expireAfterWrite: Duration) {
        // when / then
        assertThatThrownBy { FeeCacheProperties(1_000, expireAfterWrite, Duration.ofSeconds(1)) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("fee-cache.expire-after-write must be positive, was $expireAfterWrite")
    }

    @ParameterizedTest(name = "lookup-timeout {0}")
    @CsvSource("PT0S", "-PT1S")
    fun `rejects a lookup timeout that is not positive, naming the key`(lookupTimeout: Duration) {
        // when / then
        assertThatThrownBy { FeeCacheProperties(1_000, Duration.ofDays(1), lookupTimeout) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("fee-cache.lookup-timeout must be positive, was $lookupTimeout")
    }

    @Test
    fun `rejects a missing value, naming the key`() {
        // when / then
        assertThatThrownBy { FeeCacheProperties(null, Duration.ofDays(1), Duration.ofSeconds(1)) }
            .hasMessage("fee-cache.maximum-size is missing; set it in application.yml")
        assertThatThrownBy { FeeCacheProperties(1_000, null, Duration.ofSeconds(1)) }
            .hasMessage("fee-cache.expire-after-write is missing; set it in application.yml")
        assertThatThrownBy { FeeCacheProperties(1_000, Duration.ofDays(1), null) }
            .hasMessage("fee-cache.lookup-timeout is missing; set it in application.yml")
    }
}
