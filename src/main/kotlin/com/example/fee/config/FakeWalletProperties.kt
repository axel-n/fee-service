package com.example.fee.config

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

/** Settings of `FakeWalletGateway`, from `wallet.fake` (D24). A missing or negative delay fails startup. */
@ConfigurationProperties(FakeWalletProperties.PREFIX)
class FakeWalletProperties(delay: Duration?) {

    /** How long the fake wallet waits before it records a charge; raised only for the demo. */
    val delay: Duration = requireNotNull(delay) { "$PREFIX.delay is missing; set it in application.yml" }
        .also { require(!it.isNegative) { "$PREFIX.delay must not be negative, was $it" } }

    companion object {
        const val PREFIX = "wallet.fake"
    }
}
