package com.example.fee.restate.serde

import com.example.fee.contract.FeeResponse
import com.example.fee.record.ClaimResult
import com.example.fee.record.FeeRecords.response
import com.example.fee.validation.ValidationError
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
        val claim: ClaimResult = ClaimResult.AlreadyCompleted(response)

        // when
        val kotlinSdk = json(factory.create(typeTag<ClaimResult>()).serialize(claim))
        val javaSdk = json(JacksonSerdeFactory().create(ClaimResult::class.java).serialize(claim))

        // then
        assertThat(kotlinSdk).isEqualTo(javaSdk).contains("\"@type\":\"ALREADY_COMPLETED\"", "\"transaction_id\":\"txn_001\"")
    }

    @Test
    fun `journals a missing nullable result as JSON null and reads it back as null`() {
        // given: VALIDATE journals no error as null
        val serde = factory.create(typeTag<ValidationError?>())

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
