package com.example.fee.it

import com.example.fee.workflow.StepHook
import com.example.fee.workflow.WorkflowStep
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * The step hook of the shared IT app (it replaces `NoOpStepHook`, see [IntegrationTestBeans]). A test arms it for one
 * `transaction_id` with [crashOnce]; the first time that workflow reaches the armed boundary, the hook throws
 * [SimulatedCrash], a plain exception, so Restate retries the invocation from the top and replays the journal (D33).
 *
 * It also counts its calls, so a test can see that the handler ran again. Hook calls are not step executions: the hooks
 * run on every replay, the `ctx.run` closures only once. Unarmed, it does nothing, so other ITs are unaffected.
 */
class FaultInjectingStepHook : StepHook {

    /** Which side of a step the crash happens on. */
    enum class Phase { BEFORE, AFTER }

    private data class Boundary(val step: WorkflowStep, val phase: Phase)

    // Compared by identity, so remove(key, value) disarms exactly the arming that was found
    private class Arming(val boundary: Boundary, val onCrash: () -> Unit)

    private val armed = ConcurrentHashMap<String, Arming>()
    private val crashes = ConcurrentHashMap<String, AtomicInteger>()
    private val calls = ConcurrentHashMap<Pair<String, Boundary>, AtomicInteger>()

    /**
     * Makes the workflows of [transactionId] crash once, at the [phase] side of [step]. [onCrash] runs right before the
     * crash, on the handler's thread, so whatever it changes (the clock, say) is in place before Restate retries.
     */
    fun crashOnce(transactionId: String, step: WorkflowStep, phase: Phase, onCrash: () -> Unit = {}) {
        armed[transactionId] = Arming(Boundary(step, phase), onCrash)
    }

    /** How many crashes were injected into the workflows of [transactionId]. */
    fun crashCount(transactionId: String): Int = crashes[transactionId]?.get() ?: 0

    /** How many times the hook was called at the [phase] side of [step] for [transactionId], replays included. */
    fun callCount(transactionId: String, step: WorkflowStep, phase: Phase): Int =
        calls[transactionId to Boundary(step, phase)]?.get() ?: 0

    override fun before(step: WorkflowStep, workflowKey: String) = reach(workflowKey, Boundary(step, Phase.BEFORE))

    override fun after(step: WorkflowStep, workflowKey: String) = reach(workflowKey, Boundary(step, Phase.AFTER))

    private fun reach(workflowKey: String, boundary: Boundary) {
        val transactionId = workflowKey.substringBefore(':')
        calls.computeIfAbsent(transactionId to boundary) { AtomicInteger() }.incrementAndGet()
        // remove(key, value) disarms atomically, so the crash happens exactly once even if two runs reach it together
        val arming = armed[transactionId]?.takeIf { it.boundary == boundary } ?: return
        if (armed.remove(transactionId, arming)) {
            crashes.computeIfAbsent(transactionId) { AtomicInteger() }.incrementAndGet()
            arming.onCrash()
            throw SimulatedCrash("Simulated crash ${boundary.phase} ${boundary.step} in workflow $workflowKey")
        }
    }

    /** The injected failure: a plain exception, which Restate retries. */
    class SimulatedCrash(message: String) : RuntimeException(message)
}
