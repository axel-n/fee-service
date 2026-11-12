package com.example.fee.restate

import dev.restate.sdk.endpoint.Endpoint
import dev.restate.sdk.http.vertx.RestateHttpServer
import org.slf4j.LoggerFactory
import org.springframework.context.SmartLifecycle
import org.springframework.stereotype.Component
import java.lang.reflect.InvocationTargetException
import java.util.concurrent.CompletionStage
import java.util.concurrent.ExecutionException

/**
 * Serves the Spring-built [FeeWorkflow] and [TransactionFeeRecord] beans as a Restate endpoint on
 * `restate.endpoint-port` (D16, D34). It is the only place that starts or stops the endpoint, so tests stop and start it
 * to simulate a crash or a restart.
 *
 * Each start gets its own Vert.x instance. Stop closes the server and then that instance, which frees the port and
 * leaves no Vert.x threads behind (D32). Spring stops it before it destroys the context's beans. Stop logs a failure
 * instead of throwing, so the shutdown carries on. Open connections drop on stop, and Restate retries them (D32).
 */
@Component
class RestateEndpointLifecycle(
    private val feeWorkflow: FeeWorkflow,
    private val transactionFeeRecord: TransactionFeeRecord,
    private val properties: RestateProperties,
) : SmartLifecycle {

    private val lock = Any()

    // null while stopped; replaced on every start, because a closed Vert.x instance can't be reused.
    @Volatile
    private var server: RunningServer? = null

    override fun start() {
        synchronized(lock) {
            if (server != null) return
            val endpoint = Endpoint.builder()
                .bind(feeWorkflow)
                .bind(transactionFeeRecord)
                .build()
            server = RunningServer.listen(endpoint, properties.endpointPort)
                .also { log.info("Restate endpoint listening on port {}", it.port) }
        }
    }

    override fun stop() {
        synchronized(lock) {
            val running = server ?: return
            server = null
            running.close()
                .onSuccess { log.info("Restate endpoint on port {} stopped", running.port) }
                .onFailure { log.warn("Restate endpoint on port {} did not stop cleanly", running.port, it) }
        }
    }

    override fun isRunning(): Boolean = server != null

    /** The port the endpoint listens on while it runs (the OS's pick when `endpoint-port` is 0), else `null`. */
    fun port(): Int? = server?.port

    /**
     * A listening server and its own Vert.x instance. `io.vertx:vertx-core` is only a runtime dependency of the SDK, so
     * Vert.x is reached by reflection (D32, open question 7).
     */
    private class RunningServer(private val vertx: Any, private val httpServer: Any, val port: Int) {

        /** Closes the server, then its Vert.x instance (also when the server failed to close); never throws. */
        fun close(): Result<Unit> {
            val closedServer = runCatching { await(invoke(httpServer, HTTP_SERVER, "close")) }
            val closedVertx = runCatching { await(invoke(vertx, VERTX, "close")) }
            return closedServer.exceptionOrNull()?.let { Result.failure(it) } ?: closedVertx
        }

        companion object {
            private val VERTX: Class<*> = Class.forName("io.vertx.core.Vertx")
            private val HTTP_SERVER: Class<*> = Class.forName("io.vertx.core.http.HttpServer")
            private val FUTURE: Class<*> = Class.forName("io.vertx.core.Future")

            /** Listens on [port] (0: the OS picks one); fails naming the port, leaving no Vert.x instance behind. */
            fun listen(endpoint: Endpoint, port: Int): RunningServer {
                val vertx = checkNotNull(invoke(null, VERTX, "vertx")) { "Vertx.vertx() returned null" }
                try {
                    val httpServer = checkNotNull(
                        unwrapping {
                            RestateHttpServer::class.java
                                .getMethod("fromEndpoint", VERTX, Endpoint::class.java)
                                .invoke(null, vertx, endpoint)
                        },
                    ) { "RestateHttpServer.fromEndpoint returned null" }
                    await(unwrapping { HTTP_SERVER.getMethod("listen", Int::class.javaPrimitiveType).invoke(httpServer, port) })
                    val actualPort = invoke(httpServer, HTTP_SERVER, "actualPort") as Int
                    return RunningServer(vertx, httpServer, actualPort)
                } catch (e: Exception) {
                    runCatching { await(invoke(vertx, VERTX, "close")) }
                    throw IllegalStateException(
                        "Restate endpoint could not listen on port $port (${RestateProperties.PREFIX}.endpoint-port): ${e.message}",
                        e,
                    )
                }
            }

            private fun invoke(target: Any?, type: Class<*>, method: String): Any? =
                unwrapping { type.getMethod(method).invoke(target) }

            // Vert.x completes a bind or a close (successfully or not) by itself, so the wait needs no timeout of its own.
            private fun await(future: Any?) {
                val stage = invoke(future, FUTURE, "toCompletionStage") as CompletionStage<*>
                try {
                    stage.toCompletableFuture().get()
                } catch (e: ExecutionException) {
                    throw e.cause ?: e
                }
            }

            private fun <T> unwrapping(call: () -> T): T =
                try {
                    call()
                } catch (e: InvocationTargetException) {
                    throw e.targetException ?: e
                }
        }
    }

    private companion object {
        private val log = LoggerFactory.getLogger(RestateEndpointLifecycle::class.java)
    }
}
