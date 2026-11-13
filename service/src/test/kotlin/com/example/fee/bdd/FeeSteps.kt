package com.example.fee.bdd

import com.example.fee.domain.CreatedAtFormat
import com.example.fee.it.CountingFeeCalculator
import com.example.fee.it.FaultInjectingFeeRecordRepository
import com.example.fee.it.FeeApi
import com.example.fee.it.StoredFeeRecords
import com.example.fee.it.StoredWorkflows
import com.example.fee.it.TestClock
import com.example.fee.record.FeeRecordStatus
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
 * The steps of a fee request: build it, submit it to the app, check the response, the charge and the record in Postgres.
 *
 * A new instance per scenario (Cucumber's glue scope), so its fields are the scenario's state. Transaction ids written in
 * a feature are scoped to the scenario: `txn_001` is sent as `txn_001-<suffix>`, one random suffix per scenario, so
 * scenarios never share a fee record, and every mention of `txn_001` in the scenario means the same
 * transaction.
 *
 * A submission runs in the background and the `Then` steps wait for its response, so a scenario can act while the
 * request is still in flight (a restart, a concurrent submission). Every submission of the scenario is kept: the
 * response steps check the latest one, the charge and record steps the one that was charged ([chargedResponse]).
 *
 * A scenario may pin the app's clock ([itIs]); it is back on the system clock after every scenario.
 */
class FeeSteps(
    private val calculator: CountingFeeCalculator,
    private val records: FaultInjectingFeeRecordRepository,
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
        // Cucumber types every cell as nullable; an empty field name is a mistake in the feature
        val given =
            fields.asMap().entries.associate { (field, value) ->
                val name = checkNotNull(field) { "A request field in the table has no name" }
                name to requestValue(name, value)
            }
        request = given
        givenTransactionId = given["transaction_id"] as? String
    }

    @Given("a {string} transaction {string} of {bigdecimal} {string} {string} in state {string}")
    fun aTransaction(type: String, featureId: String, amount: BigDecimal, asset: String, assetType: String, state: String) {
        request =
            mapOf(
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
     * is the clock the gateway and the workflow's VALIDATE read; [resetClock] unpins it after the scenario.
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
     * The response is the problem of a rejected field: [field], the rejected [value] as sent, and the [message]
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

    /** The response is the problem of a missing [field]: its `value` is `null`, and the [message] is exact. */
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

    /**
     * Counted at CALCULATE, not read from the response: one workflow calculated the fee, and the fee recorded in Postgres
     * is the fee of the charged response.
     */
    @Then("the fee was charged exactly once")
    fun theFeeWasChargedExactlyOnce() {
        val transactionId = submittedTransactionId()
        assertThat(calculator.count(transactionId)).describedAs("CALCULATE executions").isEqualTo(1)
        assertThat(StoredFeeRecords.of(transactionId)?.response?.fee)
            .isEqualByComparingTo(chargedResponse().body["fee"].decimalValue())
    }

    /** Not even calculated: no workflow of the transaction got past validation. */
    @Then("the fee was not charged")
    fun theFeeWasNotCharged() {
        assertThat(calculator.count(submittedTransactionId())).describedAs("CALCULATE executions").isZero()
    }

    /** Read from Postgres itself, once the response is in: no fee record, so the `transaction_id` is not used up. */
    @Then("no fee is recorded")
    fun noFeeIsRecorded() {
        response()
        assertThat(StoredFeeRecords.of(submittedTransactionId())).isNull()
    }

    /**
     * Checked past the app, once the response is in: Restate never started a workflow for the transaction, and the app
     * never asked Postgres about it. The app answered on its own.
     */
    @Then("the transaction never reached Restate or Postgres")
    fun theTransactionNeverReachedRestateOrPostgres() {
        response()
        val transactionId = submittedTransactionId()
        assertThat(StoredWorkflows.step(transactionId)).describedAs("workflow step of %s", transactionId).isNull()
        assertThat(records.callCount(transactionId)).describedAs("Postgres calls about %s", transactionId).isZero()
    }

    /** Read from Postgres itself, past the app's API: one record, with the fee and the amount of the charged response. */
    @Then("the fee is recorded as completed")
    fun theFeeIsRecordedAsCompleted() {
        assertThat(StoredFeeRecords.count(submittedTransactionId())).describedAs("fee records").isEqualTo(1)
        val record = StoredFeeRecords.of(submittedTransactionId())
        val charged = chargedResponse().body
        assertThat(record?.status).isEqualTo(FeeRecordStatus.COMPLETED)
        assertThat(record?.response?.fee).isEqualByComparingTo(charged["fee"].decimalValue())
        assertThat(record?.response?.amount).isEqualByComparingTo(charged["amount"].decimalValue())
    }

    /** Read from Postgres itself: the record holds every field of the request as sent, and when it was claimed and charged. */
    @Then("the request is stored with the fee record")
    fun theRequestIsStoredWithTheFeeRecord() {
        val sent = checkNotNull(request) { "No transaction request was given" }
        val stored = checkNotNull(StoredFeeRecords.transactionOf(submittedTransactionId())) { "No fee record" }
        assertThat(stored.amount).isEqualByComparingTo(sent["amount"] as BigDecimal)
        assertThat(stored.asset.value).isEqualTo(sent["asset"])
        assertThat(stored.assetType.value).isEqualTo(sent["asset_type"])
        assertThat(stored.type.value).isEqualTo(sent["type"])
        assertThat(stored.state.value).isEqualTo(sent["state"])
        assertThat(stored.createdAt).isEqualTo(CreatedAtFormat.parseOrNull(sent["created_at"] as String))
        val times = checkNotNull(StoredFeeRecords.timesOf(submittedTransactionId()))
        assertThat(times.chargedAt).isNotNull().isAfterOrEqualTo(times.claimedAt)
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
    private fun requestValue(field: String, value: String?): Any? =
        when (field) {
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
