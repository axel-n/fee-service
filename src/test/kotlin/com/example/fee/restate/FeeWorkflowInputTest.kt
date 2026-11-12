package com.example.fee.restate

import com.example.fee.contract.FeeRequest
import com.example.fee.restate.FeeRecords.HASH
import dev.restate.common.Slice
import dev.restate.serde.jackson.JacksonSerdeFactory
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class FeeWorkflowInputTest {

    private val serde = JacksonSerdeFactory().create(FeeWorkflowInput::class.java)

    private val input = FeeWorkflowInput(
        request = FeeRequest(
            transactionId = "txn_001",
            amount = BigDecimal("1000.00"),
            asset = "USD",
            assetType = "FIAT",
            type = "Mobile Top Up",
            state = "SETTLED - PENDING FEE",
            createdAt = "2023-08-30 15:42:17.610059",
        ),
        payloadHash = HASH,
    )

    @Test
    fun `is journaled with snake_case names and the amount as sent`() {
        // given
        val value = input

        // when
        val json = String(serde.serialize(value).toByteArray())

        // then
        assertThat(json).startsWith("""{"request":{"transaction_id":"txn_001","amount":1000.00,""")
        assertThat(json).endsWith(""""payload_hash":"hash-a"}""")
    }

    @Test
    fun `reads back a journaled input written by an earlier run`() {
        // given
        val json = """
            {"request":{"transaction_id":"txn_001","amount":1000.00,"asset":"USD","asset_type":"FIAT",
            "type":"Mobile Top Up","state":"SETTLED - PENDING FEE","created_at":"2023-08-30 15:42:17.610059"},
            "payload_hash":"hash-a"}
        """.trimIndent()

        // when
        val restored = serde.deserialize(Slice.wrap(json))

        // then
        assertThat(restored).isEqualTo(input)
    }

    @Test
    fun `keeps a request with missing fields, which validation explains later`() {
        // given
        val partial = FeeWorkflowInput(FeeRequest(transactionId = "txn_001"), HASH)

        // when
        val restored = serde.deserialize(serde.serialize(partial))

        // then
        assertThat(restored).isEqualTo(partial)
        assertThat(restored.request.amount).isNull()
    }

    @Test
    fun `rejects an unknown property, as Restate's serde does for every durable type`() {
        // given
        val json = """{"request":{"transaction_id":"txn_001"},"payload_hash":"hash-a","priority":1}"""

        // when / then
        assertThatThrownBy { serde.deserialize(Slice.wrap(json)) }.hasStackTraceContaining("priority")
    }
}
