package com.example.fee.bdd

import com.example.fee.it.FaultInjectingStepHook
import com.example.fee.it.TestWalletGateway
import com.example.fee.it.awaitUntil
import com.example.fee.restate.RestateEndpointLifecycle
import com.example.fee.workflow.FeeWorkflow
import com.example.fee.workflow.WorkflowStep
import io.cucumber.java.After
import io.cucumber.java.en.Given
import io.cucumber.java.en.Then
import io.cucumber.java.en.When
import org.assertj.core.api.Assertions.assertThat

/**
 * The steps of failures during processing: a crash at a step boundary ([FaultInjectingStepHook]) and an endpoint
 * restart while the wallet holds the charge ([TestWalletGateway.blockCharges]). The scenario's transaction and its
 * submissions live in [FeeSteps] (injected, the same instance as the scenario's), so its fee, wallet and record steps
 * check the recovered workflow.
 *
 * After every scenario the wallet lets go of what it held and the shared endpoint is running again, so a failed scenario
 * can't leave a wallet thread waiting or the endpoint down for the rest of the run.
 */
class RecoverySteps(
    private val fee: FeeSteps,
    private val wallet: TestWalletGateway,
    private val hook: FaultInjectingStepHook,
    private val endpoint: RestateEndpointLifecycle,
) {

    private val blockedChargeKeys = mutableListOf<String>()

    /** Arms one crash, a plain exception Restate retries, at the [moment] (`before` / `after`) side of [step]. */
    @Given("the service will crash {word} the {string} step")
    fun theServiceWillCrash(moment: String, step: String) {
        val phase = FaultInjectingStepHook.Phase.entries.singleOrNull { it.name.equals(moment, ignoreCase = true) }
            ?: error("Unknown moment '$moment', expected one of ${FaultInjectingStepHook.Phase.entries.map { it.name.lowercase() }}")
        val workflowStep = WorkflowStep.entries.singleOrNull { it.name == step && it != WorkflowStep.DONE }
            ?: error("Unknown step '$step', expected one of ${WorkflowStep.entries.filter { it != WorkflowStep.DONE }}")
        hook.crashOnce(fee.submittedTransactionId(), workflowStep, phase)
    }

    /** Proves the scenario exercised a recovery: the armed crash really happened, once. */
    @Then("the service crashed once")
    fun theServiceCrashedOnce() {
        fee.responses()
        assertThat(hook.crashCount(fee.submittedTransactionId())).describedAs("injected crashes").isEqualTo(1)
    }

    /** The wallet holds every charge of the transaction until the endpoint has restarted ([theServiceEndpointRestarts]). */
    @Given("the wallet is slow to respond")
    fun theWalletIsSlowToRespond() {
        val chargeKey = chargeKey()
        wallet.blockCharges(chargeKey)
        blockedChargeKeys += chargeKey
    }

    /**
     * Stops and starts the endpoint while the workflow is inside CHARGE, then waits until Restate has run CHARGE again
     * on the restarted endpoint (it was not journaled) before the wallet answers. Both calls then finish, and the
     * wallet's idempotency key is all that keeps them to one charge.
     */
    @When("the service endpoint restarts")
    fun theServiceEndpointRestarts() {
        val chargeKey = chargeKey()
        check(chargeKey in blockedChargeKeys) { "The wallet must be slow to respond, or the restart may miss CHARGE" }
        awaitUntil("CHARGE has reached the wallet") { wallet.callCount(chargeKey) >= 1 }
        assertThat(wallet.chargeCount(chargeKey)).describedAs("charges before the restart").isZero()

        endpoint.stop()
        assertThat(endpoint.isRunning).describedAs("endpoint running after stop").isFalse()
        endpoint.start()
        assertThat(endpoint.isRunning).describedAs("endpoint running after start").isTrue()

        awaitUntil("Restate runs CHARGE again on the restarted endpoint") { wallet.callCount(chargeKey) >= 2 }
        wallet.releaseCharges(chargeKey)
    }

    @After
    fun restoreWalletAndEndpoint() {
        blockedChargeKeys.forEach(wallet::releaseCharges)
        endpoint.start()
    }

    private fun chargeKey(): String = FeeWorkflow.chargeKey(fee.submittedTransactionId())
}
