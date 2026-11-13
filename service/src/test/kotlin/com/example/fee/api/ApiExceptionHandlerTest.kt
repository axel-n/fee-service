package com.example.fee.api

import com.example.fee.application.FeeInProgressException
import com.example.fee.application.FeeService
import com.example.fee.contract.FeeRequest
import com.example.fee.contract.error.DuplicateTransactionException
import com.example.fee.contract.error.FeeException
import com.example.fee.contract.error.FeeProblem
import com.example.fee.contract.error.ValidationException
import com.example.fee.contract.error.WorkflowUnavailableException
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.hamcrest.Matchers.nullValue
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.`when`
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActions
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.net.ConnectException

@WebMvcTest(TransactionFeeController::class)
class ApiExceptionHandlerTest(
    @Autowired private val mockMvc: MockMvc,
) {
    @MockitoBean
    private lateinit var feeService: FeeService

    private val requestJson =
        """
        {
          "transaction_id": "txn_001",
          "amount": 1000,
          "asset": "USD",
          "asset_type": "FIAT",
          "type": "Mobile Top Up",
          "state": "PENDING",
          "created_at": "2023-08-30 15:42:17.610059"
        }
        """.trimIndent()

    private fun postFee(body: String = requestJson): ResultActions =
        mockMvc.performToCompletion(post("/transaction/fee").contentType(MediaType.APPLICATION_JSON).content(body))

    private fun submitThrows(exception: RuntimeException) {
        runBlocking { doThrow(exception).`when`(feeService).submit(any(FeeRequest::class.java) ?: FeeRequest()) }
    }

    @Test
    fun `a malformed request is 400 problem json with the field, the value and the message`() {
        // given
        val message = "Amount -5 must be greater than 0"
        submitThrows(ValidationException("amount", "-5", message, ValidationException.Kind.INVALID))

        // when
        val result = postFee()

        // then
        result
            .andExpect(status().isBadRequest)
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
            // left out: RFC 7807 reads a missing type as about:blank, and Spring writes it only when it is something else
            .andExpect(jsonPath("$.type").doesNotExist())
            .andExpect(jsonPath("$.title").value("Bad Request"))
            .andExpect(jsonPath("$.status").value(400))
            .andExpect(jsonPath("$.detail").value(message))
            .andExpect(jsonPath("$.instance").value("/transaction/fee"))
            .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
            .andExpect(jsonPath("$.field").value("amount"))
            .andExpect(jsonPath("$.value").value("-5"))
            .andExpect(jsonPath("$.message").value(message))
    }

    @Test
    fun `a missing field is 400 with value null`() {
        // given
        val message = "Field 'asset' is required"
        submitThrows(ValidationException("asset", null, message, ValidationException.Kind.INVALID))

        // when
        val result = postFee()

        // then
        result
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.field").value("asset"))
            .andExpect(jsonPath("$.value").value(nullValue()))
            .andExpect(jsonPath("$.message").value(message))
        assertThat(result.andReturn().response.contentAsString).contains("\"value\":null")
    }

    @Test
    fun `an unsupported value is 422 with a message naming the allowed values`() {
        // given
        val message = "Unsupported state 'PENDING'. Supported: 'SETTLED - PENDING FEE'"
        submitThrows(ValidationException("state", "PENDING", message, ValidationException.Kind.UNSUPPORTED))

        // when
        val result = postFee()

        // then
        result
            .andExpect(status().isUnprocessableContent)
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
            .andExpect(jsonPath("$.title").value("Unprocessable Content"))
            .andExpect(jsonPath("$.status").value(422))
            .andExpect(jsonPath("$.code").value("UNSUPPORTED_REQUEST"))
            .andExpect(jsonPath("$.field").value("state"))
            .andExpect(jsonPath("$.value").value("PENDING"))
            .andExpect(jsonPath("$.message").value(message))
            .andExpect(jsonPath("$.detail").value(message))
    }

    @Test
    fun `a transaction id claimed by another payload is 409 with the transaction id`() {
        // given
        submitThrows(DuplicateTransactionException("txn_001"))

        // when
        val result = postFee()

        // then
        result
            .andExpect(status().isConflict)
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
            .andExpect(jsonPath("$.title").value("Conflict"))
            .andExpect(jsonPath("$.status").value(409))
            .andExpect(jsonPath("$.code").value("DUPLICATE_TRANSACTION"))
            .andExpect(jsonPath("$.transaction_id").value("txn_001"))
            .andExpect(jsonPath("$.detail").value(DuplicateTransactionException("txn_001").message))
            .andExpect(jsonPath("$.field").doesNotExist())
    }

    @Test
    fun `an unavailable Restate is 503 asking for a retry without leaking the cause`() {
        // given
        submitThrows(WorkflowUnavailableException(ConnectException("Connection refused: restate:8080")))

        // when
        val result = postFee()

        // then
        result
            .andExpect(status().isServiceUnavailable)
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
            .andExpect(jsonPath("$.title").value("Service Unavailable"))
            .andExpect(jsonPath("$.status").value(503))
            .andExpect(jsonPath("$.code").value("WORKFLOW_UNAVAILABLE"))
            .andExpect(jsonPath("$.detail").value("Fee processing is temporarily unavailable. Retry the same request later"))
        assertThat(result.andReturn().response.contentAsString).doesNotContain("Connection refused", "restate:8080")
    }

    @Test
    fun `a fee still processing is 202 with an in progress body, not a problem`() {
        // given
        submitThrows(FeeInProgressException("txn_001"))

        // when
        val result = postFee()

        // then
        result
            .andExpect(status().isAccepted)
            .andExpect(content().contentType(MediaType.APPLICATION_JSON))
            .andExpect(header().doesNotExist(HttpHeaders.LOCATION))
            .andExpect(jsonPath("$.transaction_id").value("txn_001"))
            .andExpect(jsonPath("$.status").value("IN_PROGRESS"))
            .andExpect(jsonPath("$.fee").doesNotExist())
            .andExpect(jsonPath("$.code").doesNotExist())
    }

    @Test
    fun `an amount that isn't a number is 400 naming the field and the value`() {
        // given
        val body = requestJson.replace("\"amount\": 1000", "\"amount\": \"abc\"")

        // when
        val result = postFee(body)

        // then
        result
            .andExpect(status().isBadRequest)
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
            .andExpect(jsonPath("$.status").value(400))
            .andExpect(jsonPath("$.field").value("amount"))
            .andExpect(jsonPath("$.value").value("abc"))
            .andExpect(jsonPath("$.message").value("Field 'amount' has an invalid value 'abc'. Expected a number"))
        verifyNoInteractions(feeService)
    }

    @Test
    fun `a field of the wrong json type is 400 naming the field`() {
        // given
        val body = requestJson.replace("\"amount\": 1000", "\"amount\": [1000]")

        // when
        val result = postFee(body)

        // then
        result
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.field").value("amount"))
            .andExpect(jsonPath("$.message").value("Field 'amount' has a value of the wrong JSON type"))
        verifyNoInteractions(feeService)
    }

    @Test
    fun `a body that isn't json is 400 problem json with a readable message`() {
        // given
        val body = "{\"transaction_id\": \"txn_001\","

        // when
        val result = postFee(body)

        // then
        result
            .andExpect(status().isBadRequest)
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
            .andExpect(jsonPath("$.message").value("The request body is not valid JSON"))
            .andExpect(jsonPath("$.field").doesNotExist())
            .andExpect(jsonPath("$.code").doesNotExist())
        verifyNoInteractions(feeService)
    }

    @Test
    fun `a missing body is 400 problem json`() {
        // given
        val request = post("/transaction/fee").contentType(MediaType.APPLICATION_JSON)

        // when
        val result = mockMvc.performToCompletion(request)

        // then
        result
            .andExpect(status().isBadRequest)
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
            .andExpect(jsonPath("$.message").value("The request body is missing or can't be read"))
        verifyNoInteractions(feeService)
    }

    @Test
    fun `a body that isn't json content is 415 problem json`() {
        // given
        val request = post("/transaction/fee").contentType(MediaType.TEXT_PLAIN).content("txn_001")

        // when
        val result = mockMvc.performToCompletion(request)

        // then
        result
            .andExpect(status().isUnsupportedMediaType)
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
            .andExpect(jsonPath("$.status").value(415))
            .andExpect(jsonPath("$.code").doesNotExist())
        verifyNoInteractions(feeService)
    }

    @Test
    fun `a client reading the body as FeeProblem gets back the exception the service raised`() {
        // given
        val raised: List<FeeException> =
            listOf(
                ValidationException("amount", "-5", "Amount -5 must be greater than 0", ValidationException.Kind.INVALID),
                ValidationException("asset", null, "Field 'asset' is required", ValidationException.Kind.INVALID),
                ValidationException("state", "PENDING", "Unsupported state 'PENDING'", ValidationException.Kind.UNSUPPORTED),
                DuplicateTransactionException("txn_001"),
                WorkflowUnavailableException(ConnectException("Connection refused")),
            )
        val mapper = jacksonObjectMapper()

        // when
        val received =
            raised.map { exception ->
                submitThrows(exception)
                val body = postFee().andReturn().response.contentAsString
                mapper.readValue(body, FeeProblem::class.java).toException()
            }

        // then
        assertThat(received).hasSameSizeAs(raised)
        raised.zip(received).forEach { (sent, got) ->
            assertThat(got).isNotNull.isExactlyInstanceOf(sent.javaClass)
            assertThat(got?.message).isEqualTo(sent.message)
            assertThat(got?.code).isEqualTo(sent.code)
            assertThat(got?.let(::properties)).isEqualTo(properties(sent))
        }
    }

    private fun properties(exception: FeeException): List<Any?> =
        when (exception) {
            is ValidationException -> listOf(exception.field, exception.value, exception.kind)
            is DuplicateTransactionException -> listOf(exception.transactionId)
            is WorkflowUnavailableException -> emptyList()
        }
}
