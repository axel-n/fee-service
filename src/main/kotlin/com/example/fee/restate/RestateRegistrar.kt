package com.example.fee.restate

import com.example.fee.config.RestateProperties
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.DisposableBean
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.core.task.TaskRejectedException
import org.springframework.scheduling.TaskScheduler
import org.springframework.stereotype.Component
import java.time.Clock
import java.util.concurrent.ScheduledFuture

/**
 * Makes sure Restate knows this app's endpoint (`restate.advertised-url`) with its current services, once the app is
 * ready (D15, D31): missing → register; registered with other services → register again with `force`; up to date →
 * nothing. It is the only class that registers, and it reports through [RegistrationStatus].
 *
 * A failure never fails startup. The first attempt runs on the ready event; every further one runs on Spring's
 * [TaskScheduler], `restate.registration.backoff` after the previous one failed. After `max-attempts` failures it logs a
 * WARN, the status turns `FAILING`, and it keeps retrying at the same interval until Restate knows the endpoint.
 */
@Component
class RestateRegistrar(
    private val adminClient: RestateAdminClient,
    properties: RestateProperties,
    private val status: RegistrationStatus,
    private val taskScheduler: TaskScheduler,
    private val clock: Clock,
) : DisposableBean {

    private val advertisedUrl = properties.advertisedUrl
    private val registration = properties.registration

    private val lock = Any()

    // Attempts run one after the other: the next one is scheduled only once the previous one has failed.
    @Volatile
    private var attempts = 0

    // guarded by lock
    private var nextAttempt: ScheduledFuture<*>? = null
    private var stopped = false

    @EventListener(ApplicationReadyEvent::class)
    fun onApplicationReady() {
        if (!registration.enabled) {
            log.info(
                "Restate registration is disabled ({}.registration.enabled=false); not registering {}",
                RestateProperties.PREFIX,
                advertisedUrl,
            )
            return
        }
        attempt()
    }

    /** Cancels a pending retry, so nothing runs against a closing context. */
    override fun destroy() {
        synchronized(lock) {
            stopped = true
            nextAttempt?.cancel(false)
            nextAttempt = null
        }
    }

    private fun attempt() {
        if (synchronized(lock) { stopped }) return
        val attempt = ++attempts
        val result = try {
            ensureRegistered()
        } catch (e: Exception) {
            failed(attempt, e)
            return
        }
        status.markRegistered()
        log.info("Restate endpoint {}: {} (attempt {})", advertisedUrl, result, attempt)
    }

    private fun ensureRegistered(): String {
        val existing = adminClient.listDeployments().firstOrNull { it.uri != null && sameUri(it.uri, advertisedUrl) }
            ?: return "registered as deployment ${adminClient.register(advertisedUrl).id}"
        // Re-registering a known URI without force ignores changed services, so compare them first (D31).
        val discovered = adminClient.register(advertisedUrl, force = true, dryRun = true).services
        val registered = adminClient.deployment(existing.id).services
        if (comparable(discovered) == comparable(registered)) {
            return "already registered as deployment ${existing.id} with the current services"
        }
        val forced = adminClient.register(advertisedUrl, force = true)
        return "services changed, re-registered deployment ${forced.id} with force"
    }

    private fun failed(attempt: Int, error: Exception) {
        val message = error.message ?: error.javaClass.name
        val maxAttempts = registration.maxAttempts
        when {
            attempt < maxAttempts -> {
                status.recordFailure(message)
                log.info(
                    "Restate registration attempt {}/{} failed, retrying in {}: {}",
                    attempt, maxAttempts, registration.backoff, message,
                )
            }
            attempt == maxAttempts -> {
                status.markFailing(message)
                log.warn(
                    "Restate does not know endpoint {} after {} attempts: {}. The app keeps running and retries every {} " +
                        "in the background; until then requests to Restate fail",
                    advertisedUrl, attempt, message, registration.backoff,
                )
            }
            else -> {
                status.recordFailure(message)
                log.debug("Restate registration attempt {} failed: {}", attempt, message)
            }
        }
        log.debug("Restate registration attempt {} failed", attempt, error)
        scheduleNextAttempt()
    }

    private fun scheduleNextAttempt() {
        synchronized(lock) {
            if (stopped) return
            nextAttempt = try {
                taskScheduler.schedule({ attempt() }, clock.instant().plus(registration.backoff))
            } catch (e: TaskRejectedException) {
                // the scheduler is shutting down with the context
                log.debug("Restate registration retry not scheduled: {}", e.message)
                null
            }
        }
    }

    private companion object {
        private val log = LoggerFactory.getLogger(RestateRegistrar::class.java)

        // Restate stores the URI with a trailing slash (D31)
        private fun sameUri(a: String, b: String) = a.trimEnd('/') == b.trimEnd('/')

        // Handler and service order carry no meaning (D54)
        private fun comparable(services: List<ServiceDescription>) =
            services.map { service -> service.copy(handlers = service.handlers.sortedBy { it.name }) }.sortedBy { it.name }
    }
}
