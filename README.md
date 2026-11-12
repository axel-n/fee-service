# fee-service

A Kotlin + Spring Boot service that calculates, charges and records the fee of a transaction. The work runs as a
durable [Restate](https://restate.dev) workflow, so it survives crashes and restarts and never charges twice.

```
POST /transaction/fee          → 200 with the fee (or 202 while it is still running)
```

- [Requirements](requirements.md): the challenge
- [Design](docs/design.md): goal, fee table, how Restate is used, class design, wiring, call graph, guarantees
- [Dev environment](dev-setup/README.md): the dev Restate and the dev container (`podman compose`)
- [How AI was used](docs/ai-usage.md): the task runner and its gate, what a person reviews

## What it does

`POST /transaction/fee` receives a transaction and hands it to the `FeeWorkflow` in Restate. The workflow:

1. **validates** the request (required fields, formats, only `SETTLED - PENDING FEE`, only `USD`/`EUR` `FIAT`, a known type),
2. **claims** the `transaction_id`, so one transaction is charged at most once, whatever the payloads and callers,
3. **calculates** the fee with the rule of the transaction's type,
4. **charges and records** the fee: it is stored permanently in Restate state as the transaction's charged fee.

The caller waits for the result (up to `restate.attach-timeout`) and gets the fee. The workflow is keyed by the
`transaction_id`, so sending the same id again returns the first run's response without a second charge, whatever the
payload; a rejected id stays rejected until Restate drops the finished run (1 day by default).
Nothing is stored outside Restate.

```json
// POST /transaction/fee                          // 200 OK
{                                                 {
  "transaction_id": "txn_001",                      "transaction_id": "txn_001",
                                                    "status": "COMPLETED",
  "amount": 1000,                                   "amount": 1000,
  "asset": "USD",                                   "asset": "USD",
  "asset_type": "FIAT",                             "type": "Mobile Top Up",
  "type": "Mobile Top Up",                          "fee": 1.50,
  "state": "SETTLED - PENDING FEE",                 "rate": 0.0015,
  "created_at": "2023-08-30 15:42:17.610059"        "description": "Standard fee rate of 0.15%"
}                                                 }
```

## How Restate is used

```
POST /transaction/fee
   ▼
Spring app: TransactionFeeController → FeeService           (thin gateway, no business logic)
   │  1. transaction_id present and well-formed?  (it is the workflow key; otherwise 400)
   │  2. submit FeeWorkflow/txn_001/run, then attach  (the id sent again gets PREVIOUSLY_ACCEPTED
   │     and the first run's result; no result within attach-timeout → 202)
   ▼
Restate server (external Docker) ── journals every step ───────────────────────────┐
   │ invokes the app's endpoint (port 9080)                                         │
   ▼                                                                                │
FeeWorkflow.run(request)                                                            │
   step VALIDATE   journaled { RequestValidator.validate(request) } → Rejected? ────┤
   step MAP        TransactionMapper.toTransaction(request)  (pure, deterministic)  │
   step CLAIM      TransactionFeeRecord/txn_001.claim()       → Conflict? / Done? ──┤
   step CALCULATE  journaled { FeeCalculator.calculate(tx) }                     ───┤
   step RECORD     TransactionFeeRecord/txn_001.complete(response)  (the charge) ───┘
   return FeeOutcome.Charged(response)
```

- **`FeeWorkflow`** (a Restate *workflow*, keyed by `transaction_id`) runs each transaction exactly once. Every
  submission of an id attaches to its one run instead of starting a second one, and gets that run's result.
- **`TransactionFeeRecord`** (a Restate *virtual object*, keyed by `transaction_id`) holds the permanent fee record, so
  a charged id is not charged again even after Restate has dropped the finished run.
- Everything non-deterministic (the clock in validation, the fee numbers) runs inside a journaled step (Restate's `runBlock`), so its result is
  journaled and replayed, never recomputed.

**Crash example.** The app dies right after CALCULATE. Restate retries the invocation, and the endpoint (restarted, or a
new instance) receives the journal. VALIDATE, CLAIM and CALCULATE are **replayed from the journal and not executed
again**. RECORD then runs once, and the waiting caller gets the result. If the app dies *while* CALCULATE is running,
that step was not journaled yet, so it runs again; only its journaled result is recorded.

**Startup.** The app starts its Restate endpoint on port 9080 and needs nothing else from Restate to start. It calls
Restate only through the ingress (`restate.ingress-url`). Restate calls the workflow through the endpoint, once the
endpoint is registered with it: a one-time setup step (see [Run](#run)), not something the app does. Until then, or while
Restate is down, requests get 503.

## Architecture

Two Maven modules under the root `pom.xml` (the parent, `fee-service-parent`):

| Module | Artifact | What it holds |
|--------|----------|---------------|
| [`api/`](api) | `com.example:fee-service-api` | the HTTP contract other services depend on: `contract/` (`FeeRequest`, `FeeResponse`, `FeeStatusResponse`) and `contract/error/` (the `FeeException` classes, `FeeErrorCode`, `FeeProblem`, the error body). Jackson and Kotlin only, no Spring, no Restate |
| [`service/`](service) | `com.example:fee-service` | the app: everything below, plus `application.yml` and every test of it |

The packages of the service module:

```
config/            RestateProperties, FeeProperties                         every @ConfigurationProperties class: one file per
                                                                            top-level section of application.yml
api/               TransactionFeeController, ApiExceptionHandler            HTTP only, errors as RFC 7807 ProblemDetail
application/       FeeService                                               thin gateway: transaction_id → submit/attach → HTTP result
workflow/          FeeWorkflow, its durable types, StepHook                 the Restate workflow: every business step
record/            TransactionFeeRecord, FeeRecord, ClaimDecider            the Restate virtual object: one fee per transaction_id
restate/           RestateEndpointLifecycle, RestateConfig                  Restate plumbing: the endpoint, the ingress client
validation/        TransactionValidator, RequestValidator, ValidationError  run inside the workflow (VALIDATE)
validation/rule/   one TransactionValidator bean per check, by @Order
fee/               FeeRule, FeeCalculator                                   run inside the workflow (CALCULATE)
fee/formula/       PercentageFee, FlatFee, …                                the building blocks
fee/rule/          one FeeRule bean per transaction type
domain/            Transaction and its enums, TransactionMapper             pure Kotlin
```

The controller calls only `FeeService`, `FeeService` holds no business logic, and every business step runs inside
`FeeWorkflow`. Spring builds every object, including the two Restate handlers, so constructor injection, `@Order` and the
injected rule and validator lists work behind the workflow too.

The full class design, the wiring, the call graph and the idempotency and recovery guarantees are in
[`docs/design.md`](docs/design.md).

## Setup

### Prerequisites

- **JDK 25** and **Maven 3.9+** (the system `mvn`, there is no wrapper), or the dev container in [`dev-setup/`](dev-setup/README.md),
  which has both, plus a dev Restate.
- **A Restate server 1.8.x** running in Docker or Podman. The app doesn't start it.
- For `mvn verify`: a container engine that Testcontainers can reach (Docker, or Podman with its socket).

### Run

1. Start Restate. With the dev environment, `podman compose` in `dev-setup/` starts it (see its README). Without it:

   ```bash
   docker run --rm --name restate -p 8080:8080 -p 9070:9070 \
     --add-host=host.docker.internal:host-gateway \
     docker.restate.dev/restatedev/restate:1.8.1
   ```

   The `--add-host` lets Restate reach the app on the host as `host.docker.internal`, on Linux too.

2. Start the app:

   ```bash
   mvn spring-boot:run
   ```

   The API listens on port **8081**, the Restate endpoint on **9080** (`Restate endpoint listening on port 9080` in the log).

3. Register the endpoint with Restate (once), under the URL Restate reaches the app at. Restate calls that URL to
   discover the workflow and the virtual object:

   ```bash
   curl -X POST http://localhost:9070/deployments -H 'content-type: application/json' \
     -d '{"uri": "http://host.docker.internal:9080", "force": true}'
   ```

   (or `restate deployments register http://host.docker.internal:9080` with the Restate CLI). Restate keeps the
   registration across app restarts. Run it again only when the endpoint's URL changes or after a change to a handler's
   signature: `force` makes Restate pick up the changed services.

Inside the dev container the ingress URL comes from an environment variable (`RESTATE_INGRESS_URL=http://restate:8080`),
so `mvn spring-boot:run` works unchanged. Restate reaches the app there as `http://dev:9080`, so register that URL with
`http://restate:9070` (the container also sets `RESTATE_ADMIN_URL` and `RESTATE_ADVERTISED_URL` to these two):

```bash
curl -X POST "$RESTATE_ADMIN_URL/deployments" -H 'content-type: application/json' \
  -d "{\"uri\": \"$RESTATE_ADVERTISED_URL\", \"force\": true}"
```

## Configuration

Every tunable value comes from Spring Boot's externalized configuration, so a change needs **a restart, not a rebuild**.
The packaged [`application.yml`](service/src/main/resources/application.yml) holds the defaults. Overrides, highest priority first:
command-line arguments (`--restate.ingress-url=…`), environment variables, a `config/application.yml` in the directory the
app starts from (or `--spring.config.additional-location=file:/etc/fee-service/`), the packaged file.

| Key | Environment variable | Default | What it is |
|-----|----------------------|---------|------------|
| `server.port` | `SERVER_PORT` | `8081` | HTTP API port |
| `restate.ingress-url` | `RESTATE_INGRESS_URL` | `http://localhost:8080` | Restate ingress, used by `FeeService` |
| `restate.endpoint-port` | `RESTATE_ENDPOINT_PORT` | `9080` | port the Restate endpoint listens on, where Restate calls the workflow |
| `restate.attach-timeout` | `RESTATE_ATTACH_TIMEOUT` | `10s` | how long `POST` waits for the result before answering 202 |
| `fee.<type>.rate`, `.min`, `.fixed` | (use a file) | see [Fee table](#fee-table) | fee numbers per transaction type |

Fees are easiest to change in a file next to the app. It needs only the keys that change:

```yaml
# config/application.yml: raises the Bank Transfer fee; every other value keeps its default
fee:
  bank-transfer:
    rate: "0.006"
    min: "1.50"
```

Money values are quoted so they reach `BigDecimal` exactly. A missing or invalid fee value (a rate outside [0, 1), a
negative amount) fails startup with a message naming the key. Descriptions are built from the configured numbers, so they
never go stale.

**What a restart with changed values does:**

| Change | Effect |
|--------|--------|
| Fee numbers | Safe. A workflow that already passed CALCULATE keeps its journaled fee; everything else uses the new numbers. Completed records are never recalculated: resending a charged transaction returns the original response |
| `restate.ingress-url`, `attach-timeout`, `server.port` | Safe |
| `restate.endpoint-port` (or the app's host) | Register the new URL with Restate; it becomes a new deployment. Invocations in flight stay on the old URL and keep retrying it, so let them finish first |

Renaming a key or a variable breaks existing override files and environments.

## API examples

The responses below were recorded against a running app.

**Charge a fee** (200):

```bash
curl -s -X POST localhost:8081/transaction/fee -H 'Content-Type: application/json' -d '{
  "transaction_id": "txn_001", "amount": 1000, "asset": "USD", "asset_type": "FIAT",
  "type": "Mobile Top Up", "state": "SETTLED - PENDING FEE", "created_at": "2023-08-30 15:42:17.610059"}'
```
```json
{"transaction_id":"txn_001","status":"COMPLETED","amount":1000,"asset":"USD","type":"Mobile Top Up","fee":1.50,"rate":0.0015,"description":"Standard fee rate of 0.15%"}
```

Sending the **same `transaction_id` again** returns the same body, whatever the payload, and the fee is not charged again.

**Another type and asset**, with Bank Transfer's minimum applied:

```bash
curl -s -X POST localhost:8081/transaction/fee -H 'Content-Type: application/json' -d '{
  "transaction_id": "txn_002", "amount": 100, "asset": "EUR", "asset_type": "FIAT",
  "type": "Bank Transfer", "state": "SETTLED - PENDING FEE", "created_at": "2023-08-30 15:42:17.610059"}'
```
```json
{"transaction_id":"txn_002","status":"COMPLETED","amount":100,"asset":"EUR","type":"Bank Transfer","fee":1.00,"rate":0.005,"description":"Fee rate of 0.5% (min 1.00 EUR)"}
```


**Errors** are `application/problem+json`. Every error the service raises itself carries a stable `code`:

| Request | Status | Body (shortened) |
|---------|--------|------------------|
| `"state": "PENDING"` | 422 | `"code":"UNSUPPORTED_REQUEST","detail":"Unsupported state 'PENDING'. Supported: 'SETTLED - PENDING FEE'","field":"state","value":"PENDING"` |
| `"amount": -5` | 400 | `"code":"INVALID_REQUEST","detail":"Invalid amount '-5'. Allowed: a number greater than 0","field":"amount","value":"-5"` |
| An id whose earlier run was killed after its claim, once Restate dropped that run | 409 | `"code":"DUPLICATE_TRANSACTION","detail":"Transaction 'txn_001' is claimed by an earlier run that has not finished","transaction_id":"txn_001"` |

| Restate unreachable | 503 | `"code":"WORKFLOW_UNAVAILABLE"`; resending later is safe |

Spring MVC's own errors (a body that isn't JSON, 415, 405, an unknown path) are problems too, without a `code`.

A run that takes longer than `restate.attach-timeout` answers **202** with `{"transaction_id":"…","status":"IN_PROGRESS"}`;
send the transaction again to get the fee once it is charged.

A rejection is the `transaction_id`'s answer while Restate keeps the finished run (1 day by default): the corrected request
sent with the same id gets the same rejection until then (e.g. `PENDING`, then `SETTLED - PENDING FEE`).

### Calling it from another service

Depend on the api module instead of copying the DTOs. `mvn -pl api -am install` puts it, and the parent pom it needs, in
the local repository (no container engine needed):

```xml
<dependency>
    <groupId>com.example</groupId>
    <artifactId>fee-service-api</artifactId>
    <version>0.0.1-SNAPSHOT</version>
</dependency>
```

It brings `FeeRequest`, `FeeResponse` and `FeeStatusResponse` with their snake_case JSON names, and the errors: read an
error body as `FeeProblem`, and `toException()` gives back the `FeeException` the service raised
(`DuplicateTransactionException`, `ValidationException`, …), or `null` for a problem without a known `code`.

```kotlin
val problem = objectMapper.readValue(errorBody, FeeProblem::class.java)
when (val failure = problem.toException()) {
    is DuplicateTransactionException -> log.info("already charged: {}", failure.transactionId)
    is ValidationException -> throw failure            // field, value and message of the rejected request
    else -> throw IllegalStateException("fee service answered ${problem.status}: ${problem.detail}")
}
```

## Tests

```bash
mvn test              # unit tests: no Docker, no Spring context except @WebMvcTest slices
mvn verify            # + integration tests (Testcontainers Restate 1.8.1) and Cucumber features; needs a container engine
scripts/check.sh      # the project's gate: rule checks + mvn clean verify, and every test must actually run
```

- **Unit tests** (`*Test`) cover every production class: each validator and its messages, each fee rule and building
  block (rounding, minimums, `BigDecimal` comparisons), the claim rules, the serde of every durable type,
  the gateway's HTTP mapping, the packaged configuration. `TestCoverageAuditTest` fails when a production file has no test.
- **BDD features** ([`service/src/test/resources/features/`](service/src/test/resources/features)) describe the behaviour end to end against
  a real Restate: fee calculation per type and asset, validation, transaction state (a rejected id stays rejected), idempotency
  and workflow recovery.
- **Integration tests** (`*IT`) share one Testcontainers Restate and use only random ports, so a Restate or an app already
  running on the machine can't interfere.

What the recovery tests prove (each counts the **CALCULATE executions**, not just the response):

| Test | Proves |
|------|--------|
| `CrashRecoveryIT`, `workflow_recovery.feature` | A crash before or after any step boundary is retried by Restate, and the journaled steps are replayed, not run again (validation and calculation closures each run once): one fee, record `COMPLETED` |
| `EndpointRestartIT`, `workflow_recovery.feature` | Stopping and restarting the endpoint in the middle of CALCULATE completes the workflow with one fee record |
| `RestateOutageIT` | A workflow frozen mid-CALCULATE completes with one fee record once Restate thaws |
| `InFlightStatusIT` | A run that outlasts the attach timeout answers 202 `IN_PROGRESS`; the same payload sent again gets the fee |
| `DeterminismIT` | A validation result is journaled: moving the clock between a crash and the retry doesn't change it |
| `DuplicatesIT`, `idempotency.feature` | One `transaction_id`, sequentially or 10 callers at once, with the same or different payloads, is charged once and every caller gets the first run's response; an id held by an unfinished run gets 409 |
| `RejectedIT`, `transaction_state.feature` | A rejected id is neither charged nor recorded, and the corrected request with the same id gets the same rejection |
| `RetentionExpiredIT` | After Restate dropped a finished run, a charged id returns the stored response without charging again, and a rejected id is charged normally |
## Fee table

The defaults in the packaged `application.yml`, changeable with a restart (see [Configuration](#configuration)). Fixed
amounts are in the transaction's asset (USD or EUR, same numbers). Fees are rounded `HALF_EVEN` to the asset's 2 decimals.

| Type | Formula | `rate` | `description` (EUR example) | 1000 USD → fee |
|------|---------|--------|-----------------------------|----------------|
| `Mobile Top Up` | 0.15% | `0.0015` | `Standard fee rate of 0.15%` | 1.50 |
| `Bank Transfer` | 0.5%, min 1.00 | `0.005` | `Fee rate of 0.5% (min 1.00 EUR)` | 5.00 (100 → 1.00) |
| `Merchant Payment` | 1% + 0.30 fixed | `0.01` | `Fee rate of 1% plus 0.30 EUR fixed` | 10.30 |
| `Cash Withdrawal` | flat 2.00 | `0` | `Flat fee of 2.00 EUR` | 2.00 |

## Extension points

Extending means **adding a class**; no central `when` grows:

| To add… | Do this |
|---------|---------|
| a validation rule | a `@Component @Order(ValidationOrder.X)` `TransactionValidator` returning `ValidationError?` + a unit test |
| a transaction type | an enum value in `TransactionType` + a `@Component` `FeeRule` + its `*FeeProperties` + a `fee.<type>` section in `application.yml` + a unit test + a BDD row. Startup fails if a type has no rule |
| a fee formula | a new building block implementing `FeeRule` |
| an asset | an enum value in `Asset` with its decimals and asset type |
| a workflow step | a `WorkflowStep` value + a step in `FeeWorkflow` wrapped with `StepHook` + a row in the crash-recovery tests |

Details: [Extension points in `docs/design.md`](docs/design.md#extension-points). Changing a handler signature, the step
order or the JSON of `FeeRequest`/`FeeResponse` is a breaking change for in-flight workflows and stored records.

## Assumptions

- Only state `SETTLED - PENDING FEE` is processed, and only `USD` and `EUR` (`FIAT`, 2 decimals). Enum values match
  exactly (`"usd"` is unsupported).
- Malformed requests are 400, well-formed but unsupported ones 422; the first failing check (in a fixed order) is reported.
- `created_at` is `yyyy-MM-dd HH:mm:ss[.fraction]` without a zone, read as **UTC**, and may not be in the future.
- `transaction_id` is 1–64 ASCII letters, digits, `_` or `-`, because it becomes part of Restate keys.
- An amount may not have more decimals than its asset (`10.001 USD` is 422); trailing zeros don't count.
- `fee` is returned at the asset's scale (`1.50`, which equals the challenge's `1.5`); `amount` is echoed as sent.
- One `transaction_id` is processed once: sending it again, with any payload, returns the first run's response (the fee or
  the rejection) without charging again. A rejected id can be charged once Restate drops its run (1 day by default).
- Charging a fee means recording it in Restate as the transaction's charged fee; there is no external wallet.
- Bank Transfer has a minimum and no maximum.

## Future work

- **Kubernetes:** register the endpoint as a deploy step (a Job, or the Restate operator) and add **readiness** and
  **liveness** probes. Actuator is not added yet; it needs approval as a dependency.
- **Graceful shutdown of the endpoint:** stopping it today closes open connections, and Restate retries the interrupted
  invocations (safe, but noisy). Stop accepting new invocations and drain the running ones first.
- **Deployments per version:** register each release under its own URL and let the old one drain instead of re-registering
  with `force`.
- **Cancelled, killed or paused workflows** leave the record `IN_PROGRESS`, so once Restate drops the run the id gets 409
  until an operator acts.
- Spring Boot 4 with Jackson 3 (the Restate SDK's serde is still on Jackson 2).
