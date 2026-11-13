Feature: Request validation
  Every request is validated by the app before it reaches Restate or Postgres, and the first problem decides the
  response. A malformed request (a missing field, a transaction_id that can't be a key, an amount of 0 or less, a
  created_at in the wrong format or in the future) is rejected with 400 before any unsupported value is looked at; a
  well-formed request asking for something unsupported (an asset other than USD and EUR, an asset type other than FIAT,
  an unknown transaction type, more decimals than the asset allows, an amount above 3000000000) is rejected with 422.
  Each rejection names the field, echoes the value as sent and explains what is allowed. A rejection starts no workflow
  and asks Postgres nothing, so it is neither charged nor recorded. The workflow runs the same checks again before
  anything is claimed or charged.

  Scenario Outline: A request without <field> is rejected with 400
    Given a "Mobile Top Up" transaction "<id>" of 1000 "USD" "FIAT" in state "SETTLED - PENDING FEE"
    And the transaction's "<field>" is missing
    When the transaction is submitted to "/transaction/fee"
    Then the response status is 400
    And the request is rejected on missing field "<field>" with message "Field '<field>' is required"
    And the fee was not charged
    And no fee is recorded
    And the transaction never reached Restate or Postgres

    Examples: Every field of the contract is required
      | id      | field          |
      | txn_401 | transaction_id |
      | txn_402 | amount         |
      | txn_403 | asset          |
      | txn_404 | asset_type     |
      | txn_405 | type           |
      | txn_406 | state          |
      | txn_407 | created_at     |

  Scenario Outline: A request with a blank <field> is rejected with 400
    Given a "Mobile Top Up" transaction "<id>" of 1000 "USD" "FIAT" in state "SETTLED - PENDING FEE"
    And the transaction's "<field>" is changed to ""
    When the transaction is submitted to "/transaction/fee"
    Then the response status is 400
    And the request is rejected on field "<field>" with value "" and message "Field '<field>' is required but was blank: ''"
    And the fee was not charged
    And no fee is recorded
    And the transaction never reached Restate or Postgres

    Examples: A blank text counts as missing
      | id      | field      |
      | txn_411 | asset      |
      | txn_412 | asset_type |
      | txn_413 | type       |
      | txn_414 | state      |
      | txn_415 | created_at |

  Scenario: A request whose type is only spaces is rejected with 400
    Given a "Mobile Top Up" transaction "txn_416" of 1000 "USD" "FIAT" in state "SETTLED - PENDING FEE"
    And the transaction's "type" is changed to "   "
    When the transaction is submitted to "/transaction/fee"
    Then the response status is 400
    And the request is rejected on field "type" with value "   " and message "Field 'type' is required but was blank: '   '"
    And the fee was not charged
    And no fee is recorded
    And the transaction never reached Restate or Postgres

  Scenario: A blank transaction_id is rejected with 400 as missing
    Given a "Mobile Top Up" transaction "txn_417" of 1000 "USD" "FIAT" in state "SETTLED - PENDING FEE"
    And the transaction's "transaction_id" is sent exactly as ""
    When the transaction is submitted to "/transaction/fee"
    Then the response status is 400
    And the request is rejected on field "transaction_id" with value "" and message "Field 'transaction_id' is required but was blank: ''"
    And the fee was not charged

  Scenario Outline: A transaction_id that can't be a key is rejected with 400: "<transaction_id>"
    Given a "Mobile Top Up" transaction "txn_421" of 1000 "USD" "FIAT" in state "SETTLED - PENDING FEE"
    And the transaction's "transaction_id" is sent exactly as "<transaction_id>"
    When the transaction is submitted to "/transaction/fee"
    Then the response status is 400
    And the request is rejected on field "transaction_id" with value "<transaction_id>" and message "Invalid transaction_id '<transaction_id>'. Allowed: 1 to 64 characters, each a letter (A-Z, a-z), a digit, '_' or '-'"
    And the fee was not charged

    Examples: Only ASCII letters, digits, '_' and '-'
      | transaction_id |
      | txn 001        |
      | txn:001        |
      | txn/001        |
      | txn_001!       |
      | txn.001        |
      | tχn_001        |

  Scenario: A transaction_id longer than 64 characters is rejected with 400, and the message shortens it
    Given a "Mobile Top Up" transaction "txn_422" of 1000 "USD" "FIAT" in state "SETTLED - PENDING FEE"
    And the transaction's "transaction_id" is sent exactly as "txn_0123456789012345678901234567890123456789012345678901234567890"
    When the transaction is submitted to "/transaction/fee"
    Then the response status is 400
    And the request is rejected on field "transaction_id" with value "txn_0123456789012345678901234567890123456789012345678901234567890" and message "Invalid transaction_id 'txn_012345678901234567890123456789012345678901234567890123456789…'. Allowed: 1 to 64 characters, each a letter (A-Z, a-z), a digit, '_' or '-'"
    And the fee was not charged

  Scenario Outline: An amount of <amount> is rejected with 400
    Given a "Mobile Top Up" transaction "<id>" of 1000 "USD" "FIAT" in state "SETTLED - PENDING FEE"
    And the transaction's "amount" is changed to "<amount>"
    When the transaction is submitted to "/transaction/fee"
    Then the response status is 400
    And the request is rejected on field "amount" with value "<amount>" and message "Invalid amount '<amount>'. Allowed: a number greater than 0"
    And the fee was not charged
    And no fee is recorded
    And the transaction never reached Restate or Postgres

    Examples: The amount must be greater than 0
      | id      | amount |
      | txn_431 | 0      |
      | txn_432 | 0.00   |
      | txn_433 | -0.01  |
      | txn_434 | -1     |
      | txn_435 | -1000  |

  Scenario Outline: A created_at of "<created_at>" is rejected with 400
    Given a "Mobile Top Up" transaction "<id>" of 1000 "USD" "FIAT" in state "SETTLED - PENDING FEE"
    And the transaction's "created_at" is changed to "<created_at>"
    When the transaction is submitted to "/transaction/fee"
    Then the response status is 400
    And the request is rejected on field "created_at" with value "<created_at>" and message "Invalid created_at '<created_at>'. Allowed: 'yyyy-MM-dd HH:mm:ss' with 0 to 9 fraction digits, e.g. '2023-08-30 15:42:17.610059'"
    And the fee was not charged
    And no fee is recorded
    And the transaction never reached Restate or Postgres

    Examples: Only 'yyyy-MM-dd HH:mm:ss' with 0 to 9 fraction digits, no zone, a real date
      | id      | created_at                     |
      | txn_441 | 2023-08-30T15:42:17.610059     |
      | txn_442 | 2023-08-30 15:42:17.610059Z    |
      | txn_443 | 2023-08-30 15:42:17+02:00      |
      | txn_444 | 2023-08-30                     |
      | txn_445 | 2023-08-30 15:42               |
      | txn_446 | 30.08.2023 15:42:17            |
      | txn_447 | 2023-02-30 10:00:00            |
      | txn_448 | 2023-08-30 24:00:00            |
      | txn_449 | 2023-08-30 15:42:17.1234567890 |
      | txn_450 | yesterday                      |

  Scenario Outline: A transaction created at <created_at>, after now, is rejected with 400
    Given it is "2030-01-01 12:00:00" UTC
    And a "Mobile Top Up" transaction "<id>" of 1000 "USD" "FIAT" in state "SETTLED - PENDING FEE"
    And the transaction's "created_at" is changed to "<created_at>"
    When the transaction is submitted to "/transaction/fee"
    Then the response status is 400
    And the request is rejected on field "created_at" with value "<created_at>" and message "Invalid created_at '<created_at>': it is in the future. Allowed: up to now, 2030-01-01 12:00:00.000000 UTC"
    And the fee was not charged
    And no fee is recorded
    And the transaction never reached Restate or Postgres

    Examples: created_at is UTC and may not be later than now
      | id      | created_at                 |
      | txn_451 | 2030-01-01 12:00:00.000001 |
      | txn_452 | 2030-01-01 12:00:01        |
      | txn_453 | 2030-01-02 00:00:00        |
      | txn_454 | 2099-12-31 23:59:59        |

  Scenario: A transaction created exactly now is accepted
    Given it is "2030-01-01 12:00:00" UTC
    And a "Mobile Top Up" transaction "txn_455" of 1000 "USD" "FIAT" in state "SETTLED - PENDING FEE"
    And the transaction's "created_at" is changed to "2030-01-01 12:00:00"
    When the transaction is submitted to "/transaction/fee"
    Then the response status is 200
    And the fee is 1.5 with rate 0.0015 and description "Standard fee rate of 0.15%"
    And the fee was charged exactly once
    And the fee is recorded as completed

  Scenario Outline: An unsupported <field> "<value>" is rejected with 422
    Given a "<type>" transaction "<id>" of <amount> "<asset>" "<asset_type>" in state "SETTLED - PENDING FEE"
    When the transaction is submitted to "/transaction/fee"
    Then the response status is 422
    And the request is rejected on field "<field>" with value "<value>" and message "<message>"
    And the fee was not charged
    And no fee is recorded
    And the transaction never reached Restate or Postgres

    Examples: Only USD and EUR, matched exactly; the asset is checked before its type
      | id      | type          | amount | asset | asset_type | field | value | message                                           |
      | txn_461 | Mobile Top Up | 1000   | GBP   | FIAT       | asset | GBP   | Unsupported asset 'GBP'. Supported: 'USD', 'EUR'  |
      | txn_462 | Mobile Top Up | 1000   | BTC   | CRYPTO     | asset | BTC   | Unsupported asset 'BTC'. Supported: 'USD', 'EUR'  |
      | txn_463 | Mobile Top Up | 1000   | usd   | FIAT       | asset | usd   | Unsupported asset 'usd'. Supported: 'USD', 'EUR'  |

    Examples: Only FIAT
      | id      | type          | amount | asset | asset_type | field      | value  | message                                          |
      | txn_464 | Mobile Top Up | 1000   | USD   | CRYPTO     | asset_type | CRYPTO | Unsupported asset_type 'CRYPTO'. Supported: 'FIAT' |
      | txn_465 | Bank Transfer | 1000   | EUR   | fiat       | asset_type | fiat   | Unsupported asset_type 'fiat'. Supported: 'FIAT'   |

    Examples: Only the types that have a fee rule
      | id      | type            | amount | asset | asset_type | field | value           | message                                                                                                        |
      | txn_466 | Crypto Purchase | 1000   | USD   | FIAT       | type  | Crypto Purchase | Unsupported type 'Crypto Purchase'. Supported: 'Mobile Top Up', 'Bank Transfer', 'Merchant Payment', 'Cash Withdrawal' |
      | txn_467 | mobile top up   | 1000   | EUR   | FIAT       | type  | mobile top up   | Unsupported type 'mobile top up'. Supported: 'Mobile Top Up', 'Bank Transfer', 'Merchant Payment', 'Cash Withdrawal'   |

    Examples: No more decimals than the asset allows
      | id      | type             | amount   | asset | asset_type | field  | value    | message                                      |
      | txn_468 | Mobile Top Up    | 10.001   | USD   | FIAT       | amount | 10.001   | Amount 10.001 has 3 decimals. USD allows 2   |
      | txn_469 | Bank Transfer    | 0.005    | EUR   | FIAT       | amount | 0.005    | Amount 0.005 has 3 decimals. EUR allows 2    |
      | txn_470 | Merchant Payment | 1.123456 | USD   | FIAT       | amount | 1.123456 | Amount 1.123456 has 6 decimals. USD allows 2 |

    Examples: An amount of at most 3000000000, in every asset
      | id      | type             | amount        | asset | asset_type | field  | value         | message                                                              |
      | txn_472 | Mobile Top Up    | 3000000000.01 | USD   | FIAT       | amount | 3000000000.01 | Amount 3000000000.01 is above the maximum. Allowed: up to 3000000000 |
      | txn_473 | Bank Transfer    | 3000000001    | EUR   | FIAT       | amount | 3000000001    | Amount 3000000001 is above the maximum. Allowed: up to 3000000000    |
      | txn_474 | Cash Withdrawal  | 10000000000   | USD   | FIAT       | amount | 10000000000   | Amount 10000000000 is above the maximum. Allowed: up to 3000000000   |

  Scenario: Trailing zeros are not decimals
    Given a "Mobile Top Up" transaction "txn_471" of 1000 "USD" "FIAT" in state "SETTLED - PENDING FEE"
    And the transaction's "amount" is changed to "10.100"
    When the transaction is submitted to "/transaction/fee"
    Then the response status is 200
    And the fee is 0.02 with rate 0.0015 and description "Standard fee rate of 0.15%"
    And the fee was charged exactly once
    And the fee is recorded as completed

  Scenario: An amount of exactly the maximum is accepted
    Given a "Mobile Top Up" transaction "txn_475" of 3000000000.00 "USD" "FIAT" in state "SETTLED - PENDING FEE"
    When the transaction is submitted to "/transaction/fee"
    Then the response status is 200
    And the fee is 4500000.00 with rate 0.0015 and description "Standard fee rate of 0.15%"
    And the fee was charged exactly once
    And the fee is recorded as completed

  Scenario: Too many decimals are reported before an amount above the maximum
    Given a "Mobile Top Up" transaction "txn_476" of 3000000000.001 "USD" "FIAT" in state "SETTLED - PENDING FEE"
    When the transaction is submitted to "/transaction/fee"
    Then the response status is 422
    And the request is rejected on field "amount" with value "3000000000.001" and message "Amount 3000000000.001 has 3 decimals. USD allows 2"
    And the fee was not charged
    And no fee is recorded
    And the transaction never reached Restate or Postgres

  Scenario: A malformed request is rejected with 400 even when it is also unsupported
    Given a "Crypto Purchase" transaction "txn_481" of 1000 "GBP" "CRYPTO" in state "PENDING"
    And the transaction's "amount" is changed to "-5"
    When the transaction is submitted to "/transaction/fee"
    Then the response status is 400
    And the request is rejected on field "amount" with value "-5" and message "Invalid amount '-5'. Allowed: a number greater than 0"
    And the fee was not charged
    And no fee is recorded
    And the transaction never reached Restate or Postgres

  Scenario: Of several unsupported values, the first in validation order is reported
    Given a "Crypto Purchase" transaction "txn_482" of 10.001 "GBP" "CRYPTO" in state "SETTLED - PENDING FEE"
    When the transaction is submitted to "/transaction/fee"
    Then the response status is 422
    And the request is rejected on field "asset" with value "GBP" and message "Unsupported asset 'GBP'. Supported: 'USD', 'EUR'"
    And the fee was not charged
    And no fee is recorded
    And the transaction never reached Restate or Postgres
