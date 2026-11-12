package com.example.fee.it

import dev.restate.client.Client
import dev.restate.serde.jackson.JacksonSerdeFactory

/**
 * A client of the shared Restate's ingress for reading what Restate stored, past the app's own API. Uses Restate's own
 * serde, as the app's ingress client does (D35).
 */
object TestIngress {

    // Connecting opens no connection yet
    val client: Client by lazy { Client.connect(RestateTestEnvironment.ingressUrl, JacksonSerdeFactory()) }
}
