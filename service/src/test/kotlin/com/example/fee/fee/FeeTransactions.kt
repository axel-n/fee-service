package com.example.fee.fee

import com.example.fee.domain.Asset
import com.example.fee.domain.AssetType
import com.example.fee.domain.Transaction
import com.example.fee.domain.TransactionState
import com.example.fee.domain.TransactionType
import java.math.BigDecimal
import java.time.LocalDateTime

/** A validated transaction for fee tests; only amount, asset and type matter to a rule. */
fun transaction(
    amount: String,
    asset: Asset = Asset.USD,
    type: TransactionType = TransactionType.MOBILE_TOP_UP,
): Transaction =
    Transaction(
        transactionId = "txn_001",
        amount = BigDecimal(amount),
        asset = asset,
        assetType = AssetType.FIAT,
        type = type,
        state = TransactionState.SETTLED_PENDING_FEE,
        createdAt = LocalDateTime.of(2023, 8, 30, 15, 42, 17),
    )

/** A description that shows which asset it was built for. */
val describeAsset: (Asset) -> String = { asset -> "described for ${asset.value}" }
