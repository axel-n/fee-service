package com.example.fee.validation.rule

import com.example.fee.contract.FeeRequest
import com.example.fee.domain.CreatedAtFormat
import com.example.fee.validation.TransactionValidator
import com.example.fee.validation.ValidationError
import com.example.fee.validation.ValidationOrder
import com.example.fee.validation.ValueDisplay
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * `created_at` must be in the request format ([CreatedAtFormat]) and not later than now, read as UTC (400).
 * Reads the [Clock], which is why VALIDATE runs inside a journaled step: the result is journaled.
 * Relies on [RequiredFieldsValidator] for presence.
 */
@Component
@Order(ValidationOrder.CREATED_AT)
class CreatedAtValidator(private val clock: Clock) : TransactionValidator {

    override fun validate(request: FeeRequest): ValidationError? {
        val raw = request.createdAt ?: return null
        val createdAt = CreatedAtFormat.parseOrNull(raw)
            ?: return error(
                raw,
                "Invalid $FIELD ${ValueDisplay.quoted(raw)}. Allowed: 'yyyy-MM-dd HH:mm:ss' with 0 to 9 fraction " +
                    "digits, e.g. '$EXAMPLE'",
            )
        // UTC explicitly, so a Clock in another zone (a test, a misconfigured bean) can't shift the check.
        val now = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC)
        if (!createdAt.isAfter(now)) return null
        return error(
            raw,
            "Invalid $FIELD ${ValueDisplay.quoted(raw)}: it is in the future. Allowed: up to now, " +
                "${NOW_FORMAT.format(now)} UTC",
        )
    }

    private fun error(raw: String, message: String) =
        ValidationError(FIELD, raw, message, ValidationError.Kind.INVALID)

    private companion object {
        const val FIELD = "created_at"
        const val EXAMPLE = "2023-08-30 15:42:17.610059"
        val NOW_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss.SSSSSS")
    }
}
