package com.example.fee

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication
import org.springframework.context.annotation.Bean
import java.time.Clock

@SpringBootApplication
@ConfigurationPropertiesScan
class FeeServiceApplication {
    /**
     * The only source of "now". UTC rather than the host's zone, so a `created_at` without a zone is judged
     * the same way on every machine.
     */
    @Bean
    fun clock(): Clock = Clock.systemUTC()
}

fun main(args: Array<String>) {
    runApplication<FeeServiceApplication>(*args)
}
