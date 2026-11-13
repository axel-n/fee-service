package com.example.fee.it

import com.example.fee.fee.FeeRule
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import org.springframework.context.event.EventListener

/**
 * The test beans of the shared IT context, added by [RestateIntegrationTest] next to the app's own configuration.
 *
 * Deliberately without `@Configuration` / `@TestConfiguration`, so a component scan of the test classpath never picks it
 * up and the app stays the plain app everywhere else.
 */
class IntegrationTestBeans {
    // Registers the shared app's endpoint once it listens, as the README asks of whoever starts the app
    @Bean
    fun endpointRegistration(): EndpointRegistration = EndpointRegistration()

    // @Primary over NoOpStepHook; unarmed it does nothing
    @Bean
    @Primary
    fun faultInjectingStepHook(): FaultInjectingStepHook = FaultInjectingStepHook()

    // Joins the injected validator list and runs last; it always passes
    @Bean
    fun countingValidator(): CountingValidator = CountingValidator()

    // @Primary over the app's FeeCalculator, which stays a bean; the same rules, so the same fees
    @Bean
    @Primary
    fun countingFeeCalculator(rules: List<FeeRule>): CountingFeeCalculator = CountingFeeCalculator(rules)

    // @Primary over the app's Clock.systemUTC(); unpinned it reads the same time
    @Bean
    @Primary
    fun testClock(): TestClock = TestClock()
}

/**
 * Registers the run's endpoint with the shared Restate once the shared app is ready: by then `RestateEndpointLifecycle`
 * listens, so Restate can discover the services.
 */
class EndpointRegistration {
    @EventListener(ApplicationReadyEvent::class)
    fun register() {
        RestateTestEnvironment.registerEndpoint()
    }
}
