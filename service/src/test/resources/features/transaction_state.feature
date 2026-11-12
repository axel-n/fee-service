Feature: Transaction state
  Only a transaction in state "SETTLED - PENDING FEE" is processed. Any other state is rejected with 422 and a message
  that names the rejected state and the supported one; states match exactly (case-sensitive, no trimming).
  A rejection is neither charged nor recorded. The workflow is keyed by the transaction_id, so the rejection stays the
  id's answer while Restate keeps the finished run (1 day by default): the same transaction sent again in the corrected
  state gets the same rejection.

  Scenario Outline: A transaction in state "<state>" is rejected, and stays rejected when sent again in the corrected state
    Given a "Mobile Top Up" transaction "<id>" of 1000 "USD" "FIAT" in state "<state>"
    When the transaction is submitted to "/transaction/fee"
    Then the response status is 422
    And the request is rejected on field "state" with value "<state>" and message "<message>"
    And the fee was not charged
    And no fee is recorded
    When the transaction's "state" is changed to "SETTLED - PENDING FEE"
    And the transaction is submitted to "/transaction/fee"
    Then the response status is 422
    And the request is rejected on field "state" with value "<state>" and message "<message>"
    And the fee was not charged
    And no fee is recorded

    Examples: States of a transaction that is not ready for its fee
      | id      | state     | message                                                             |
      | txn_301 | PENDING   | Unsupported state 'PENDING'. Supported: 'SETTLED - PENDING FEE'     |
      | txn_302 | SETTLED   | Unsupported state 'SETTLED'. Supported: 'SETTLED - PENDING FEE'     |
      | txn_303 | COMPLETED | Unsupported state 'COMPLETED'. Supported: 'SETTLED - PENDING FEE'   |
      | txn_304 | FAILED    | Unsupported state 'FAILED'. Supported: 'SETTLED - PENDING FEE'      |
      | txn_305 | CANCELLED | Unsupported state 'CANCELLED'. Supported: 'SETTLED - PENDING FEE'   |

    Examples: Near misses of the supported state are not accepted either
      | id      | state                 | message                                                                         |
      | txn_311 | settled - pending fee | Unsupported state 'settled - pending fee'. Supported: 'SETTLED - PENDING FEE'   |
      | txn_312 | SETTLED-PENDING FEE   | Unsupported state 'SETTLED-PENDING FEE'. Supported: 'SETTLED - PENDING FEE'     |
      | txn_313 | PENDING FEE           | Unsupported state 'PENDING FEE'. Supported: 'SETTLED - PENDING FEE'             |
