package com.example.fee.bdd

import com.example.fee.it.RestateIntegrationTest
import io.cucumber.spring.CucumberContextConfiguration

/**
 * The Spring context of every scenario: the shared IT context of [RestateIntegrationTest], inherited unchanged (same
 * `@SpringBootTest`, same `@DynamicPropertySource`), so Spring's context cache hands Cucumber the very app the JUnit ITs
 * use, against the one Testcontainers Restate (`RestateTestEnvironment`) and its random ports. Adding anything here
 * would create a second context that can't bind the run's endpoint port (D61).
 */
@CucumberContextConfiguration
class CucumberSpringConfiguration : RestateIntegrationTest()
