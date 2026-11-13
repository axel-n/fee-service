package com.example.fee.bdd

import com.example.fee.it.CountingFeeCalculator
import com.example.fee.it.FaultInjectingStepHook
import com.example.fee.it.awaitUntil
import com.example.fee.restate.RestateEndpointLifecycle
import com.example.fee.workflow.WorkflowStep
import io.cucumber.java.After
import io.cucumber.java.en.Given
import io.cucumber.java.en.Then
import io.cucumber.java.en.When
import org.assertj.core.api.Assertions.assertThat

/**
 * The steps of failures during processing: a crash at a step boundary ([FaultInjectingStepHook]) and an endpoint
 * restart while the calculation is held ([CountingFeeCalculator.block]). The scenario's transaction and its
 * submissions live in [FeeSteps] (injected, the same instance as the scenario's), so its fee, charge and record steps
 * check the recovered workflow.
 *
 * After every scenario the held calculations are let go and the shared endpoint is running again, so a failed scenario
 * can't leave a thread waiting or the endpoint down for the rest of the run.
 */
class RecoverySteps(
    private val fee: FeeSteps,
    private val calculator: CountingFeeCalculator,
    private val hook: FaultInjectingStepHook,
    private val endpoint: RestateEndpointLifecycle,
) {
    private val blockedIds = mutableListOf<String>()

    /** Arms one crash, a plain exception Restate retries, at the [moment] (`before` / `after`) side of [step]. */
    @Given("the service will crash {word} the {string} step")
    fun theServiceWillCrash(moment: String, step: String) {
        val phase =
            FaultInjectingStepHook.Phase.entries.singleOrNull { it.name.equals(moment, ignoreCase = true) }
                ?: error("Unknown moment '$moment', expected one of ${FaultInjectingStepHook.Phase.entries.map { it.name.lowercase() }}")
        val workflowStep =
            WorkflowStep.entries.singleOrNull { it.name == step && it != WorkflowStep.DONE }
                ?: error("Unknown step '$step', expected one of ${WorkflowStep.entries.filter { it != WorkflowStep.DONE }}")
        hook.crashOnce(fee.submittedTransactionId(), workflowStep, phase)
    }

    /** Proves the scenario exercised a recovery: the armed crash really happened, once. */
    @Then("the service crashed once")
    fun theServiceCrashedOnce() {
        fee.responses()
        assertThat(hook.crashCount(fee.submittedTransactionId())).describedAs("injected crashes").isEqualTo(1)
    }

    /** Holds every calculation of the transaction until the endpoint has restarted ([theServiceEndpointRestarts]). */
    @Given("the fee calculation is slow")
    fun theFeeCalculationIsSlow() {
        val transactionId = fee.submittedTransactionId()
        calculator.block(transactionId)
        blockedIds += transactionId
    }

    /**
     * Stops and starts the endpoint while the workflow is inside CALCULATE, then waits until Restate has run CALCULATE
     * again on the restarted endpoint (it was not journaled) before the calculation goes on. Both calls then finish, and
     * only the journaled one counts.
     */
    @When("the service endpoint restarts")
    fun theServiceEndpointRestarts() {
        val transactionId = fee.submittedTransactionId()
        check(transactionId in blockedIds) { "The fee calculation must be slow, or the restart may miss CALCULATE" }
        awaitUntil("CALCULATE has started") { calculator.count(transactionId) >= 1 }

        endpoint.stop()
        assertThat(endpoint.isRunning).describedAs("endpoint running after stop").isFalse()
        endpoint.start()
        assertThat(endpoint.isRunning).describedAs("endpoint running after start").isTrue()

        awaitUntil("Restate runs CALCULATE again on the restarted endpoint") { calculator.count(transactionId) >= 2 }
        calculator.release(transactionId)
    }

    @After
    fun restoreCalculatorAndEndpoint() {
        blockedIds.forEach(calculator::release)
        endpoint.start()
    }
}
