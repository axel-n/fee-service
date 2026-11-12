package com.example.fee.domain

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.NullAndEmptySource
import org.junit.jupiter.params.provider.ValueSource
import java.time.LocalDateTime

class CreatedAtFormatTest {

    @ParameterizedTest(name = "''{0}'' is {1}")
    @CsvSource(
        "2023-08-30 15:42:17.610059, 2023-08-30T15:42:17.610059",
        "2023-08-30 15:42:17, 2023-08-30T15:42:17",
        "2023-08-30 15:42:17.6, 2023-08-30T15:42:17.600",
        "2024-02-29 00:00:00.123456789, 2024-02-29T00:00:00.123456789",
    )
    fun `parses the request format with 0 to 9 fraction digits`(raw: String, expected: LocalDateTime) {
        // given
        val input = raw

        // when
        val parsed = CreatedAtFormat.parseOrNull(input)

        // then
        assertThat(parsed).isEqualTo(expected)
    }

    @ParameterizedTest(name = "''{0}'' is null")
    @NullAndEmptySource
    @ValueSource(
        strings = [
            "yesterday",
            "2023-08-30",
            "2023-08-30T15:42:17.610059",
            "2023-08-30 15:42:17.",
            "2023-08-30 15:42:17.1234567890",
            "2023-02-30 15:42:17",
            "2023-08-30 24:00:00",
            "2023-08-30 15:42:17Z",
            " 2023-08-30 15:42:17",
        ],
    )
    fun `returns null for anything outside the format without throwing`(raw: String?) {
        // given
        val input = raw

        // when
        val parsed = CreatedAtFormat.parseOrNull(input)

        // then
        assertThat(parsed).isNull()
    }
}
