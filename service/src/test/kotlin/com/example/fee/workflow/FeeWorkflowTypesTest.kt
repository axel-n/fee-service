package com.example.fee.workflow

import com.example.fee.contract.ContractMapper
import com.example.fee.contract.FeeRequest
import com.example.fee.record.FeeRecords.HASH
import com.example.fee.record.FeeRecords.response
import com.example.fee.validation.ValidationError
import dev.restate.common.Slice
import dev.restate.serde.jackson.JacksonSerdeFactory
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
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

class FeeOutcomeTest {

    private val serde = JacksonSerdeFactory().create(FeeOutcome::class.java)

    @ParameterizedTest
    @MethodSource("outcomes")
    fun `is stored under its explicit type name with snake_case fields`(
        outcome: FeeOutcome,
        typeName: String,
        fields: List<String>,
    ) {
        // given
        val value = outcome

        // when
        val json = String(serde.serialize(value).toByteArray())

        // then
        assertThat(json).startsWith("""{"@type":"$typeName"""")
        assertThat(ContractMapper.fieldNames(json)).containsExactlyInAnyOrderElementsOf(listOf("@type") + fields)
    }

    @Test
    fun `reads back a charged outcome stored by an earlier run, keeping the fee's scale`() {
        // given
        val json = """
            {"@type":"CHARGED","response":{"transaction_id":"txn_001","amount":1000,"asset":"USD",
            "type":"Mobile Top Up","fee":1.50,"rate":0.0015,"description":"Standard fee rate of 0.15%"}}
        """.trimIndent()

        // when
        val restored = serde.deserialize(Slice.wrap(json))

        // then
        assertThat(restored).isEqualTo(FeeOutcome.Charged(response))
        assertThat((restored as FeeOutcome.Charged).response.fee).isEqualByComparingTo("1.5")
    }

    @Test
    fun `reads back a rejection with the kind that decides the status`() {
        // given
        val json = """
            {"@type":"REJECTED","error":{"field":"state","value":"PENDING",
            "message":"Unsupported state 'PENDING'. Supported: 'SETTLED - PENDING FEE'","kind":"UNSUPPORTED"}}
        """.trimIndent()

        // when
        val restored = serde.deserialize(Slice.wrap(json))

        // then
        assertThat(restored).isEqualTo(FeeOutcome.Rejected(unsupportedState))
    }

    @Test
    fun `rejects an unknown type name`() {
        // given
        val json = """{"@type":"Charged","response":null}"""

        // when / then
        assertThatThrownBy { serde.deserialize(Slice.wrap(json)) }.hasStackTraceContaining("Charged")
    }

    companion object {
        private val unsupportedState = ValidationError(
            field = "state",
            value = "PENDING",
            message = "Unsupported state 'PENDING'. Supported: 'SETTLED - PENDING FEE'",
            kind = ValidationError.Kind.UNSUPPORTED,
        )

        @JvmStatic
        fun outcomes() = listOf(
            Arguments.of(FeeOutcome.Charged(response), "CHARGED", listOf("response")),
            Arguments.of(FeeOutcome.Rejected(unsupportedState), "REJECTED", listOf("error")),
            Arguments.of(FeeOutcome.Conflict("txn_001"), "CONFLICT", listOf("transaction_id")),
        )
    }
}

class WorkflowStepTest {

    @Test
    fun `lists the steps in the order the workflow runs them, ending with DONE`() {
        // given
        val order = listOf("VALIDATE", "MAP", "CLAIM", "CALCULATE", "RECORD", "DONE")

        // when
        val names = WorkflowStep.entries.map { it.name }

        // then
        assertThat(names).containsExactlyElementsOf(order)
    }
}
