package com.example.fee.it

import com.example.fee.fee.FeeRule
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary

/**
 * The test beans of the shared IT context (D61), added by [RestateIntegrationTest] next to the app's own configuration.
 *
 * Deliberately without `@Configuration` / `@TestConfiguration`: apps started by [startApp] component-scan the test
 * classpath without Spring's test filter, and they must stay the plain app.
 */
class IntegrationTestBeans {

    // @Primary: FakeWalletGateway stays a bean, but the workflow gets this one
    @Bean
    @Primary
    fun testWalletGateway(): TestWalletGateway = TestWalletGateway()

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
