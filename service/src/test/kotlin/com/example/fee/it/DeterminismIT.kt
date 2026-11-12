package com.example.fee.it

import com.example.fee.contract.FeeRequest
import com.example.fee.record.FeeRecordStatus
import com.example.fee.validation.ValidationError
import com.example.fee.workflow.FeeOutcome
import com.example.fee.workflow.FeeWorkflow
import com.example.fee.workflow.WorkflowStep
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.web.server.LocalServerPort
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * VALIDATE reads the `Clock` inside `ctx.run`, so its result is journaled: when the clock moves between a crash
 * and Restate's retry, the replay returns the journaled result instead of judging `created_at` again.
 *
 * The clock is moved by the crash itself ([FaultInjectingStepHook.crashOnce]'s `onCrash`), so it has certainly moved
 * before the retry. A second payload checked at the moved clock shows that a re-run would have decided differently.
 */
class DeterminismIT @Autowired constructor(
    private val wallet: TestWalletGateway,
    private val hook: FaultInjectingStepHook,
    private val validator: CountingValidator,
    private val clock: TestClock,
    @LocalServerPort apiPort: Int,
) : RestateIntegrationTest() {

    private val api = FeeApi(apiPort)

    @AfterEach
    fun resetClock() {
        clock.reset()
    }

    @Test
    fun `a rejection for created_at in the future stays rejected when the clock advances past it before the retry`() {
        // given: created_at an hour after the clock, and a crash after VALIDATE that moves the clock two hours on
        val transactionId = newTransactionId()
        val validatedAt = Instant.now().truncatedTo(ChronoUnit.SECONDS)
        val createdAt = format(validatedAt.plus(Duration.ofHours(1)))
        val request = request(transactionId, createdAt)
        clock.pin(validatedAt)
        hook.crashOnce(transactionId, WorkflowStep.VALIDATE, FaultInjectingStepHook.Phase.AFTER) {
            clock.pin(validatedAt.plus(Duration.ofHours(2)))
        }

        // when
        val response = api.submit(bodyOf(request))

        // then: the crash happened and the handler ran again, with the clock already past created_at
        assertThat(hook.crashCount(transactionId)).describedAs("injected crashes").isEqualTo(1)
        assertThat(hook.callCount(transactionId, WorkflowStep.VALIDATE, FaultInjectingStepHook.Phase.BEFORE))
            .describedAs("handler runs (hook calls before VALIDATE)").isEqualTo(2)
        assertThat(clock.instant()).isAfter(validatedAt.plus(Duration.ofHours(1)))

        // then: still the rejection of the first run, naming the clock as it was then
        val expectedMessage = "Invalid created_at '$createdAt': it is in the future. Allowed: up to now, " +
            "${NOW_FORMAT.format(validatedAt.atOffset(ZoneOffset.UTC))} UTC"
        assertThat(response.status).describedAs("POST status, body %s", response.body).isEqualTo(400)
        assertThat(response.body["field"].asText()).isEqualTo("created_at")
        assertThat(response.body["message"].asText()).isEqualTo(expectedMessage)
        val outcome = StoredWorkflows.outcome(request)
        assertThat(outcome).isEqualTo(
            FeeOutcome.Rejected(
                ValidationError("created_at", createdAt, expectedMessage, ValidationError.Kind.INVALID),
            ),
        )
        assertThat(StoredWorkflows.step(request)).isEqualTo(WorkflowStep.DONE)

        // then: nothing recorded or charged
        assertThat(StoredFeeRecords.of(transactionId)).isNull()
        assertThat(validator.count(transactionId)).describedAs("VALIDATE executions that passed").isZero()
        val chargeKey = FeeWorkflow.chargeKey(transactionId)
        assertThat(wallet.callCount(chargeKey)).isZero()
        assertThat(wallet.chargeCount(chargeKey)).isZero()

        // when: another payload with the same created_at, validated at the advanced clock
        val reValidated = request.copy(amount = BigDecimal("2000"))
        val charged = api.submit(bodyOf(reValidated))

        // then: it passes, so only the journal kept the first payload rejected
        assertThat(charged.status).describedAs("POST status, body %s", charged.body).isEqualTo(200)
        assertThat(charged.body["fee"].decimalValue()).isEqualByComparingTo(BigDecimal("3.00"))
        assertThat(wallet.chargeCount(chargeKey)).isEqualTo(1)
        assertThat(StoredWorkflows.outcome(request)).isEqualTo(outcome)
    }

    @Test
    fun `a passed validation stays passed when the clock moves back before created_at before the retry`() {
        // given: created_at exactly at the clock (it passes), and a crash after VALIDATE that moves the clock an hour back
        val transactionId = newTransactionId()
        val validatedAt = Instant.now().truncatedTo(ChronoUnit.SECONDS)
        val createdAt = format(validatedAt)
        val request = request(transactionId, createdAt)
        clock.pin(validatedAt)
        hook.crashOnce(transactionId, WorkflowStep.VALIDATE, FaultInjectingStepHook.Phase.AFTER) {
            clock.pin(validatedAt.minus(Duration.ofHours(1)))
        }

        // when
        val response = api.submit(bodyOf(request))

        // then: the crash happened and the handler ran again, with created_at now in the clock's future
        assertThat(hook.crashCount(transactionId)).describedAs("injected crashes").isEqualTo(1)
        assertThat(hook.callCount(transactionId, WorkflowStep.VALIDATE, FaultInjectingStepHook.Phase.BEFORE))
            .describedAs("handler runs (hook calls before VALIDATE)").isEqualTo(2)
        assertThat(clock.instant()).isBefore(validatedAt)

        // then: the journaled pass held: VALIDATE ran once, and the payload was charged once and recorded
        assertThat(response.status).describedAs("POST status, body %s", response.body).isEqualTo(200)
        assertThat(response.body["fee"].decimalValue()).isEqualByComparingTo(BigDecimal("1.50"))
        assertThat(validator.count(transactionId)).describedAs("VALIDATE executions").isEqualTo(1)
        val chargeKey = FeeWorkflow.chargeKey(transactionId)
        assertThat(wallet.callCount(chargeKey)).describedAs("CHARGE executions").isEqualTo(1)
        assertThat(wallet.chargeCount(chargeKey)).describedAs("charges").isEqualTo(1)
        assertThat(StoredFeeRecords.of(transactionId)?.status).isEqualTo(FeeRecordStatus.COMPLETED)

        // when: another payload with the same created_at, validated at the clock that moved back
        val reValidated = request.copy(amount = BigDecimal("2000"))
        val rejected = api.submit(bodyOf(reValidated))

        // then: it is rejected as in the future, so only the journal kept the first payload valid
        assertThat(rejected.status).describedAs("POST status, body %s", rejected.body).isEqualTo(400)
        assertThat(rejected.body["field"].asText()).isEqualTo("created_at")
        assertThat(rejected.body["message"].asText()).contains("it is in the future")
        assertThat(wallet.chargeCount(chargeKey)).isEqualTo(1)
    }

    private fun newTransactionId() = "it-determinism-${UUID.randomUUID().toString().take(13)}"

    private fun format(instant: Instant): String = CREATED_AT_FORMAT.format(instant.atOffset(ZoneOffset.UTC))

    private fun request(transactionId: String, createdAt: String) = FeeRequest(
        transactionId = transactionId,
        amount = BigDecimal("1000"),
        asset = "USD",
        assetType = "FIAT",
        type = "Mobile Top Up",
        state = "SETTLED - PENDING FEE",
        createdAt = createdAt,
    )

    private fun bodyOf(request: FeeRequest): Map<String, Any?> = mapOf(
        "transaction_id" to request.transactionId,
        "amount" to request.amount,
        "asset" to request.asset,
        "asset_type" to request.assetType,
        "type" to request.type,
        "state" to request.state,
        "created_at" to request.createdAt,
    )

    private companion object {
        val CREATED_AT_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss")
        val NOW_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss.SSSSSS")
    }
}
