package com.example.fee.validation

import com.example.fee.contract.FeeRequest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.core.annotation.Order

class RequestValidatorTest {

    private val request = FeeRequest(transactionId = "txn_001")

    private val calls = mutableListOf<String>()

    private val requiredFieldsError =
        ValidationError("amount", null, "Field 'amount' is required", ValidationError.Kind.INVALID)
    private val stateError = ValidationError(
        "state",
        "PENDING",
        "Unsupported state 'PENDING'. Supported: 'SETTLED - PENDING FEE'",
        ValidationError.Kind.UNSUPPORTED,
    )

    @Test
    fun `returns null when every validator passes`() {
        // given
        val validator = RequestValidator(listOf(First(calls), Second(calls), Third(calls)))

        // when
        val error = validator.validate(request)

        // then
        assertThat(error).isNull()
        assertThat(calls).containsExactly("first", "second", "third")
    }

    @Test
    fun `returns null without validators`() {
        // given
        val validator = RequestValidator(emptyList())

        // when
        val error = validator.validate(request)

        // then
        assertThat(error).isNull()
    }

    @Test
    fun `runs validators in Order order whatever the list order`() {
        // given
        val validator = RequestValidator(listOf(Third(calls), First(calls), Second(calls)))

        // when
        validator.validate(request)

        // then
        assertThat(calls).containsExactly("first", "second", "third")
    }

    @Test
    fun `stops at the first error and skips the later validators`() {
        // given
        val validator = RequestValidator(listOf(Third(calls), Second(calls, stateError), First(calls)))

        // when
        val error = validator.validate(request)

        // then
        assertThat(error).isEqualTo(stateError)
        assertThat(calls).containsExactly("first", "second")
    }

    @Test
    fun `reports the error of the lowest Order when several validators fail`() {
        // given
        val validator = RequestValidator(
            listOf(Third(calls, stateError), Second(calls, stateError), First(calls, requiredFieldsError)),
        )

        // when
        val error = validator.validate(request)

        // then
        assertThat(error).isEqualTo(requiredFieldsError)
        assertThat(calls).containsExactly("first")
    }

    @Test
    fun `runs a validator without Order after the ordered ones`() {
        // given
        val validator = RequestValidator(listOf(Unordered(calls), Third(calls), First(calls)))

        // when
        validator.validate(request)

        // then
        assertThat(calls).containsExactly("first", "third", "unordered")
    }

    @Test
    fun `passes the request to every validator`() {
        // given
        val seen = mutableListOf<FeeRequest>()
        val validator = RequestValidator(
            listOf(
                Second(calls) { seen += it },
                First(calls) { seen += it },
            ),
        )

        // when
        validator.validate(request)

        // then
        assertThat(seen).containsExactly(request, request)
    }

    /** Mockito mocks of the interface carry no `@Order`, so the order tests use these small annotated stubs. */
    abstract class RecordingValidator(
        private val name: String,
        private val calls: MutableList<String>,
        private val result: ValidationError?,
        private val onValidate: (FeeRequest) -> Unit,
    ) : TransactionValidator {
        override fun validate(request: FeeRequest): ValidationError? {
            calls += name
            onValidate(request)
            return result
        }
    }

    @Order(ValidationOrder.REQUIRED_FIELDS)
    class First(calls: MutableList<String>, result: ValidationError? = null, onValidate: (FeeRequest) -> Unit = {}) :
        RecordingValidator("first", calls, result, onValidate)

    @Order(ValidationOrder.STATE)
    class Second(calls: MutableList<String>, result: ValidationError? = null, onValidate: (FeeRequest) -> Unit = {}) :
        RecordingValidator("second", calls, result, onValidate)

    @Order(ValidationOrder.AMOUNT_SCALE)
    class Third(calls: MutableList<String>, result: ValidationError? = null, onValidate: (FeeRequest) -> Unit = {}) :
        RecordingValidator("third", calls, result, onValidate)

    class Unordered(calls: MutableList<String>) : RecordingValidator("unordered", calls, null, {})
}
