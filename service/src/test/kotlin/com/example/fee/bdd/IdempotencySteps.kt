package com.example.fee.bdd

import com.example.fee.it.FeeApi
import io.cucumber.java.en.Then
import io.cucumber.java.en.When
import org.assertj.core.api.Assertions.assertThat
import org.springframework.boot.test.web.server.LocalServerPort
import java.math.BigDecimal
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * The steps of duplicate submissions: the same transaction sent again, and sent by several callers at once. The
 * scenario's request and its submissions live in [FeeSteps] (injected, the same instance as the scenario's), so the
 * fee, charge and record steps there check these submissions too.
 */
class IdempotencySteps(
    private val fee: FeeSteps,
    @LocalServerPort apiPort: Int,
) {
    private val api = FeeApi(apiPort)

    // The bodies the concurrent callers sent, each with its submission
    private var concurrentCalls: List<Pair<Map<String, Any?>, CompletableFuture<FeeApi.Response>>> = emptyList()

    /** Waits for every earlier response first, so this is a resubmission, not a concurrent one. */
    @When("the same transaction is submitted to {string} again")
    fun theSameTransactionIsSubmittedAgain(path: String) {
        fee.responses()
        fee.theTransactionIsSubmittedTo(path)
    }

    /** [callers] threads post the same request, released together by one gate so the requests reach the app at once. */
    @When("{int} callers submit the transaction to {string} at the same time")
    fun callersSubmitAtTheSameTime(callers: Int, path: String) {
        val request = fee.request()
        submitTogether(path, List(callers) { request })
    }

    /** Like [callersSubmitAtTheSameTime], but caller `i` adds `i` to the amount: one transaction, different payloads. */
    @When("{int} callers submit the transaction to {string} at the same time, each with its own amount")
    fun callersSubmitWithTheirOwnAmount(callers: Int, path: String) {
        val request = fee.request()
        val amount = request["amount"] as? BigDecimal ?: error("The transaction has no amount to vary: $request")
        submitTogether(path, List(callers) { request + ("amount" to amount + BigDecimal(it)) })
    }

    @Then("the response is the same as the first one")
    fun theResponseIsTheSameAsTheFirstOne() {
        val first = fee.responses().first()
        val latest = fee.response()
        assertThat(first.status).describedAs("first response status, body %s", first.body).isEqualTo(200)
        assertThat(latest.status).describedAs("latest response status, body %s", latest.body).isEqualTo(200)
        assertThat(latest.body).isEqualTo(first.body)
    }

    @Then("every caller gets status {int} and the same response")
    fun everyCallerGetsTheSameResponse(status: Int) {
        val responses = callerResponses()
        responses.forEach {
            assertThat(it.status).describedAs("response status, body %s", it.body).isEqualTo(status)
        }
        assertThat(responses.map { it.body }.distinct()).describedAs("distinct response bodies").hasSize(1)
    }

    /**
     * The one response every caller got charges the amount one of them sent: the payload that reached Restate first.
     */
    @Then("the response is the charge of one caller's amount")
    fun theResponseIsTheChargeOfOneCallersAmount() {
        val charged = callerResponses().first().body["amount"].decimalValue()
        val sent = concurrentCalls.map { (body, _) -> body["amount"] as BigDecimal }
        assertThat(sent.filter { it.compareTo(charged) == 0 })
            .describedAs("amounts sent %s that the charged amount %s matches", sent, charged)
            .hasSize(1)
    }

    private fun callerResponses(): List<FeeApi.Response> {
        check(concurrentCalls.isNotEmpty()) { "No callers submitted the transaction at the same time" }
        fee.responses()
        return concurrentCalls.map { (_, submission) -> submission.join() }
    }

    // Each body on its own thread, all waiting on one gate. The pool is shut down once they are on their way, which
    // lets them finish; the gate opens in any case, so a failed start can't leave a thread waiting on it
    private fun submitTogether(path: String, bodies: List<Map<String, Any?>>) {
        val executor = Executors.newFixedThreadPool(bodies.size)
        val gate = CountDownLatch(1)
        val ready = CountDownLatch(bodies.size)
        try {
            concurrentCalls =
                bodies.map { body ->
                    body to
                        CompletableFuture.supplyAsync({
                            ready.countDown()
                            gate.await()
                            api.submit(body, path)
                        }, executor)
                }
            concurrentCalls.forEach { (_, submission) -> fee.submitted(submission) }
            check(ready.await(READY_TIMEOUT_SECONDS, TimeUnit.SECONDS)) { "The callers did not start in time" }
        } finally {
            gate.countDown()
            executor.shutdown()
        }
    }

    private companion object {
        const val READY_TIMEOUT_SECONDS = 30L
    }
}
