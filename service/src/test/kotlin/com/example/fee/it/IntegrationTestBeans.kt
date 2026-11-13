package com.example.fee.it

import com.example.fee.fee.FeeRule
import com.example.fee.record.PostgresFeeRecordRepository
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary

/**
 * The test beans of the shared IT context, added by [RestateIntegrationTest] next to the app's own configuration.
 *
 * Deliberately without `@Configuration` / `@TestConfiguration`, so a component scan of the test classpath never picks it
 * up and the app stays the plain app everywhere else.
 */
class IntegrationTestBeans {
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

    // @Primary over the app's PostgresFeeRecordRepository, which stays a bean and does every write; unarmed it adds nothing
    @Bean
    @Primary
    fun faultInjectingFeeRecordRepository(postgres: PostgresFeeRecordRepository): FaultInjectingFeeRecordRepository =
        FaultInjectingFeeRecordRepository(postgres)
}
