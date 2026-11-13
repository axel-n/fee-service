package com.example.fee.contract

import com.fasterxml.jackson.annotation.JacksonAnnotationsInside
import tools.jackson.core.JsonGenerator
import tools.jackson.databind.SerializationContext
import tools.jackson.databind.annotation.JsonSerialize
import tools.jackson.databind.ser.std.StdSerializer
import java.math.BigDecimal
import com.fasterxml.jackson.core.JsonGenerator as Jackson2Generator
import com.fasterxml.jackson.databind.SerializerProvider as Jackson2Provider
import com.fasterxml.jackson.databind.annotation.JsonSerialize as Jackson2Serialize
import com.fasterxml.jackson.databind.ser.std.StdSerializer as Jackson2StdSerializer

/**
 * Writes a [BigDecimal] property as a plain JSON number (`0.0000001`, `1000`), never in scientific notation (`1E-7`,
 * `1E+3`), keeping its scale (`1.50`).
 *
 * The contract is written by two Jackson generations: Spring MVC's Jackson 3 for HTTP, and the Restate SDK's own
 * Jackson 2 mapper for the journal and state. Only an annotation reaches both mappers, and each generation reads only
 * its own `@JsonSerialize`, so this one bundles both through [JacksonAnnotationsInside], which the two generations share.
 *
 * A scale beyond [MAX_PLAIN_SCALE] keeps Jackson's default (scientific) form: a request amount like `1e999999999` would
 * otherwise become a billion-digit string before validation can reject it. Both forms read back as the same value.
 */
@Target(AnnotationTarget.VALUE_PARAMETER, AnnotationTarget.FIELD, AnnotationTarget.PROPERTY_GETTER)
@Retention(AnnotationRetention.RUNTIME)
@JacksonAnnotationsInside
@JsonSerialize(using = PlainBigDecimal.Jackson3Serializer::class)
@Jackson2Serialize(using = PlainBigDecimal.Jackson2Serializer::class)
annotation class PlainBigDecimal {
    /** HTTP bodies: Spring MVC on Boot 4, and consumers on Jackson 3. */
    class Jackson3Serializer : StdSerializer<BigDecimal>(BigDecimal::class.java) {
        override fun serialize(value: BigDecimal, gen: JsonGenerator, ctxt: SerializationContext) {
            if (isPlain(value)) gen.writeNumber(value.toPlainString()) else gen.writeNumber(value)
        }
    }

    /** The Restate journal and state, and consumers still on Jackson 2. */
    class Jackson2Serializer : Jackson2StdSerializer<BigDecimal>(BigDecimal::class.java) {
        override fun serialize(value: BigDecimal, gen: Jackson2Generator, provider: Jackson2Provider) {
            if (isPlain(value)) gen.writeNumber(value.toPlainString()) else gen.writeNumber(value)
        }
    }

    companion object {
        /** The bound Jackson itself uses for `WRITE_BIGDECIMAL_AS_PLAIN`. */
        const val MAX_PLAIN_SCALE = 9_999

        private fun isPlain(value: BigDecimal): Boolean = value.scale() in -MAX_PLAIN_SCALE..MAX_PLAIN_SCALE
    }
}
