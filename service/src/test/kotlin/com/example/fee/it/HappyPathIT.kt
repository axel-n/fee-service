package com.example.fee.it

import com.example.fee.record.FeeRecordStatus
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.web.server.LocalServerPort
import java.math.BigDecimal
import java.util.UUID

/**
 * The happy path end to end: every transaction type in every supported asset goes through the real app and the shared
 * Restate (VALIDATE → MAP → CLAIM → CALCULATE → RECORD) and comes back charged with the packaged fees.
 */
class HappyPathIT @Autowired constructor(
    private val calculator: CountingFeeCalculator,
    @LocalServerPort apiPort: Int,
) : RestateIntegrationTest() {

    private val api = FeeApi(apiPort)

    @ParameterizedTest(name = "{0} {2} {1} → fee {3}")
    @CsvSource(
        delimiter = '|',
        textBlock = """
        Mobile Top Up    | USD | 1000   | 1.50  | 0.0015 | Standard fee rate of 0.15%
        Mobile Top Up    | EUR | 250.50 | 0.38  | 0.0015 | Standard fee rate of 0.15%
        Bank Transfer    | USD | 1000   | 5.00  | 0.005  | Fee rate of 0.5% (min 1.00 USD)
        Bank Transfer    | EUR | 100    | 1.00  | 0.005  | Fee rate of 0.5% (min 1.00 EUR)
        Merchant Payment | USD | 1000   | 10.30 | 0.01   | Fee rate of 1% plus 0.30 USD fixed
        Merchant Payment | EUR | 49.99  | 0.80  | 0.01   | Fee rate of 1% plus 0.30 EUR fixed
        Cash Withdrawal  | USD | 1000   | 2.00  | 0      | Flat fee of 2.00 USD
        Cash Withdrawal  | EUR | 5      | 2.00  | 0      | Flat fee of 2.00 EUR""",
    )
    fun `a settled transaction is charged its fee once and recorded as completed`(
        type: String,
        asset: String,
        amount: BigDecimal,
        fee: BigDecimal,
        rate: BigDecimal,
        description: String,
    ) {
        // given
        val transactionId = "it-happy-${UUID.randomUUID().toString().take(13)}"
        val request = mapOf(
            "transaction_id" to transactionId,
            "amount" to amount,
            "asset" to asset,
            "asset_type" to "FIAT",
            "type" to type,
            "state" to "SETTLED - PENDING FEE",
            "created_at" to "2023-08-30 15:42:17.610059",
        )

        // when
        val response = api.submit(request)

        // then: the response carries the fee
        assertThat(response.status).describedAs("POST status, body %s", response.body).isEqualTo(200)
        val body = response.body
        assertThat(body["transaction_id"].asText()).isEqualTo(transactionId)
        assertThat(body["status"].asText()).isEqualTo("COMPLETED")
        assertThat(body["amount"].decimalValue()).isEqualByComparingTo(amount)
        assertThat(body["asset"].asText()).isEqualTo(asset)
        assertThat(body["type"].asText()).isEqualTo(type)
        assertThat(body["fee"].decimalValue()).isEqualByComparingTo(fee)
        assertThat(body["rate"].decimalValue()).isEqualByComparingTo(rate)
        assertThat(body["description"].asText()).isEqualTo(description)

        // then: Restate holds the record as COMPLETED with that response
        val record = StoredFeeRecords.of(transactionId)
        assertThat(record).isNotNull
        assertThat(record?.status).isEqualTo(FeeRecordStatus.COMPLETED)
        assertThat(record?.response?.transactionId).isEqualTo(transactionId)
        assertThat(record?.response?.fee).isEqualByComparingTo(fee)
        assertThat(record?.response?.rate).isEqualByComparingTo(rate)
        assertThat(record?.response?.description).isEqualTo(description)


        // then: the fee was calculated once
        assertThat(calculator.count(transactionId)).describedAs("CALCULATE executions").isEqualTo(1)
    }
}
