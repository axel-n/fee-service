package com.example.fee.wallet

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Duration

class FakeWalletPropertiesTest {

    @Test
    fun `keeps a valid delay`() {
        // given
        val delay = Duration.ofSeconds(10)

        // when
        val properties = FakeWalletProperties(delay)

        // then
        assertThat(properties.delay).isEqualTo(Duration.ofSeconds(10))
    }

    @Test
    fun `accepts a delay of 0`() {
        // given
        val delay = Duration.ZERO

        // when
        val properties = FakeWalletProperties(delay)

        // then
        assertThat(properties.delay).isZero()
    }

    @Test
    fun `rejects a negative delay with a readable message`() {
        // given
        val delay = Duration.ofSeconds(-1)

        // when / then
        assertThatThrownBy { FakeWalletProperties(delay) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("wallet.fake.delay must not be negative, was PT-1S")
    }

    @Test
    fun `rejects a missing delay with a readable message`() {
        // given
        val delay: Duration? = null

        // when / then
        assertThatThrownBy { FakeWalletProperties(delay) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageStartingWith("wallet.fake.delay is missing")
    }
}
