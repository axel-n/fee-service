package com.example.fee.domain

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.time.format.DateTimeParseException
import java.time.format.ResolverStyle
import java.time.temporal.ChronoField

/**
 * The format of the request's `created_at`: `2023-08-30 15:42:17.610059`, a space between date and time, 0 to 9
 * fraction digits, no zone (read as UTC, D26). One place, so validation and mapping can't disagree on it.
 */
object CreatedAtFormat {
    private val formatter: DateTimeFormatter = DateTimeFormatterBuilder()
        .appendPattern("uuuu-MM-dd HH:mm:ss")
        .optionalStart()
        .appendFraction(ChronoField.NANO_OF_SECOND, 1, 9, true)
        .optionalEnd()
        .toFormatter()
        .withResolverStyle(ResolverStyle.STRICT)

    /** The parsed value, or `null` for null or anything not in the format (including impossible dates). Never throws. */
    fun parseOrNull(raw: String?): LocalDateTime? =
        raw?.let {
            try {
                LocalDateTime.parse(it, formatter)
            } catch (_: DateTimeParseException) {
                null
            }
        }
}
