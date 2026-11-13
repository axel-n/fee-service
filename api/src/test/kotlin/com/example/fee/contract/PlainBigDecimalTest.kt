package com.example.fee.contract

import com.fasterxml.jackson.annotation.JsonProperty
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.EnumSource
import org.junit.jupiter.params.provider.MethodSource
import java.math.BigDecimal

/** Through both mappers the contract meets ([ContractMapper]): each Jackson generation must honour the annotation. */
class PlainBigDecimalTest {
    data class Amount(
        @JsonProperty("value") @PlainBigDecimal val value: BigDecimal,
    )

    private fun ContractMapper.write(amount: Amount): String = write(amount, Amount::class.java)

    private fun number(json: String): String = json.removeSurrounding("{\"value\":", "}")

    @ParameterizedTest(name = "{0}: {1} is written as {2}")
    @MethodSource("plainNumbers")
    fun `writes a plain number that keeps its scale`(mapper: ContractMapper, value: String, expected: String) {
        // given
        val amount = Amount(BigDecimal(value))

        // when
        val json = mapper.write(amount)

        // then
        assertThat(json).isEqualTo("{\"value\":$expected}")
    }

    @ParameterizedTest(name = "{0}: {1} is written plain, {2} characters")
    @MethodSource("scaleBounds")
    fun `writes plain up to the scale bound`(mapper: ContractMapper, value: String, length: Int) {
        // given
        val amount = Amount(BigDecimal(value))

        // when
        val json = mapper.write(amount)

        // then
        assertThat(number(json)).hasSize(length).doesNotContain("E")
        assertThat(BigDecimal(number(json))).isEqualByComparingTo(amount.value)
    }

    @ParameterizedTest(name = "{0}: {1} stays scientific")
    @MethodSource("extremeScales")
    fun `keeps scientific notation for an extreme scale instead of building a huge string`(mapper: ContractMapper, value: String) {
        // given
        val amount = Amount(BigDecimal(value))

        // when
        val json = mapper.write(amount)

        // then
        assertThat(number(json)).hasSizeLessThan(20)
        assertThat(BigDecimal(number(json))).isEqualByComparingTo(amount.value)
    }

    @ParameterizedTest
    @EnumSource(ContractMapper::class)
    fun `reads back the value it wrote`(mapper: ContractMapper) {
        // given
        val amount = Amount(BigDecimal("1E+3"))

        // when
        val read = mapper.read(mapper.write(amount), Amount::class.java)

        // then
        assertThat(read.value).isEqualByComparingTo(amount.value)
    }

    companion object {
        private fun forEachMapper(vararg cases: List<Any>): List<Arguments> =
            ContractMapper.entries.flatMap { mapper -> cases.map { Arguments.of(mapper, *it.toTypedArray()) } }

        @JvmStatic
        fun plainNumbers(): List<Arguments> =
            forEachMapper(
                listOf("1E+3", "1000"),
                listOf("1E-7", "0.0000001"),
                listOf("1.50", "1.50"),
                listOf("0.0015", "0.0015"),
                listOf("-2.00", "-2.00"),
                listOf("0", "0"),
                listOf("0E-4", "0.0000"),
                listOf("0E+3", "0"),
                listOf("3000000000", "3000000000"),
                listOf("3E+9", "3000000000"),
                listOf("3000000000.01", "3000000000.01"),
                listOf("-1E+10", "-10000000000"),
            )

        @JvmStatic
        fun scaleBounds(): List<Arguments> =
            forEachMapper(
                listOf("1E+9999", 10_000),
                listOf("1E-9999", 10_001),
                listOf("-1E+9999", 10_001),
            )

        @JvmStatic
        fun extremeScales(): List<Arguments> =
            forEachMapper(
                listOf("1E+999999999"),
                listOf("1E-10000"),
                listOf("1E+10000"),
                listOf("-1E+10000"),
                listOf("1E-999999999"),
                listOf("-1E-999999999"),
            )
    }
}
