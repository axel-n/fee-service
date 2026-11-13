package com.example.fee

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication

/** The entry point. Its beans are defined in `config`. */
@SpringBootApplication
@ConfigurationPropertiesScan
class FeeServiceApplication

fun main(args: Array<String>) {
    runApplication<FeeServiceApplication>(*args)
}
