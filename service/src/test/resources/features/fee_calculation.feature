Feature: Fee calculation
  A transaction in state "SETTLED - PENDING FEE" is charged the fee of its type, in its own asset, once, and the fee is
  recorded in Restate. Fees are rounded HALF_EVEN to 2 decimals; amounts compare by value (1.5 equals 1.50).
  Transaction ids are scoped to their scenario, so scenarios never share a fee record or a charge.

  Scenario: The sample transaction from the requirements is charged the Mobile Top Up fee
    Given a transaction request:
      | transaction_id | txn_001                    |
      | amount         | 1000                       |
      | asset          | USD                        |
      | asset_type     | FIAT                       |
      | type           | Mobile Top Up              |
      | state          | SETTLED - PENDING FEE      |
      | created_at     | 2023-08-30 15:42:17.610059 |
    When the transaction is submitted to "/transaction/fee"
    Then the response status is 200
    And the response body is:
      | transaction_id | txn_001                    |
      | amount         | 1000                       |
      | asset          | USD                        |
      | type           | Mobile Top Up              |
      | fee            | 1.5                        |
      | rate           | 0.0015                     |
      | description    | Standard fee rate of 0.15% |
    And the fee was charged exactly once
    And the fee is recorded as completed

  Scenario Outline: A <type> of <amount> <asset> is charged <fee> <asset>
    Given a "<type>" transaction "<id>" of <amount> "<asset>" "FIAT" in state "SETTLED - PENDING FEE"
    When the transaction is submitted to "/transaction/fee"
    Then the response status is 200
    And the fee is <fee> with rate <rate> and description "<description>"
    And the fee was charged exactly once
    And the fee is recorded as completed

    Examples: One of each type in each supported asset
      | id      | type             | amount | asset | fee  | rate   | description                        |
      | txn_101 | Mobile Top Up    | 1000   | USD   | 1.5  | 0.0015 | Standard fee rate of 0.15%         |
      | txn_102 | Mobile Top Up    | 250.50 | EUR   | 0.38 | 0.0015 | Standard fee rate of 0.15%         |
      | txn_103 | Bank Transfer    | 1000   | USD   | 5.0  | 0.005  | Fee rate of 0.5% (min 1.00 USD)    |
      | txn_104 | Bank Transfer    | 10000  | EUR   | 50.0 | 0.005  | Fee rate of 0.5% (min 1.00 EUR)    |
      | txn_105 | Merchant Payment | 1000   | USD   | 10.3 | 0.01   | Fee rate of 1% plus 0.30 USD fixed |
      | txn_106 | Merchant Payment | 49.99  | EUR   | 0.80 | 0.01   | Fee rate of 1% plus 0.30 EUR fixed |
      | txn_107 | Cash Withdrawal  | 1000   | USD   | 2.0  | 0      | Flat fee of 2.00 USD               |
      | txn_108 | Cash Withdrawal  | 5      | EUR   | 2.0  | 0      | Flat fee of 2.00 EUR               |

    Examples: Bank Transfer never charges less than its minimum, in the transaction's asset
      | id      | type          | amount | asset | fee  | rate  | description                     |
      | txn_111 | Bank Transfer | 100    | USD   | 1.0  | 0.005 | Fee rate of 0.5% (min 1.00 USD) |
      | txn_112 | Bank Transfer | 50     | EUR   | 1.0  | 0.005 | Fee rate of 0.5% (min 1.00 EUR) |
      | txn_113 | Bank Transfer | 200    | EUR   | 1.0  | 0.005 | Fee rate of 0.5% (min 1.00 EUR) |
      | txn_114 | Bank Transfer | 201    | USD   | 1.0  | 0.005 | Fee rate of 0.5% (min 1.00 USD) |
      | txn_115 | Bank Transfer | 202    | USD   | 1.01 | 0.005 | Fee rate of 0.5% (min 1.00 USD) |
