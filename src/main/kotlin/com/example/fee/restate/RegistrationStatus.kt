package com.example.fee.restate

import org.springframework.stereotype.Component

// TODO(k8s): expose as an Actuator readiness HealthIndicator so the pod is not ready until Restate knows the endpoint
/**
 * Whether Restate knows this app's endpoint, as [RestateRegistrar] last found out (D15), and the last error it saw.
 * The registrar writes it from its own thread, so every read returns one consistent [Snapshot].
 */
@Component
class RegistrationStatus {

    enum class State {
        /** Not registered yet: the first attempts are running, or registration is disabled. */
        PENDING,

        /** Restate knows the endpoint with its current services. */
        REGISTERED,

        /** Still not registered after `restate.registration.max-attempts`; the registrar keeps retrying. */
        FAILING,
    }

    /** The state and the last error; [lastError] is `null` until an attempt fails, and again once registered. */
    data class Snapshot(val state: State, val lastError: String?)

    @Volatile
    private var snapshot = Snapshot(State.PENDING, null)

    fun current(): Snapshot = snapshot

    val state: State get() = snapshot.state

    val lastError: String? get() = snapshot.lastError

    fun markRegistered() {
        snapshot = Snapshot(State.REGISTERED, null)
    }

    /** An attempt failed; the state stays as it is (`PENDING` before `max-attempts`, `FAILING` after). */
    fun recordFailure(error: String) {
        snapshot = snapshot.copy(lastError = error)
    }

    fun markFailing(error: String) {
        snapshot = Snapshot(State.FAILING, error)
    }
}
