package com.example.fee.fee

import com.example.fee.contract.ContractMapper
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class FeeRuleTest {
    @Test
    fun `survives Restate's serde with its scale, as CALCULATE journals it`() {
        // given
        val quote = FeeQuote(fee = BigDecimal("1.50"), rate = BigDecimal("0.0015"), description = "Standard fee rate of 0.15%")

        // when
        val replayed = ContractMapper.RESTATE.read(ContractMapper.RESTATE.write(quote, FeeQuote::class.java), FeeQuote::class.java)

        // then
        assertThat(replayed).isEqualTo(quote)
        assertThat(replayed.fee.scale()).isEqualTo(2)
    }
}
