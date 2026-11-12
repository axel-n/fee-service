package com.example.fee.validation

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.core.annotation.Order
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

class CreatedAtValidatorTest {

    private val now = Instant.parse("2023-08-30T16:00:00Z")
    private val validator = CreatedAtValidator(Clock.fixed(now, ZoneOffset.UTC))

    @ParameterizedTest(name = "''{0}''")
    @ValueSource(
        strings = [
            "2023-08-30 15:42:17.610059",
            "2023-08-30 15:42:17",
            "2023-08-30 16:00:00",
            "2023-08-30 16:00:00.000000000",
            "1999-01-01 00:00:00.1",
        ],
    )
    fun `passes a well-formed date up to now`(createdAt: String) {
        // given
        val request = validRequest.copy(createdAt = createdAt)

        // when
        val error = validator.validate(request)

        // then
        assertThat(error).isNull()
    }

    @ParameterizedTest(name = "''{0}''")
    @ValueSource(
        strings = [
            "yesterday",
            "2023-08-30",
            "2023-08-30T15:42:17.610059",
            "2023-08-30 15:42:17Z",
            "2023-02-30 15:42:17",
            "30.08.2023 15:42:17",
        ],
    )
    fun `rejects a malformed date with a message naming it and the expected format`(createdAt: String) {
        // given
        val request = validRequest.copy(createdAt = createdAt)

        // when
        val error = validator.validate(request)

        // then
        assertThat(error).isEqualTo(
            ValidationError(
                "created_at",
                createdAt,
                "Invalid created_at '$createdAt'. Allowed: 'yyyy-MM-dd HH:mm:ss' with 0 to 9 fraction digits, " +
                    "e.g. '2023-08-30 15:42:17.610059'",
                ValidationError.Kind.INVALID,
            ),
        )
    }

    @ParameterizedTest(name = "''{0}''")
    @ValueSource(strings = ["2023-08-30 16:00:00.000001", "2023-08-30 17:00:00", "2099-01-01 00:00:00"])
    fun `rejects a date in the future with a message naming it and now`(createdAt: String) {
        // given
        val request = validRequest.copy(createdAt = createdAt)

        // when
        val error = validator.validate(request)

        // then
        assertThat(error).isEqualTo(
            ValidationError(
                "created_at",
                createdAt,
                "Invalid created_at '$createdAt': it is in the future. Allowed: up to now, 2023-08-30 16:00:00.000000 UTC",
                ValidationError.Kind.INVALID,
            ),
        )
    }

    @Test
    fun `reads created_at as UTC whatever the zone of the clock`() {
        // given
        val tokyo = CreatedAtValidator(Clock.fixed(now, ZoneId.of("Asia/Tokyo")))
        val request = validRequest.copy(createdAt = "2023-08-30 20:00:00")

        // when
        val error = tokyo.validate(request)

        // then
        assertThat(error?.message).contains("in the future")
    }

    @Test
    fun `leaves a missing date to the required fields check`() {
        // given
        val request = validRequest.copy(createdAt = null)

        // when
        val error = validator.validate(request)

        // then
        assertThat(error).isNull()
    }

    @Test
    fun `runs last among the structural checks`() {
        // given
        val type = CreatedAtValidator::class.java

        // when
        val order = type.getAnnotation(Order::class.java)?.value

        // then
        assertThat(order).isEqualTo(ValidationOrder.CREATED_AT)
    }
}
