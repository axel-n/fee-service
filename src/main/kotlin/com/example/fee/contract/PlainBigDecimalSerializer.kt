package com.example.fee.contract

import com.fasterxml.jackson.core.JsonGenerator
import com.fasterxml.jackson.databind.SerializerProvider
import com.fasterxml.jackson.databind.ser.std.StdSerializer
import java.math.BigDecimal

/**
 * Writes a [BigDecimal] as a plain JSON number (`0.0000001`, `1000`), never in scientific notation (`1E-7`, `1E+3`),
 * keeping its scale (`1.50`). It is set per field, because the contract is written by two mappers: Spring's for HTTP and
 * Restate's own for the journal and state (D35), and only an annotation reaches both.
 *
 * A scale beyond [MAX_PLAIN_SCALE] falls back to Jackson's default (scientific) form: a request amount like
 * `1e999999999` would otherwise become a billion-digit string before validation can reject it. Both forms read back
 * as the same value.
 */
class PlainBigDecimalSerializer : StdSerializer<BigDecimal>(BigDecimal::class.java) {

    override fun serialize(value: BigDecimal, gen: JsonGenerator, provider: SerializerProvider) {
        if (value.scale() in -MAX_PLAIN_SCALE..MAX_PLAIN_SCALE) {
            gen.writeNumber(value.toPlainString())
        } else {
            gen.writeNumber(value)
        }
    }

    companion object {
        /** The bound Jackson itself uses for `WRITE_BIGDECIMAL_AS_PLAIN`. */
        const val MAX_PLAIN_SCALE = 9999
    }
}
