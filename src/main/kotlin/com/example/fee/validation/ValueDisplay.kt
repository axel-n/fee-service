package com.example.fee.validation

import com.example.fee.contract.PlainBigDecimalSerializer
import java.math.BigDecimal

/**
 * How a rejected value appears in a [ValidationError]: [value] for the `value` field, [quoted] for the message.
 * Messages echo what the client sent, so an absurdly long value is shortened there and can't bloat the response.
 */
object ValueDisplay {

    /** Longer values are cut to this many characters in messages, followed by `…`. */
    const val MAX_MESSAGE_LENGTH = 64

    /** The amount as JSON carries it: plain (`1000`, `-1.50`), scientific only beyond the serializer's bound (D38). */
    fun value(amount: BigDecimal): String =
        if (amount.scale() in -PlainBigDecimalSerializer.MAX_PLAIN_SCALE..PlainBigDecimalSerializer.MAX_PLAIN_SCALE) {
            amount.toPlainString()
        } else {
            amount.toString()
        }

    /** [raw] shortened to [MAX_MESSAGE_LENGTH] characters, for a message that shows it without quotes. */
    fun shortened(raw: String): String =
        if (raw.length <= MAX_MESSAGE_LENGTH) raw else "${raw.take(MAX_MESSAGE_LENGTH)}…"

    /** [raw] in single quotes, shortened to [MAX_MESSAGE_LENGTH] characters. */
    fun quoted(raw: String): String = "'${shortened(raw)}'"
}
