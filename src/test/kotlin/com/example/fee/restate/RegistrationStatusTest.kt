package com.example.fee.restate

import com.example.fee.restate.RegistrationStatus.Snapshot
import com.example.fee.restate.RegistrationStatus.State
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class RegistrationStatusTest {

    @Test
    fun `starts pending without an error`() {
        // given
        val status = RegistrationStatus()

        // when
        val current = status.current()

        // then
        assertThat(current).isEqualTo(Snapshot(State.PENDING, null))
    }

    @Test
    fun `a failed attempt keeps the state pending and records the error`() {
        // given
        val status = RegistrationStatus()

        // when
        status.recordFailure("admin API unreachable")

        // then
        assertThat(status.state).isEqualTo(State.PENDING)
        assertThat(status.lastError).isEqualTo("admin API unreachable")
    }

    @Test
    fun `marking failing records the state and the error`() {
        // given
        val status = RegistrationStatus()

        // when
        status.markFailing("admin API unreachable")

        // then
        assertThat(status.current()).isEqualTo(Snapshot(State.FAILING, "admin API unreachable"))
    }

    @Test
    fun `a failed attempt while failing stays failing with the newest error`() {
        // given
        val status = RegistrationStatus()
        status.markFailing("first error")

        // when
        status.recordFailure("second error")

        // then
        assertThat(status.current()).isEqualTo(Snapshot(State.FAILING, "second error"))
    }

    @Test
    fun `registering clears the last error`() {
        // given
        val status = RegistrationStatus()
        status.markFailing("admin API unreachable")

        // when
        status.markRegistered()

        // then
        assertThat(status.current()).isEqualTo(Snapshot(State.REGISTERED, null))
    }
}
