package com.example.fee.restate

import com.example.fee.config.RestateProperties
import com.example.fee.workflow.FeeWorkflow
import dev.restate.sdk.endpoint.Endpoint
import dev.restate.sdk.http.vertx.RestateHttpServer
import org.slf4j.LoggerFactory
import org.springframework.context.SmartLifecycle
import org.springframework.stereotype.Component
import java.lang.reflect.InvocationTargetException
import java.util.concurrent.CompletionStage
import java.util.concurrent.ExecutionException

private val log = LoggerFactory.getLogger(RestateEndpointLifecycle::class.java)

/**
 * Serves the Spring-built [FeeWorkflow] bean as a Restate endpoint on `restate.endpoint-port`, and keeps it registered
 * with Restate while it runs. It is the only place that starts or stops the endpoint, so tests stop and start it to
 * simulate a crash or a restart.
 *
 * Start listens, then registers the endpoint as `http://<restate.advertised-host>:<port>`. Registering the same URL again
 * after a restart keeps its deployment, so invocations pinned to it resume. A failed registration fails the start: an app
 * Restate can't reach would only ever answer 202.
 *
 * Stop closes the endpoint, then removes its deployment from Restate unless invocations that haven't finished still need
 * it. Removing it would strand them: Restate never resumes an invocation whose deployment is gone, not even at the same
 * URL. Kept, they resume once the endpoint is back at that URL. Stop logs a failure instead of throwing, so the shutdown
 * carries on. Open connections drop on stop, and Restate retries them.
 *
 * Each start gets its own Vert.x instance. Stop closes the server and then that instance, which frees the port and
 * leaves no Vert.x threads behind. Spring stops it before it destroys the context's beans.
 */
@Component
class RestateEndpointLifecycle(
    private val feeWorkflow: FeeWorkflow,
    private val deployments: RestateDeployments,
    private val properties: RestateProperties,
) : SmartLifecycle {
    private val lock = Any()

    // null while stopped; replaced on every start, because a closed Vert.x instance can't be reused.
    @Volatile
    private var started: Started? = null

    override fun start() {
        synchronized(lock) {
            if (started != null) return
            val endpoint =
                Endpoint
                    .builder()
                    .bind(feeWorkflow)
                    .build()
            val server = RunningServer.listen(endpoint, properties.endpointPort)
            log.info("Restate endpoint listening on port {}", server.port)
            started = Started(server, register(server))
        }
    }

    override fun stop() {
        synchronized(lock) {
            val running = started ?: return
            started = null
            running.server
                .close()
                .onSuccess { log.info("Restate endpoint on port {} stopped", running.server.port) }
                .onFailure { log.warn("Restate endpoint on port {} did not stop cleanly", running.server.port, it) }
            removeUnlessNeeded(running.deployment)
        }
    }

    override fun isRunning(): Boolean = started != null

    /** The port the endpoint listens on while it runs (the OS's pick when `endpoint-port` is 0), else `null`. */
    fun port(): Int? = started?.server?.port

    // Restate discovers the services by calling the endpoint, so it registers only once the endpoint listens.
    private fun register(server: RunningServer): Deployment {
        val uri = "http://${properties.advertisedHost}:${server.port}"
        return try {
            deployments
                .register(uri)
                .also { log.info("Restate endpoint registered as {} (deployment {})", uri, it.id) }
        } catch (e: Exception) {
            server.close()
            throw IllegalStateException(
                "Could not register the Restate endpoint $uri with Restate's admin API at ${properties.adminUrl} " +
                    "(${RestateProperties.PREFIX}.advertised-host, ${RestateProperties.PREFIX}.admin-url): ${e.message}",
                e,
            )
        }
    }

    // On a failure the deployment stays: a stale entry is harmless, a removed one can strand invocations.
    private fun removeUnlessNeeded(deployment: Deployment) {
        try {
            val unfinished = deployments.unfinishedInvocations(deployment)
            if (unfinished == 0L) {
                deployments.remove(deployment)
                log.info("Restate deployment {} removed", deployment.id)
            } else {
                log.info(
                    "Restate deployment {} kept: {} unfinished invocation(s) resume once the endpoint is back at its URL",
                    deployment.id,
                    unfinished,
                )
            }
        } catch (e: Exception) {
            log.warn("Restate deployment {} kept: it could not be checked or removed: {}", deployment.id, e.toString())
        }
    }

    /** A running endpoint: its server and the deployment Restate registered it as. */
    private class Started(
        val server: RunningServer,
        val deployment: Deployment,
    )

    /**
     * A listening server and its own Vert.x instance. `io.vertx:vertx-core` is only a runtime dependency of the SDK, so
     * Vert.x is reached by reflection rather than declared as a compile dependency.
     */
    private class RunningServer(
        private val vertx: Any,
        private val httpServer: Any,
        val port: Int,
    ) {
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
                    val httpServer =
                        checkNotNull(
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

            private fun invoke(target: Any?, type: Class<*>, method: String): Any? = unwrapping { type.getMethod(method).invoke(target) }

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
}
