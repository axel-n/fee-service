package com.example.fee.contract

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.module.SimpleModule
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.math.BigDecimal

class PlainBigDecimalSerializerTest {
    private val mapper =
        ObjectMapper().registerModule(
            SimpleModule().addSerializer(BigDecimal::class.java, PlainBigDecimalSerializer()),
        )

    @ParameterizedTest(name = "{0} is written as {1}")
    @CsvSource(
        "1E+3, 1000",
        "1E-7, 0.0000001",
        "1.50, 1.50",
        "0.0015, 0.0015",
        "-2.00, -2.00",
        "0, 0",
        "0E-4, 0.0000",
        "0E+3, 0",
        "3000000000, 3000000000",
        "3E+9, 3000000000",
        "3000000000.01, 3000000000.01",
        "-1E+10, -10000000000",
    )
    fun `writes a plain number that keeps its scale`(
        value: String,
        expected: String,
    ) {
        // given
        val number = BigDecimal(value)

        // when
        val json = mapper.writeValueAsString(number)

        // then
        assertThat(json).isEqualTo(expected)
    }

    @ParameterizedTest(name = "{0} is written plain, {1} characters")
    @CsvSource("1E+9999, 10000", "1E-9999, 10001", "-1E+9999, 10001")
    fun `writes plain up to the scale bound`(
        value: String,
        length: Int,
    ) {
        // given
        val number = BigDecimal(value)

        // when
        val json = mapper.writeValueAsString(number)

        // then
        assertThat(json).hasSize(length).doesNotContain("E")
        assertThat(BigDecimal(json)).isEqualByComparingTo(number)
    }

    @ParameterizedTest(name = "{0} stays scientific")
    @CsvSource("1E+999999999", "1E-10000", "1E+10000", "-1E+10000", "1E-999999999", "-1E-999999999")
    fun `keeps scientific notation for an extreme scale instead of building a huge string`(value: String) {
        // given
        val number = BigDecimal(value)

        // when
        val json = mapper.writeValueAsString(number)

        // then
        assertThat(json).hasSizeLessThan(20)
        assertThat(BigDecimal(json)).isEqualByComparingTo(number)
    }
}
