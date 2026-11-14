package com.example.fee.restate.serde

import com.example.fee.contract.FeeResponse
import com.example.fee.record.FeeRecords.response
import dev.restate.common.Slice
import dev.restate.serde.TypeTag
import dev.restate.serde.jackson.JacksonSerdeFactory
import dev.restate.serde.kotlinx.KotlinSerializationSerdeFactory.KtTypeTag
import dev.restate.serde.kotlinx.typeTag
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class KotlinJacksonSerdeFactoryTest {
    private val factory = KotlinJacksonSerdeFactory()

    private fun json(slice: Slice): String = String(slice.toByteArray())

    @Test
    fun `writes the same JSON as Restate's Jackson serde, so journaled results keep their shape`() {
        // given
        val output: FeeResponse = response

        // when
        val kotlinSdk = json(factory.create(typeTag<FeeResponse>()).serialize(output))
        val javaSdk = json(JacksonSerdeFactory().create(FeeResponse::class.java).serialize(output))

        // then
        assertThat(kotlinSdk).isEqualTo(javaSdk).contains("\"transaction_id\":\"txn_001\"", "\"fee\":1.50")
    }

    @Test
    fun `journals a missing nullable result as JSON null and reads it back as null`() {
        // given: a nullable result type
        val serde = factory.create(typeTag<FeeResponse?>())

        // when
        val written = serde.serialize(null)

        // then
        assertThat(json(written)).isEqualTo("null")
        assertThat(serde.deserialize(written)).isNull()
    }

    @Test
    fun `writes Unit as no bytes, as the Kotlin SDK expects`() {
        // given
        val serde = factory.create(typeTag<Unit>())

        // when
        val written = serde.serialize(Unit)

        // then
        assertThat(written.toByteArray()).isEmpty()
    }

    @Test
    fun `falls back to the class when a type tag carries no Kotlin type`() {
        // given
        val serde = factory.create(KtTypeTag<FeeResponse>(FeeResponse::class, null))

        // when
        val restored = serde.deserialize(serde.serialize(response))

        // then
        assertThat(restored).isEqualTo(response)
    }

    @Test
    fun `still reads the Java type tags the client uses`() {
        // given
        val serde = factory.create(TypeTag.of(FeeResponse::class.java))

        // when
        val restored = serde.deserialize(serde.serialize(response))

        // then
        assertThat(restored).isEqualTo(response)
    }
}
