package com.example.fee.restate

import com.example.fee.contract.FeeRequest
import com.example.fee.contract.FeeResponse
import com.example.fee.fee.FeeQuote
import com.example.fee.record.ClaimResult
import com.example.fee.record.FeeRecords.response
import com.example.fee.restate.serde.KotlinJacksonSerdeFactory
import com.example.fee.validation.ValidationError
import com.example.fee.workflow.FeeOutcome
import com.example.fee.workflow.WorkflowStep
import dev.restate.serde.kotlinx.KotlinSerializationSerdeFactory.KtTypeTag
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import java.math.BigDecimal
import kotlin.reflect.full.createType

/**
 * Every type that crosses Restate's serde (handler input and output, journaled results, state), round-tripped through
 * the serde the handlers declare, with the type tags the Kotlin SDK uses. Equality covers `BigDecimal` scale too.
 */
class DurableTypesSerdeTest {
    private val factory = KotlinJacksonSerdeFactory()

    @ParameterizedTest(name = "{0}")
    @MethodSource("durableValues")
    fun `reads back what it journaled, unchanged`(label: String, value: Any, declaredType: Class<*>) {
        // given
        val serde = factory.create(KtTypeTag<Any>(declaredType.kotlin, declaredType.kotlin.createType()))

        // when
        val restored = serde.deserialize(serde.serialize(value))

        // then
        assertThat(restored).isEqualTo(value)
    }

    companion object {
        private val request =
            FeeRequest(
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
        fun durableValues() =
            listOf(
                Arguments.of("FeeOutcome.Charged", FeeOutcome.Charged(response), FeeOutcome::class.java),
                Arguments.of("FeeOutcome.Rejected", FeeOutcome.Rejected(error), FeeOutcome::class.java),
                Arguments.of("FeeOutcome.Conflict", FeeOutcome.Conflict("txn_001"), FeeOutcome::class.java),
                Arguments.of("ClaimResult.New", ClaimResult.New, ClaimResult::class.java),
                Arguments.of("ClaimResult.AlreadyCompleted", ClaimResult.AlreadyCompleted(response), ClaimResult::class.java),
                Arguments.of("ClaimResult.InProgress", ClaimResult.InProgress, ClaimResult::class.java),
                Arguments.of("ValidationError", error, ValidationError::class.java),
                Arguments.of("FeeRequest (the workflow input)", request, FeeRequest::class.java),
                Arguments.of("FeeRequest with missing fields", FeeRequest(transactionId = "txn_001"), FeeRequest::class.java),
                Arguments.of("FeeResponse", response, FeeResponse::class.java),
                Arguments.of("FeeQuote", FeeQuote(BigDecimal("1.50"), BigDecimal("0.0015"), "Fee"), FeeQuote::class.java),
            ) + WorkflowStep.entries.map { Arguments.of("WorkflowStep.$it", it, WorkflowStep::class.java) }
    }
}
