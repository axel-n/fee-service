package com.example.fee.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock

/** The app's only source of "now", injected wherever time is read (validation, the fee records' timestamps). */
@Configuration(proxyBeanMethods = false)
class ClockConfig {
    /**
     * UTC rather than the host's zone, so a `created_at` without a zone is judged the same way on every machine.
     */
    @Bean
    fun clock(): Clock = Clock.systemUTC()
}
