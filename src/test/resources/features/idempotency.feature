Feature: Idempotency
  A transaction is keyed by its transaction_id and a hash of its payload. The same payload sent again, one call after
  the other or by several callers at once, is the same workflow: Restate runs it once, every caller gets its response,
  and the wallet is charged once. An amount that differs only in trailing zeros is the same payload.
  A changed payload for a transaction that was already charged is a new workflow, and the claim on the transaction's fee
  record stops it with 409 Conflict. The original charge and record stand, and the original payload still gets its
  response. (A changed payload after a rejection is processed normally: see transaction_state.feature.)

  Scenario: The same payload sent twice gets the same response and is charged once
    Given a "Mobile Top Up" transaction "txn_401" of 1000 "USD" "FIAT" in state "SETTLED - PENDING FEE"
    When the transaction is submitted to "/transaction/fee"
    Then the response status is 200
    When the same transaction is submitted to "/transaction/fee" again
    Then the response status is 200
    And the response is the same as the first one
    And the fee is 1.5 with rate 0.0015 and description "Standard fee rate of 0.15%"
    And the wallet was charged exactly once
    And the charge was attempted only once
    And the fee is recorded as completed

  Scenario: An amount sent again with trailing zeros is the same payload
    Given a "Merchant Payment" transaction "txn_402" of 1000 "EUR" "FIAT" in state "SETTLED - PENDING FEE"
    When the transaction is submitted to "/transaction/fee"
    Then the response status is 200
    When the transaction's "amount" is changed to "1000.00"
    And the same transaction is submitted to "/transaction/fee" again
    Then the response status is 200
    And the response is the same as the first one
    And the fee is 10.3 with rate 0.01 and description "Fee rate of 1% plus 0.30 EUR fixed"
    And the wallet was charged exactly once
    And the charge was attempted only once
    And the fee is recorded as completed

  Scenario Outline: A charged transaction sent again with a changed <field> is a conflict, and the charge stands
    Given a "Mobile Top Up" transaction "<id>" of 1000 "USD" "FIAT" in state "SETTLED - PENDING FEE"
    When the transaction is submitted to "/transaction/fee"
    Then the response status is 200
    When the transaction's "<field>" is changed to "<changed>"
    And the same transaction is submitted to "/transaction/fee" again
    Then the response status is 409
    And the response is a conflict naming the transaction
    And the wallet was charged exactly once
    And the charge was attempted only once
    And the fee is recorded as completed
    When the transaction's "<field>" is changed to "<original>"
    And the same transaction is submitted to "/transaction/fee" again
    Then the response status is 200
    And the response is the same as the first one
    And the wallet was charged exactly once
    And the charge was attempted only once

    Examples: Fields of an already charged transaction that change its payload
      | id      | field      | original                   | changed                    |
      | txn_411 | amount     | 1000                       | 2000                       |
      | txn_412 | asset      | USD                        | EUR                        |
      | txn_413 | type       | Mobile Top Up              | Cash Withdrawal            |
      | txn_414 | created_at | 2023-08-30 15:42:17.610059 | 2023-08-30 15:42:17.610060 |

  Scenario: The same payload sent by 5 callers at once is charged once, and every caller gets the response
    Given a "Bank Transfer" transaction "txn_421" of 1000 "EUR" "FIAT" in state "SETTLED - PENDING FEE"
    When 5 callers submit the transaction to "/transaction/fee" at the same time
    Then every caller gets status 200 and the same response
    And the fee is 5.0 with rate 0.005 and description "Fee rate of 0.5% (min 1.00 EUR)"
    And the wallet was charged exactly once
    And the charge was attempted only once
    And the fee is recorded as completed

  Scenario: Different payloads of one transaction sent at once: one is charged, the others conflict
    Given a "Mobile Top Up" transaction "txn_422" of 1000 "USD" "FIAT" in state "SETTLED - PENDING FEE"
    When 5 callers submit the transaction to "/transaction/fee" at the same time, each with its own amount
    Then one caller gets status 200 and every other caller a conflict naming the transaction
    And the wallet was charged exactly once
    And the charge was attempted only once
    And the fee is recorded as completed
