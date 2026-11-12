package com.example.fee.validation

/**
 * The `@Order` of each [TransactionValidator], spaced by 100 so a new check fits between two others. `@Order` needs
 * compile-time constants, hence an object of `const val`s. Every structural check (400) comes before every business
 * check (422), and a check comes after the ones it relies on (D11).
 */
object ValidationOrder {

    // Structural: the request is malformed (ValidationError.Kind.INVALID, 400).
    const val REQUIRED_FIELDS = 100
    const val TRANSACTION_ID = 200
    const val AMOUNT = 300
    const val CREATED_AT = 400

    // Business: well-formed but not supported (ValidationError.Kind.UNSUPPORTED, 422).
    const val STATE = 500
    const val ASSET = 600
    const val ASSET_TYPE = 700
    const val TRANSACTION_TYPE = 800

    /** After [ASSET]: the allowed decimals belong to the asset. */
    const val AMOUNT_SCALE = 900
}
