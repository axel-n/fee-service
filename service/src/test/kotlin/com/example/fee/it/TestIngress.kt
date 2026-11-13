package com.example.fee.it

import com.example.fee.restate.serde.KotlinJacksonSerdeFactory
import dev.restate.client.Client

/**
 * A client of the shared Restate's ingress for reading what Restate stored, past the app's own API. Uses the serde the
 * handlers declare, as the app's ingress client does.
 */
object TestIngress {
    // Connecting opens no connection yet
    val client: Client by lazy { Client.connect(RestateTestEnvironment.ingressUrl, KotlinJacksonSerdeFactory()) }
}
