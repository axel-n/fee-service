package com.example.fee.it

import com.example.fee.FeeServiceApplication
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.context.ConfigurableApplicationContext

/**
 * Starts the app outside Spring's test context cache, for tests about the start itself. It gets the same settings as
 * [RestateIntegrationTest], as command-line arguments so they win over environment variables, plus [overrides].
 * The run's endpoint port must be free: stop the shared endpoint first.
 */
fun startApp(overrides: Map<String, String> = emptyMap()): ConfigurableApplicationContext {
    val properties = mapOf("spring.config.location" to "classpath:/", "server.port" to "0") +
        RestateTestEnvironment.restateProperties() + overrides
    return SpringApplicationBuilder(FeeServiceApplication::class.java)
        .run(*properties.map { (key, value) -> "--$key=$value" }.toTypedArray())
}
