package com.example.fee.it

import com.example.fee.FeeServiceApplication
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource

/**
 * The base of every IT that boots the app with `@SpringBootTest`: the real app against [RestateTestEnvironment], so
 * registration on startup runs for real. The HTTP API is on a random port (`@LocalServerPort`).
 *
 * Only the packaged configuration is loaded (`spring.config.location=classpath:/`), so a local `config/application.yml`
 * can't change the fees under test, and every `restate.*` setting comes from `@DynamicPropertySource`.
 *
 * All subclasses share one cached application context, which owns the run's endpoint port. A subclass must not change the
 * context (no `@MockitoBean`, no extra properties or configuration): a second context would fail to bind that port. Test
 * beans go into this shared configuration instead ([IntegrationTestBeans]). A test that starts apps of its own stops the
 * shared endpoint first (`RestateEndpointLifecycle.stop()`) and starts it again afterwards.
 */
@SpringBootTest(
    classes = [FeeServiceApplication::class, IntegrationTestBeans::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["spring.config.location=classpath:/"],
)
abstract class RestateIntegrationTest {

    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun restateProperties(registry: DynamicPropertyRegistry) {
            RestateTestEnvironment.registerProperties(registry)
        }
    }
}
