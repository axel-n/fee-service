package com.example.fee.record

import com.example.fee.contract.FeeResponse
import java.math.BigDecimal

/** Test fixtures for fee records: the reference Mobile Top Up response and two distinct payload hashes. */
object FeeRecords {
    const val HASH = "hash-a"
    const val OTHER_HASH = "hash-b"

    val response = FeeResponse(
        transactionId = "txn_001",
        amount = BigDecimal("1000"),
        asset = "USD",
        type = "Mobile Top Up",
        fee = BigDecimal("1.50"),
        rate = BigDecimal("0.0015"),
        description = "Standard fee rate of 0.15%",
    )
}
