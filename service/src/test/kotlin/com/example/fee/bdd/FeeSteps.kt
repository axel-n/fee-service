package com.example.fee.bdd

import com.example.fee.domain.CreatedAtFormat
import com.example.fee.it.FeeApi
import com.example.fee.it.StoredFeeRecords
import com.example.fee.it.TestClock
import com.example.fee.it.TestWalletGateway
import com.example.fee.record.FeeRecordStatus
import com.example.fee.workflow.FeeWorkflow
import io.cucumber.datatable.DataTable
import io.cucumber.java.After
import io.cucumber.java.en.Given
import io.cucumber.java.en.Then
import io.cucumber.java.en.When
import org.assertj.core.api.Assertions.assertThat
import org.springframework.boot.test.web.server.LocalServerPort
import java.math.BigDecimal
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/**
 * The steps of a fee request: build it, submit it to the app, check the response, the wallet and the record in Restate.
 *
 * A new instance per scenario (Cucumber's glue scope), so its fields are the scenario's state. Transaction ids written in
 * a feature are scoped to the scenario: `txn_001` is sent as `txn_001-<suffix>`, one random suffix per scenario, so
 * scenarios never share a fee record or a wallet charge, and every mention of `txn_001` in the scenario means the same
 * transaction (D69).
 *
 * A submission runs in the background and the `Then` steps wait for its response, so a scenario can act while the
 * request is still in flight (a restart, a concurrent submission). Every submission of the scenario is kept: the
 * response steps check the latest one, the wallet and record steps the one that was charged ([chargedResponse]).
 *
 * A scenario may pin the app's clock ([itIs]); it is back on the system clock after every scenario.
 */
class FeeSteps(
    private val wallet: TestWalletGateway,
    private val clock: TestClock,
    @LocalServerPort apiPort: Int,
) {

    private val api = FeeApi(apiPort)
    private val scenarioSuffix = UUID.randomUUID().toString().take(8)

    private var request: Map<String, Any?>? = null

    // The id the scenario's transaction was given with, scoped; it still names the transaction once the request no
    // longer carries it (a missing transaction_id)
    private var givenTransactionId: String? = null
    private val submissions = mutableListOf<CompletableFuture<FeeApi.Response>>()

    /** The id sent for [featureId], the id the feature names. */
    fun transactionId(featureId: String): String = "$featureId-$scenarioSuffix"

    @Given("a transaction request:")
    fun aTransactionRequest(fields: DataTable) {
        val given = fields.asMap().mapValues { (field, value) -> requestValue(field, value) }
        request = given
        givenTransactionId = given["transaction_id"] as? String
    }

    @Given("a {string} transaction {string} of {bigdecimal} {string} {string} in state {string}")
    fun aTransaction(type: String, featureId: String, amount: BigDecimal, asset: String, assetType: String, state: String) {
        request = mapOf(
            "transaction_id" to transactionId(featureId),
            "amount" to amount,
            "asset" to asset,
            "asset_type" to assetType,
            "type" to type,
            "state" to state,
            "created_at" to SAMPLE_CREATED_AT,
        )
        givenTransactionId = transactionId(featureId)
    }

    /** Changes one field of the scenario's request, e.g. to send the transaction again in a corrected state. */
    @When("the transaction's {string} is changed to {string}")
    fun theTransactionFieldIsChangedTo(field: String, value: String) {
        val current = checkNotNull(request) { "No transaction was given before it was changed" }
        request = current + (field to requestValue(field, value))
    }

    /** Leaves [field] out of the scenario's request altogether, as a client that never sent it. */
    @When("the transaction's {string} is missing")
    fun theTransactionFieldIsMissing(field: String) {
        val current = checkNotNull(request) { "No transaction was given before a field was removed" }
        check(field in current) { "The transaction has no field '$field' to remove" }
        request = current - field
    }

    /**
     * Sets [field] to the text [value] exactly as written: not scoped to the scenario like a transaction id, not turned
     * into a number like an amount. For values that only make sense verbatim, such as a malformed transaction id.
     */
    @When("the transaction's {string} is sent exactly as {string}")
    fun theTransactionFieldIsSentExactlyAs(field: String, value: String) {
        val current = checkNotNull(request) { "No transaction was given before it was changed" }
        request = current + (field to value)
    }

    /**
     * Pins the app's clock to [now], a UTC time in the `created_at` format, so a message that names "now" is exact. It
     * is the clock VALIDATE reads inside the workflow; [resetClock] unpins it after the scenario.
     */
    @Given("it is {string} UTC")
    fun itIs(now: String) {
        val time = checkNotNull(CreatedAtFormat.parseOrNull(now)) { "'$now' is not in the created_at format" }
        clock.pin(time.toInstant(ZoneOffset.UTC))
    }

    @After
    fun resetClock() {
        clock.reset()
    }

    @When("the transaction is submitted to {string}")
    fun theTransactionIsSubmittedTo(path: String) {
        val body = request()
        submitted(CompletableFuture.supplyAsync { api.submit(body, path) })
    }

    /** The scenario's request as it would be sent now. */
    fun request(): Map<String, Any?> = checkNotNull(request) { "No transaction was given before it was submitted" }

    /** Adds a submission made by another step class (e.g. concurrent callers) to the scenario's submissions. */
    fun submitted(submission: CompletableFuture<FeeApi.Response>) {
        submissions += submission
    }

    @Then("the response status is {int}")
    fun theResponseStatusIs(status: Int) {
        val response = response()
        assertThat(response.status).describedAs("response status, body %s", response.body).isEqualTo(status)
    }

    /**
     * The response is the problem of a rejected field (D60): [field], the rejected [value] as sent, and the [message]
     * exactly, also as the problem's `detail`.
     */
    @Then("the request is rejected on field {string} with value {string} and message {string}")
    fun theRequestIsRejectedOn(field: String, value: String, message: String) {
        val problem = response().body
        assertThat(problem["field"]?.asText()).describedAs("field of %s", problem).isEqualTo(field)
        assertThat(problem["value"]?.asText()).describedAs("value of %s", problem).isEqualTo(value)
        assertThat(problem["message"]?.asText()).describedAs("message of %s", problem).isEqualTo(message)
        assertThat(problem["detail"]?.asText()).describedAs("detail of %s", problem).isEqualTo(message)
    }

    /** The response is the problem of a missing [field] (D60): its `value` is `null`, and the [message] is exact. */
    @Then("the request is rejected on missing field {string} with message {string}")
    fun theRequestIsRejectedOnMissingField(field: String, message: String) {
        val problem = response().body
        assertThat(problem["field"]?.asText()).describedAs("field of %s", problem).isEqualTo(field)
        assertThat(problem.has("value")).describedAs("value present in %s", problem).isTrue()
        assertThat(problem["value"].isNull).describedAs("value of %s is null", problem).isTrue()
        assertThat(problem["message"]?.asText()).describedAs("message of %s", problem).isEqualTo(message)
        assertThat(problem["detail"]?.asText()).describedAs("detail of %s", problem).isEqualTo(message)
    }

    /**
     * The response body has exactly the table's fields. Numbers compare by value (`1.5` equals `1.50`), texts exactly;
     * `transaction_id` is the feature's id, scoped like every other.
     */
    @Then("the response body is:")
    fun theResponseBodyIs(expected: DataTable) {
        val body = response().body
        val fields = expected.asMap()
        assertThat(body.fieldNames().asSequence().toList()).containsExactlyInAnyOrderElementsOf(fields.keys)
        fields.forEach { (field, value) ->
            val actual = body[field]
            when {
                field == "transaction_id" -> assertThat(actual.asText()).isEqualTo(value?.let(::transactionId))
                actual.isNumber -> assertThat(actual.decimalValue()).describedAs(field).isEqualByComparingTo(value)
                else -> assertThat(actual.asText()).describedAs(field).isEqualTo(value)
            }
        }
    }

    @Then("the fee is {bigdecimal} with rate {bigdecimal} and description {string}")
    fun theFeeIs(fee: BigDecimal, rate: BigDecimal, description: String) {
        theFeeIs(fee)
        val body = response().body
        assertThat(body["rate"].decimalValue()).isEqualByComparingTo(rate)
        assertThat(body["description"].asText()).isEqualTo(description)
    }

    @Then("the fee is {bigdecimal}")
    fun theFeeIs(fee: BigDecimal) {
        val response = response()
        assertThat(response.status).describedAs("response status, body %s", response.body).isEqualTo(200)
        assertThat(response.body["fee"].decimalValue()).isEqualByComparingTo(fee)
    }

    /** Counted at the wallet, not read from the response, and charged with the fee of the charged response. */
    @Then("the wallet was charged exactly once")
    fun theWalletWasChargedExactlyOnce() {
        val chargeKey = FeeWorkflow.chargeKey(submittedTransactionId())
        assertThat(wallet.chargeCount(chargeKey)).isEqualTo(1)
        assertThat(wallet.receipt(chargeKey)?.amount)
            .isEqualByComparingTo(chargedResponse().body["fee"].decimalValue())
    }

    /** Neither charged nor even attempted: the wallet never saw the transaction's charge key. */
    @Then("the wallet was not charged")
    fun theWalletWasNotCharged() {
        val chargeKey = FeeWorkflow.chargeKey(submittedTransactionId())
        assertThat(wallet.callCount(chargeKey)).isZero()
        assertThat(wallet.chargeCount(chargeKey)).isZero()
    }

    /** Read from Restate itself, once the response is in: no fee record, so the `transaction_id` is not used up (D8). */
    @Then("no fee is recorded")
    fun noFeeIsRecorded() {
        response()
        assertThat(StoredFeeRecords.of(submittedTransactionId())).isNull()
    }

    /** Read from Restate itself, past the app's API: the fee and the amount of the charged response. */
    @Then("the fee is recorded as completed")
    fun theFeeIsRecordedAsCompleted() {
        val record = StoredFeeRecords.of(submittedTransactionId())
        val charged = chargedResponse().body
        assertThat(record?.status).isEqualTo(FeeRecordStatus.COMPLETED)
        assertThat(record?.response?.fee).isEqualByComparingTo(charged["fee"].decimalValue())
        assertThat(record?.response?.amount).isEqualByComparingTo(charged["amount"].decimalValue())
    }

    /** The response of the scenario's latest submission, waiting for it if it is still in flight. */
    fun response(): FeeApi.Response = await(checkNotNull(submissions.lastOrNull()) { "No transaction was submitted" })

    /** The responses of every submission of the scenario in the order they were made, waiting for those in flight. */
    fun responses(): List<FeeApi.Response> = submissions.map(::await)

    /**
     * The latest response with a fee (status 200) among the scenario's submissions: the charge that a later rejection
     * or conflict left standing. A scenario that expects one charged payload among several asserts that itself.
     */
    fun chargedResponse(): FeeApi.Response {
        val responses = responses()
        return checkNotNull(responses.lastOrNull { it.status == 200 }) {
            "No submission of the scenario was charged: ${responses.map { it.status to it.body }}"
        }
    }

    /** The id the request carried, verbatim; for a request sent without one, the id its transaction was given with. */
    fun submittedTransactionId(): String =
        checkNotNull(request?.get("transaction_id") as? String ?: givenTransactionId) {
            "No transaction with an id was given"
        }

    private fun await(submission: CompletableFuture<FeeApi.Response>): FeeApi.Response =
        submission.get(RESPONSE_TIMEOUT_SECONDS, TimeUnit.SECONDS)

    // A feature's value as the request carries it: the id scoped to the scenario, the amount as a number
    private fun requestValue(field: String, value: String?): Any? = when (field) {
        "transaction_id" -> value?.let(::transactionId)
        "amount" -> value?.let(::BigDecimal)
        else -> value
    }

    private companion object {
        // the created_at of the sample in requirements.md
        const val SAMPLE_CREATED_AT = "2023-08-30 15:42:17.610059"

        // above FeeApi's own 60 s, so a slow request fails there with its cause
        const val RESPONSE_TIMEOUT_SECONDS = 90L
    }
}
