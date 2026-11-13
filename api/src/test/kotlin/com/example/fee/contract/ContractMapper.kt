package com.example.fee.contract

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import dev.restate.common.Slice
import dev.restate.serde.jackson.JacksonSerdeFactory
import tools.jackson.databind.json.JsonMapper

/**
 * The two mappers the contract meets, one per Jackson generation: Jackson 3 for HTTP, with the modules on the
 * classpath as Spring Boot finds them, and Restate's own Jackson 2 mapper for the journal and state.
 * Parameterized tests run against both, so a field name or number format that only one of them honours fails.
 */
enum class ContractMapper {
    HTTP {
        private val mapper: JsonMapper = JsonMapper.builder().findAndAddModules().build()

        override fun <T> write(value: T, type: Class<T>): String = mapper.writerFor(type).writeValueAsString(value)

        override fun <T> read(json: String, type: Class<T>): T = mapper.readValue(json, type)
    },
    RESTATE {
        private val factory = JacksonSerdeFactory()

        override fun <T> write(value: T, type: Class<T>): String =
            String(factory.create(type).serialize(value).toByteArray(), Charsets.UTF_8)

        override fun <T> read(json: String, type: Class<T>): T = factory.create(type).deserialize(Slice.wrap(json))
    },
    ;

    abstract fun <T> write(value: T, type: Class<T>): String

    abstract fun <T> read(json: String, type: Class<T>): T

    companion object {
        private val tree = ObjectMapper()

        fun tree(json: String): JsonNode = tree.readTree(json)

        fun fieldNames(json: String): Set<String> = tree(json).fieldNames().asSequence().toSet()
    }
}
