package com.example.fee.restate.serde

import dev.restate.serde.Serde
import dev.restate.serde.TypeRef
import dev.restate.serde.TypeTag
import dev.restate.serde.jackson.JacksonSerdeFactory
import dev.restate.serde.kotlinx.KotlinSerializationSerdeFactory
import dev.restate.serde.kotlinx.KotlinSerializationSerdeFactory.KtTypeTag
import java.lang.reflect.Type
import kotlin.reflect.jvm.javaType

/**
 * Restate's Jackson serde for the Kotlin SDK: named by `@CustomSerdeFactory` on both Restate services, and used by the
 * ingress client. Everything Restate stores goes through it: handler inputs and outputs, `runBlock` results, state.
 *
 * The Kotlin SDK describes types with [KtTypeTag], which the plain [JacksonSerdeFactory] rejects, so this turns them
 * into Java types. It keeps Restate's own Jackson mapper, so stored records, journals and workflow outputs have the same
 * JSON they had under the Java SDK. `Unit` is written as no bytes at all, as the Kotlin SDK writes it.
 *
 * `@CustomSerdeFactory` creates it by reflection, so it needs a public no-arg constructor.
 */
class KotlinJacksonSerdeFactory : JacksonSerdeFactory() {

    @Suppress("UNCHECKED_CAST")
    override fun <T : Any?> create(typeTag: TypeTag<T>): Serde<T> = when {
        typeTag !is KtTypeTag<*> -> super.create(typeTag)
        typeTag.type == Unit::class -> KotlinSerializationSerdeFactory.UNIT as Serde<T>
        else -> create(JavaTypeRef<T>(typeTag.kotlinType?.javaType ?: typeTag.type.java))
    }

    // TypeRef reads its type from a subclass's generic signature; this one takes it from Kotlin reflection instead.
    private class JavaTypeRef<T>(private val javaType: Type) : TypeRef<T>() {
        override fun getType(): Type = javaType
    }
}
