package com.example.fee.wallet

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ChargeDeclinedExceptionTest {

    @Test
    fun `carries the key and reason and names both in its message`() {
        // given
        val key = "fee-txn_001"

        // when
        val exception = ChargeDeclinedException(key, "insufficient funds")

        // then
        assertThat(exception.idempotencyKey).isEqualTo("fee-txn_001")
        assertThat(exception.reason).isEqualTo("insufficient funds")
        assertThat(exception).hasMessage("Charge 'fee-txn_001' declined: insufficient funds")
    }
}
