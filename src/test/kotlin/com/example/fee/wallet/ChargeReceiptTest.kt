package com.example.fee.wallet

import com.example.fee.domain.Asset
import dev.restate.serde.jackson.JacksonSerdeFactory
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class ChargeReceiptTest {

    @Test
    fun `survives a round trip through Restate's serde with snake_case names and the amount's scale`() {
        // given
        val receipt = ChargeReceipt("fake-charge-1", "fee-txn_001", BigDecimal("1.50"), Asset.USD)
        val serde = JacksonSerdeFactory().create(ChargeReceipt::class.java)

        // when
        val json = serde.serialize(receipt)
        val restored = serde.deserialize(json)

        // then
        assertThat(String(json.toByteArray())).isEqualTo(
            """{"charge_id":"fake-charge-1","idempotency_key":"fee-txn_001","amount":1.50,"asset":"USD"}""",
        )
        assertThat(restored).isEqualTo(receipt)
    }
}
