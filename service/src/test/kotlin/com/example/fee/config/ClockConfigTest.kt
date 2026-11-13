package com.example.fee.config

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.ZoneOffset

class ClockConfigTest {
    @Test
    fun `clock bean uses UTC whatever the host zone is`() {
        // given
        val config = ClockConfig()

        // when
        val clock = config.clock()

        // then
        assertThat(clock.zone).isEqualTo(ZoneOffset.UTC)
    }

    @Test
    fun `clock bean follows the system time`() {
        // given
        val clock = ClockConfig().clock()
        val before = Instant.now()

        // when
        val now = clock.instant()

        // then
        assertThat(now).isBetween(before, Instant.now())
    }
}
