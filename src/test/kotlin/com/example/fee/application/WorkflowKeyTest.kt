package com.example.fee.application

import com.example.fee.contract.FeeRequest
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.math.BigDecimal

class WorkflowKeyTest {

    private val hash = PayloadHash("5f83c964915aab97c2886b04b478206d5201855e2461042ea5d5ad1a73870db5")

    @Test
    fun `is the transaction id and the payload hash joined by a colon`() {
        // given
        val transactionId = "txn_001"

        // when
        val key = WorkflowKey.of(transactionId, hash)

        // then
        assertThat(key.value).isEqualTo("txn_001:5f83c964915aab97c2886b04b478206d5201855e2461042ea5d5ad1a73870db5")
        assertThat(key.toString()).isEqualTo(key.value)
    }

    @Test
    fun `the same payload gets the same key and a changed payload another one`() {
        // given
        val request = FeeRequest(transactionId = "txn_001", amount = BigDecimal("1000"), state = "PENDING")
        val sameAmountRewritten = request.copy(amount = BigDecimal("1000.00"))
        val changed = request.copy(state = "SETTLED - PENDING FEE")

        // when
        val keys = listOf(request, sameAmountRewritten, changed).map { WorkflowKey.of("txn_001", PayloadHash.of(it)) }

        // then
        assertThat(keys[1]).isEqualTo(keys[0])
        assertThat(keys[2]).isNotEqualTo(keys[0])
        assertThat(keys[2].value).startsWith("txn_001:")
    }

    @ParameterizedTest
    @ValueSource(strings = ["", "txn:001"])
    fun `rejects a transaction id the pre-check would have refused`(transactionId: String) {
        // given
        val id = transactionId

        // when / then
        assertThatThrownBy { WorkflowKey.of(id, hash) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("transaction_id")
    }
}
