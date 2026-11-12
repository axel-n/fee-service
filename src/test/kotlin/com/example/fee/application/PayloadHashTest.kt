package com.example.fee.application

import com.example.fee.contract.ContractMapper
import com.example.fee.contract.FeeRequest
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.EnumSource
import org.junit.jupiter.params.provider.MethodSource
import org.junit.jupiter.params.provider.ValueSource
import java.math.BigDecimal
import java.util.concurrent.TimeUnit

class PayloadHashTest {

    private val reference = FeeRequest(
        transactionId = "txn_001",
        amount = BigDecimal("1000"),
        asset = "USD",
        assetType = "FIAT",
        type = "Mobile Top Up",
        state = "SETTLED - PENDING FEE",
        createdAt = "2023-08-30 15:42:17.610059",
    )

    @ParameterizedTest
    @ValueSource(strings = ["1000.00", "1000.0", "1E+3", "1.000E3", "1000.000000"])
    fun `normalizes the amount, so 1000 and an equal amount written differently hash alike`(amount: String) {
        // given
        val other = reference.copy(amount = BigDecimal(amount))

        // when
        val hash = PayloadHash.of(other)

        // then
        assertThat(hash).isEqualTo(PayloadHash.of(reference))
    }

    @Test
    fun `normalizes zero and negative amounts too`() {
        // given
        val zero = reference.copy(amount = BigDecimal("0"))
        val negative = reference.copy(amount = BigDecimal("-1.5"))

        // when
        val zeroes = listOf("0.00", "0E+5", "0E-5").map { PayloadHash.of(reference.copy(amount = BigDecimal(it))) }
        val negatives = listOf("-1.50", "-15E-1").map { PayloadHash.of(reference.copy(amount = BigDecimal(it))) }

        // then
        assertThat(zeroes).containsOnly(PayloadHash.of(zero))
        assertThat(negatives).containsOnly(PayloadHash.of(negative))
    }

    @ParameterizedTest
    @EnumSource(ContractMapper::class)
    fun `the order of the fields in the JSON body doesn't change the hash`(mapper: ContractMapper) {
        // given
        val inContractOrder = """
            {"transaction_id": "txn_001", "amount": 1000, "asset": "USD", "asset_type": "FIAT",
             "type": "Mobile Top Up", "state": "SETTLED - PENDING FEE", "created_at": "2023-08-30 15:42:17.610059"}
        """.trimIndent()
        val reordered = """
            {"created_at": "2023-08-30 15:42:17.610059", "state": "SETTLED - PENDING FEE", "type": "Mobile Top Up",
             "amount": 1000.00, "asset_type": "FIAT", "asset": "USD", "transaction_id": "txn_001"}
        """.trimIndent()

        // when
        val first = PayloadHash.of(mapper.read(inContractOrder, FeeRequest::class.java))
        val second = PayloadHash.of(mapper.read(reordered, FeeRequest::class.java))

        // then
        assertThat(second).isEqualTo(first)
        assertThat(first).isEqualTo(PayloadHash.of(reference))
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("changedFields")
    fun `a change to any field changes the hash`(field: String, changed: (FeeRequest) -> FeeRequest) {
        // given
        val other = changed(reference)

        // when
        val hash = PayloadHash.of(other)

        // then
        assertThat(hash).isNotEqualTo(PayloadHash.of(reference))
    }

    @Test
    fun `a missing field and an empty one hash differently`() {
        // given
        val missing = reference.copy(asset = null)
        val empty = reference.copy(asset = "")

        // when
        val hashes = listOf(missing, empty).map(PayloadHash::of)

        // then
        assertThat(hashes[0]).isNotEqualTo(hashes[1])
    }

    @Test
    fun `the same text in another field hashes differently`() {
        // given
        val inType = reference.copy(asset = null, type = "USD")
        val inAsset = reference.copy(asset = "USD", type = null)

        // when
        val hashes = listOf(inType, inAsset).map(PayloadHash::of)

        // then
        assertThat(hashes[0]).isNotEqualTo(hashes[1])
    }

    @Test
    fun `a value can't pass for the next field by containing the separators`() {
        // given
        val split = reference.copy(asset = "USD", assetType = "FIAT")
        val joined = reference.copy(asset = "USD\nasset_type=4:FIAT", assetType = null)

        // when
        val hashes = listOf(split, joined).map(PayloadHash::of)

        // then
        assertThat(hashes[0]).isNotEqualTo(hashes[1])
    }

    @Test
    fun `is 64 lowercase hex digits and pinned for the reference request, because hashes are stored`() {
        // given
        val request = reference

        // when
        val hash = PayloadHash.of(request)

        // then
        assertThat(PayloadHash.canonical(request)).isEqualTo(
            "fee-request/v1\n" +
                "transaction_id=7:txn_001\n" +
                "amount=3:1E3\n" +
                "asset=3:USD\n" +
                "asset_type=4:FIAT\n" +
                "type=13:Mobile Top Up\n" +
                "state=21:SETTLED - PENDING FEE\n" +
                "created_at=26:2023-08-30 15:42:17.610059\n",
        )
        assertThat(hash.value).isEqualTo("5f83c964915aab97c2886b04b478206d5201855e2461042ea5d5ad1a73870db5")
        assertThat(hash.toString()).isEqualTo(hash.value)
    }

    @Test
    fun `hashes an empty request`() {
        // given
        val request = FeeRequest()

        // when
        val hash = PayloadHash.of(request)

        // then
        assertThat(hash.value).matches("[0-9a-f]{64}")
        assertThat(hash).isNotEqualTo(PayloadHash.of(reference))
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    fun `hashes a hostile amount without expanding it, because hashing comes before validation`() {
        // given
        val huge = reference.copy(amount = BigDecimal("1E+999999999"))
        val tiny = reference.copy(amount = BigDecimal("1E-999999999"))

        // when
        val hashes = listOf(huge, tiny).map(PayloadHash::of)

        // then
        assertThat(hashes[0]).isNotEqualTo(hashes[1])
        assertThat(hashes[0]).isEqualTo(PayloadHash.of(reference.copy(amount = BigDecimal("10E+999999998"))))
    }

    @ParameterizedTest
    @ValueSource(strings = ["", "abc", "A0E277B554C630426FB1AFBF643A3ACB0AFE62FD75561AB424054DE3EAF4DFD9"])
    fun `rejects a value that isn't 64 lowercase hex digits`(value: String) {
        // given
        val raw = value

        // when / then
        assertThatThrownBy { PayloadHash(raw) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("64 lowercase hex digits")
    }

    companion object {
        @JvmStatic
        fun changedFields(): List<Arguments> = listOf(
            Arguments.of("transaction_id", { r: FeeRequest -> r.copy(transactionId = "txn_002") }),
            Arguments.of("amount", { r: FeeRequest -> r.copy(amount = BigDecimal("1000.01")) }),
            Arguments.of("amount missing", { r: FeeRequest -> r.copy(amount = null) }),
            Arguments.of("asset", { r: FeeRequest -> r.copy(asset = "EUR") }),
            Arguments.of("asset case", { r: FeeRequest -> r.copy(asset = "usd") }),
            Arguments.of("asset_type", { r: FeeRequest -> r.copy(assetType = "CRYPTO") }),
            Arguments.of("type", { r: FeeRequest -> r.copy(type = "Bank Transfer") }),
            Arguments.of("type untrimmed", { r: FeeRequest -> r.copy(type = "Mobile Top Up ") }),
            Arguments.of("state", { r: FeeRequest -> r.copy(state = "PENDING") }),
            Arguments.of("created_at", { r: FeeRequest -> r.copy(createdAt = "2023-08-30 15:42:17.610060") }),
        )
    }
}
