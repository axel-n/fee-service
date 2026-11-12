package com.example.fee.restate

import com.example.fee.contract.FeeRequest
import com.example.fee.contract.FeeResponse
import com.example.fee.domain.Asset
import com.example.fee.fee.FeeQuote
import com.example.fee.record.ClaimResult
import com.example.fee.record.FeeRecord
import com.example.fee.record.FeeRecords.HASH
import com.example.fee.record.FeeRecords.response
import com.example.fee.validation.ValidationError
import com.example.fee.wallet.ChargeReceipt
import com.example.fee.workflow.FeeOutcome
import com.example.fee.workflow.FeeWorkflowInput
import com.example.fee.workflow.WorkflowStep
import dev.restate.serde.jackson.JacksonSerdeFactory
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import java.math.BigDecimal

/**
 * Every type that crosses Restate's serde (handler input and output, `ctx.run` results, VO state), round-tripped through
 * the serde the generated code uses (D35), declared as the handlers declare it. Equality covers `BigDecimal` scale too.
 */
class DurableTypesSerdeTest {

    private val factory = JacksonSerdeFactory()

    @ParameterizedTest(name = "{0}")
    @MethodSource("durableValues")
    fun `reads back what it journaled, unchanged`(label: String, value: Any, declaredType: Class<*>) {
        // given
        @Suppress("UNCHECKED_CAST")
        val serde = factory.create(declaredType as Class<Any>)

        // when
        val restored = serde.deserialize(serde.serialize(value))

        // then
        assertThat(restored).isEqualTo(value)
    }

    companion object {
        private val request = FeeRequest(
            transactionId = "txn_001",
            amount = BigDecimal("1000.50"),
            asset = "EUR",
            assetType = "FIAT",
            type = "Bank Transfer",
            state = "SETTLED - PENDING FEE",
            createdAt = "2023-08-30 15:42:17.610059",
        )
        private val error = ValidationError("amount", null, "Field 'amount' is required", ValidationError.Kind.INVALID)

        @JvmStatic
        fun durableValues() = listOf(
            Arguments.of("FeeWorkflowInput", FeeWorkflowInput(request, HASH), FeeWorkflowInput::class.java),
            Arguments.of("FeeOutcome.Charged", FeeOutcome.Charged(response), FeeOutcome::class.java),
            Arguments.of("FeeOutcome.Rejected", FeeOutcome.Rejected(error), FeeOutcome::class.java),
            Arguments.of("FeeOutcome.Conflict", FeeOutcome.Conflict("txn_001"), FeeOutcome::class.java),
            Arguments.of("FeeOutcome.Failed", FeeOutcome.Failed("Charge declined"), FeeOutcome::class.java),
            Arguments.of("ClaimResult.New", ClaimResult.New, ClaimResult::class.java),
            Arguments.of("ClaimResult.AlreadyCompleted", ClaimResult.AlreadyCompleted(response), ClaimResult::class.java),
            Arguments.of("ClaimResult.InProgress", ClaimResult.InProgress, ClaimResult::class.java),
            Arguments.of("ClaimResult.Conflict", ClaimResult.Conflict, ClaimResult::class.java),
            Arguments.of("FeeRecord IN_PROGRESS", FeeRecord.inProgress(HASH), FeeRecord::class.java),
            Arguments.of("FeeRecord COMPLETED", FeeRecord.inProgress(HASH).complete(response), FeeRecord::class.java),
            Arguments.of("FeeRecord FAILED", FeeRecord.inProgress(HASH).fail("Charge declined"), FeeRecord::class.java),
            Arguments.of("ValidationError", error, ValidationError::class.java),
            Arguments.of("FeeRequest", request, FeeRequest::class.java),
            Arguments.of("FeeResponse", response, FeeResponse::class.java),
            Arguments.of("FeeQuote", FeeQuote(BigDecimal("1.50"), BigDecimal("0.0015"), "Fee"), FeeQuote::class.java),
            Arguments.of("ChargeReceipt", ChargeReceipt("c-1", "fee-txn_001", BigDecimal("1.50"), Asset.USD), ChargeReceipt::class.java),
        ) + WorkflowStep.entries.map { Arguments.of("WorkflowStep.$it", it, WorkflowStep::class.java) }
    }
}
