Feature: Fee workflow survives failures
  Every step of the fee workflow is journaled by Restate. When the service crashes between steps, Restate retries the
  workflow and replays the journaled steps instead of running them again, so the caller still gets the fee and the
  wallet is charged once. When the endpoint restarts while the wallet is still charging, that step was not journaled and
  runs again; the wallet's idempotency key keeps it to one charge.

  Scenario Outline: The service crashes <moment> the <step> step
    Given a "Mobile Top Up" transaction "txn_200" of 1000 "USD" "FIAT" in state "SETTLED - PENDING FEE"
    And the service will crash <moment> the "<step>" step
    When the transaction is submitted to "/transaction/fee"
    Then the response status is 200
    And the fee is 1.5 with rate 0.0015 and description "Standard fee rate of 0.15%"
    And the service crashed once
    And the wallet was charged exactly once
    And the fee is recorded as completed

    Examples:
      | moment | step      |
      | before | CHARGE    |
      | after  | CHARGE    |
      | after  | CALCULATE |
      | before | RECORD    |

  Scenario: The service endpoint restarts while charging
    Given a "Bank Transfer" transaction "txn_201" of 1000 "EUR" "FIAT" in state "SETTLED - PENDING FEE"
    And the wallet is slow to respond
    When the transaction is submitted to "/transaction/fee"
    And the service endpoint restarts
    Then the fee is 5.0
    And the wallet was charged exactly once
    And the fee is recorded as completed
