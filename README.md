# fee-service

`POST /transaction/fee` calculates, charges and records the fee of a transaction. It is written in Kotlin with
Spring Boot. The work runs as a [Restate](https://restate.dev) workflow, and the fee records are stored in PostgreSQL.
A transaction is charged at most once, even after crashes, restarts or repeated requests.

## How it works

```
POST /transaction/fee
  ▼
TransactionFeeController                HTTP only
  ▼
FeeService
  ├─ 1. RequestValidator ─────────────► invalid: 400 / 422, no Postgres or Restate call
  ├─ 2. ChargedFees ──────────────────► already charged: 200, no Restate call
  │       Caffeine cache, then Postgres
  ├─ 3. FeeRecordRepository.start       fee_record row: transaction_id → new workflow_id (UUID)
  │                                     (a repeated id reuses the row's workflow_id)
  └─ 4. FeeWorkflowClients              submit FeeWorkflow/<workflow_id>, wait for the result
          ▼
        Restate                         journals every step, retries after a crash
          ▼
        FeeWorkflow
          CALCULATE  FeeCalculator: the FeeRule of the transaction type
          CHARGE     FeeRecordRepository.charge: the row gets the fee and becomes CHARGED
          ▼
        200 with the fee
```

- **Charged once:** a `transaction_id` has a single `fee_record` row, and so a single workflow. Sending it again
  returns the first result.
- **Survives failure:** after a crash, Restate replays the steps that already finished from its journal instead of
  running them again.
- **Traceable:** `fee_record.workflow_id` is the Restate workflow key, and the app logs
  `Transaction <id> is processed by workflow <workflow_id>`. You can look the transaction up in the Restate UI
  (`localhost:9070`).
- **Extensible:** a new transaction type is an enum value, one `FeeRule` class with its `*FeeProperties` and its fee
  numbers in `application.yml`; a new check is one `TransactionValidator` class.

## Modules

- `api/` (`fee-service-api`): the HTTP contract (`FeeRequest`, `FeeResponse` and the error types). It depends only
  on Kotlin and Jackson, so another service can depend on it (`mvn -pl api -am install`) and call the fee service with
  the same types, without pulling in Spring, Restate or the service.
- `service/` (`fee-service`): the app.

## Fees

| Type | Fee | 1000 USD → |
|---|---|---|
| Mobile Top Up | 0.15% | 1.50 |
| Bank Transfer | 0.5%, min 1.00 | 5.00 |
| Merchant Payment | 1% + 0.30 | 10.30 |
| Cash Withdrawal | flat 2.00 | 2.00 |

USD and EUR use the same numbers.

## Run

Restate and Postgres come from [`dev-setup/`](dev-setup/README.md) (Podman compose).

To run everything in containers (only Podman is needed):

```bash
cd dev-setup
podman compose up -d --build
podman compose exec dev bash
mvn spring-boot:run              # inside the dev container
```

To run the app on the host (JDK 25 and Maven 3.9+), with only Restate and Postgres in containers:

```bash
cd dev-setup && podman compose up -d restate postgres && cd ..
mvn spring-boot:run -Dspring-boot.run.profiles=local
```

Either way the API is at `localhost:8081`. On startup the app creates its table and registers itself with Restate.
If the dev container is running too, it already holds port 8081, so start the host app with `SERVER_PORT=8082`.
Every setting, fees included, is in [`application.yml`](service/src/main/resources/application.yml) together with its
environment variable. A restart applies a change.

```bash
curl -s -X POST localhost:8081/transaction/fee -H 'Content-Type: application/json' -d '{
  "transaction_id": "txn_001", "amount": 1000, "asset": "USD", "asset_type": "FIAT",
  "type": "Mobile Top Up", "state": "SETTLED - PENDING FEE", "created_at": "2023-08-30 15:42:17.610059"}'
```
```json
{"transaction_id":"txn_001","amount":1000,"asset":"USD","type":"Mobile Top Up","fee":1.5,"rate":0.0015,"description":"Standard fee rate of 0.15%"}
```

Errors are `application/problem+json`; the service's own carry a `code`:

- **400:** a malformed request (a missing field, a wrong format); **422:** a well-formed one the service doesn't support
- **503:** Postgres or Restate is unreachable. Sending the request again is safe.
- **504:** the fee was not ready within 30 s. Send the request again; the transaction is still charged only once.

More examples: [docs/demo.md](docs/demo.md).

## Tests

```bash
mvn test     # unit tests
mvn verify   # + integration tests and Cucumber features against Testcontainers Restate and Postgres
```

Every build needs a container engine, because it generates the jOOQ classes from a throwaway Postgres. The recovery
tests crash the app at every step and right after every Postgres write, restart its endpoint, freeze Restate and take
Postgres down. Each test counts the **CALCULATE executions** to prove that a transaction is calculated and charged
exactly once.

## Assumptions

- Only the state `SETTLED - PENDING FEE` is processed, and only `USD` and `EUR` (`FIAT`, 2 decimals) are supported.
- `amount` is greater than 0, at most 3,000,000,000, and has no more decimals than its asset.
- `created_at` is read as UTC and must not be in the future.
- `transaction_id` is 1–64 letters, digits, `_` or `-`.
- Fees are rounded `HALF_EVEN` to 2 decimals and returned without trailing zeros (`1.5`).
- Charging a fee means recording it in Postgres. There is no external wallet.
- Only the app calls Restate. Clients can't reach it directly.
