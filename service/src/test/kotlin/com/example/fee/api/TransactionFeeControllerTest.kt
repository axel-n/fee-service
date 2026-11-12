package com.example.fee.api

import com.example.fee.application.FeeService
import com.example.fee.contract.FeeRequest
import com.example.fee.contract.FeeResponse
import com.example.fee.contract.FeeStatusResponse
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.math.BigDecimal

@WebMvcTest(TransactionFeeController::class)
class TransactionFeeControllerTest(@Autowired private val mockMvc: MockMvc) {

    @MockitoBean
    private lateinit var feeService: FeeService

    private val requestJson = """
        {
          "transaction_id": "txn_001",
          "amount": 1000,
          "asset": "USD",
          "asset_type": "FIAT",
          "type": "Mobile Top Up",
          "state": "SETTLED - PENDING FEE",
          "created_at": "2023-08-30 15:42:17.610059"
        }
    """.trimIndent()

    private val request = FeeRequest(
        transactionId = "txn_001",
        amount = BigDecimal("1000"),
        asset = "USD",
        assetType = "FIAT",
        type = "Mobile Top Up",
        state = "SETTLED - PENDING FEE",
        createdAt = "2023-08-30 15:42:17.610059",
    )

    private val response = FeeResponse(
        transactionId = "txn_001",
        amount = BigDecimal("1000"),
        asset = "USD",
        type = "Mobile Top Up",
        fee = BigDecimal("1.50"),
        rate = BigDecimal("0.0015"),
        description = "Standard fee rate of 0.15%",
    )

    @Test
    fun `a charged fee is 200 with the completed status and the fee in snake case`() {
        // given
        `when`(feeService.submit(request)).thenReturn(FeeStatusResponse.completed(response))

        // when
        val result = mockMvc.perform(
            post("/transaction/fee").contentType(MediaType.APPLICATION_JSON).content(requestJson),
        )

        // then
        result
            .andExpect(status().isOk)
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
            .andExpect(header().doesNotExist(HttpHeaders.LOCATION))
            .andExpect(jsonPath("$.transaction_id").value("txn_001"))
            .andExpect(jsonPath("$.status").value("COMPLETED"))
            .andExpect(jsonPath("$.asset").value("USD"))
            .andExpect(jsonPath("$.type").value("Mobile Top Up"))
            .andExpect(jsonPath("$.description").value("Standard fee rate of 0.15%"))
        val body = result.andReturn().response.contentAsString
        assertThat(body).contains("\"amount\":1000,", "\"fee\":1.50,", "\"rate\":0.0015,")
    }

    @Test
    fun `the request body reaches the service with every snake case field`() {
        // given
        `when`(feeService.submit(request)).thenReturn(FeeStatusResponse.completed(response))

        // when
        mockMvc.perform(post("/transaction/fee").contentType(MediaType.APPLICATION_JSON).content(requestJson))

        // then
        verify(feeService).submit(request)
    }
}
