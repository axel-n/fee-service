Feature: Idempotency
  A transaction is keyed by its transaction_id: Restate runs one workflow per transaction_id, and every submission of
  that id, one call after the other or by several callers at once, attaches to that run. The first payload Restate
  receives is the one processed and charged, once, and every caller gets that run's response, whatever payload it sent.
  (A rejection is the id's answer in the same way: see transaction_state.feature.)

  Scenario: The same payload sent twice gets the same response and is charged once
    Given a "Mobile Top Up" transaction "txn_401" of 1000 "USD" "FIAT" in state "SETTLED - PENDING FEE"
    When the transaction is submitted to "/transaction/fee"
    Then the response status is 200
    When the same transaction is submitted to "/transaction/fee" again
    Then the response status is 200
    And the response is the same as the first one
    And the fee is 1.5 with rate 0.0015 and description "Standard fee rate of 0.15%"
    And the fee was charged exactly once
    And the fee is recorded as completed

  Scenario Outline: A charged transaction sent again with a changed <field> gets the first response and is charged once
    Given a "Mobile Top Up" transaction "<id>" of 1000 "USD" "FIAT" in state "SETTLED - PENDING FEE"
    When the transaction is submitted to "/transaction/fee"
    Then the response status is 200
    When the transaction's "<field>" is changed to "<changed>"
    And the same transaction is submitted to "/transaction/fee" again
    Then the response status is 200
    And the response is the same as the first one
    And the fee is 1.5 with rate 0.0015 and description "Standard fee rate of 0.15%"
    And the fee was charged exactly once
    And the fee is recorded as completed

    Examples: Fields of an already charged transaction
      | id      | field      | changed                    |
      | txn_411 | amount     | 1000.00                    |
      | txn_412 | amount     | 2000                       |
      | txn_413 | asset      | EUR                        |
      | txn_414 | type       | Cash Withdrawal            |
      | txn_415 | state      | PENDING                    |
      | txn_416 | created_at | 2023-08-30 15:42:17.610060 |

  Scenario: The same payload sent by 5 callers at once is charged once, and every caller gets the response
    Given a "Bank Transfer" transaction "txn_421" of 1000 "EUR" "FIAT" in state "SETTLED - PENDING FEE"
    When 5 callers submit the transaction to "/transaction/fee" at the same time
    Then every caller gets status 200 and the same response
    And the fee is 5.0 with rate 0.005 and description "Fee rate of 0.5% (min 1.00 EUR)"
    And the fee was charged exactly once
    And the fee is recorded as completed

  Scenario: Different payloads of one transaction sent at once: one is charged, and every caller gets its response
    Given a "Mobile Top Up" transaction "txn_422" of 1000 "USD" "FIAT" in state "SETTLED - PENDING FEE"
    When 5 callers submit the transaction to "/transaction/fee" at the same time, each with its own amount
    Then every caller gets status 200 and the same response
    And the response is the charge of one caller's amount
    And the fee was charged exactly once
    And the fee is recorded as completed
