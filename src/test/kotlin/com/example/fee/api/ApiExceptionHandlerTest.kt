package com.example.fee.api

import com.example.fee.application.ChargeFailedException
import com.example.fee.application.DuplicateTransactionException
import com.example.fee.application.FeeService
import com.example.fee.application.TransactionNotFoundException
import com.example.fee.application.ValidationException
import com.example.fee.application.WorkflowUnavailableException
import com.example.fee.contract.FeeRequest
import com.example.fee.validation.ValidationError
import org.assertj.core.api.Assertions.assertThat
import org.hamcrest.Matchers.nullValue
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.`when`
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.http.MediaType
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActions
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.net.ConnectException

@WebMvcTest(TransactionFeeController::class)
class ApiExceptionHandlerTest(@Autowired private val mockMvc: MockMvc) {

    @MockitoBean
    private lateinit var feeService: FeeService

    private val requestJson = """
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
        mockMvc.perform(post("/transaction/fee").contentType(MediaType.APPLICATION_JSON).content(body))

    private fun submitThrows(exception: RuntimeException) {
        `when`(feeService.submit(any(FeeRequest::class.java) ?: FeeRequest())).thenThrow(exception)
    }

    @Test
    fun `a malformed request is 400 problem json with the field, the value and the message`() {
        // given
        val message = "Amount -5 must be greater than 0"
        submitThrows(ValidationException(ValidationError("amount", "-5", message, ValidationError.Kind.INVALID)))

        // when
        val result = postFee()

        // then
        result
            .andExpect(status().isBadRequest)
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
            .andExpect(jsonPath("$.type").value("about:blank"))
            .andExpect(jsonPath("$.title").value("Bad Request"))
            .andExpect(jsonPath("$.status").value(400))
            .andExpect(jsonPath("$.detail").value(message))
            .andExpect(jsonPath("$.instance").value("/transaction/fee"))
            .andExpect(jsonPath("$.field").value("amount"))
            .andExpect(jsonPath("$.value").value("-5"))
            .andExpect(jsonPath("$.message").value(message))
    }

    @Test
    fun `a missing field is 400 with value null`() {
        // given
        val message = "Field 'asset' is required"
        submitThrows(ValidationException(ValidationError("asset", null, message, ValidationError.Kind.INVALID)))

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
        submitThrows(ValidationException(ValidationError("state", "PENDING", message, ValidationError.Kind.UNSUPPORTED)))

        // when
        val result = postFee()

        // then
        result
            .andExpect(status().isUnprocessableEntity)
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
            .andExpect(jsonPath("$.title").value("Unprocessable Entity"))
            .andExpect(jsonPath("$.status").value(422))
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
            .andExpect(jsonPath("$.transaction_id").value("txn_001"))
            .andExpect(jsonPath("$.detail").value(DuplicateTransactionException("txn_001").message))
            .andExpect(jsonPath("$.field").doesNotExist())
    }

    @Test
    fun `a declined charge is 422 with the transaction id and the wallet's reason`() {
        // given
        submitThrows(ChargeFailedException("txn_001", "insufficient funds"))

        // when
        val result = postFee()

        // then
        result
            .andExpect(status().isUnprocessableEntity)
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
            .andExpect(jsonPath("$.status").value(422))
            .andExpect(jsonPath("$.transaction_id").value("txn_001"))
            .andExpect(jsonPath("$.reason").value("insufficient funds"))
            .andExpect(jsonPath("$.detail").value("The fee for transaction 'txn_001' could not be charged: insufficient funds"))
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
            .andExpect(jsonPath("$.detail").value("Fee processing is temporarily unavailable. Retry the same request later"))
        assertThat(result.andReturn().response.contentAsString).doesNotContain("Connection refused", "restate:8080")
    }

    @Test
    fun `an unknown transaction on get is 404 with the transaction id`() {
        // given
        `when`(feeService.status("txn_404")).thenThrow(TransactionNotFoundException("txn_404"))

        // when
        val result = mockMvc.perform(get("/transaction/fee/txn_404"))

        // then
        result
            .andExpect(status().isNotFound)
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
            .andExpect(jsonPath("$.status").value(404))
            .andExpect(jsonPath("$.transaction_id").value("txn_404"))
            .andExpect(jsonPath("$.detail").value("No fee record for transaction 'txn_404'"))
            .andExpect(jsonPath("$.instance").value("/transaction/fee/txn_404"))
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
        verifyNoInteractions(feeService)
    }

    @Test
    fun `a missing body is 400 problem json`() {
        // given
        val request = post("/transaction/fee").contentType(MediaType.APPLICATION_JSON)

        // when
        val result = mockMvc.perform(request)

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
        val result = mockMvc.perform(request)

        // then
        result
            .andExpect(status().isUnsupportedMediaType)
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
            .andExpect(jsonPath("$.status").value(415))
        verifyNoInteractions(feeService)
    }
}
