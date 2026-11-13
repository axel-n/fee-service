package com.example.fee.contract

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import dev.restate.common.Slice
import dev.restate.serde.jackson.JacksonSerdeFactory
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder

/**
 * The two mappers the contract meets: Spring's for HTTP and Restate's own for the journal and state.
 * Parameterized tests run against both, so a field name or number format that only one of them honours fails.
 */
enum class ContractMapper {
    SPRING {
        private val mapper: ObjectMapper = Jackson2ObjectMapperBuilder.json().build()

        override fun <T> write(
            value: T,
            type: Class<T>,
        ): String = mapper.writerFor(type).writeValueAsString(value)

        override fun <T> read(
            json: String,
            type: Class<T>,
        ): T = mapper.readValue(json, type)
    },
    RESTATE {
        private val factory = JacksonSerdeFactory()

        override fun <T> write(
            value: T,
            type: Class<T>,
        ): String = String(factory.create(type).serialize(value).toByteArray(), Charsets.UTF_8)

        override fun <T> read(
            json: String,
            type: Class<T>,
        ): T = factory.create(type).deserialize(Slice.wrap(json))
    },
    ;

    abstract fun <T> write(
        value: T,
        type: Class<T>,
    ): String

    abstract fun <T> read(
        json: String,
        type: Class<T>,
    ): T

    companion object {
        private val tree = ObjectMapper()

        fun tree(json: String): JsonNode = tree.readTree(json)

        fun fieldNames(json: String): Set<String> = tree(json).fieldNames().asSequence().toSet()
    }
}
