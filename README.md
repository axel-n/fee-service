# fee-service

A Kotlin + Spring Boot service that calculates, charges and records the fee of a transaction. The work runs as a
durable [Restate](https://restate.dev) workflow, so it survives crashes and restarts and never charges twice.

```
POST /transaction/fee          → 200 with the fee (or 504 when it is not charged within restate.attach-timeout)
```

- [Requirements](requirements.md): the challenge
- [Design](docs/design.md): goal, fee table, how Restate is used, class design, wiring, call graph, guarantees
- [Demo](docs/demo.md): curl commands with their expected responses: the requirements' sample, the same id again,
  the other transaction types, an unknown type
- [Dev environment](dev-setup/README.md): the dev Restate, the dev Postgres and the dev container (`podman compose`)
- [How AI is used](docs/ai-usage.md): what every session reads, the gate, what a person decides

## What it does

`POST /transaction/fee` receives a transaction and hands it to the `FeeWorkflow` in Restate. The workflow:

1. **validates** the request (required fields, formats, only `SETTLED - PENDING FEE`, only `USD`/`EUR` `FIAT`, a known type,
   an amount up to 3,000,000,000),
2. **claims** the `transaction_id`, so one transaction is charged at most once, whatever the payloads and callers,
3. **calculates** the fee with the rule of the transaction's type,
4. **charges and records** the fee: it is stored permanently in PostgreSQL as the transaction's charged fee.

The caller waits for the result (up to `restate.attach-timeout`, 30 s by default) and gets the fee, or a 504 problem when Restate
then reports the run as not finished (its 470). The workflow is keyed by the
`transaction_id`, so sending the same id again returns the first run's response without a second charge, whatever the
payload; a rejected id stays rejected until Restate drops the finished run (1 day by default).
A charged id sent again is answered from a local cache (Caffeine) or from Postgres, without a call to Restate. The fee
records live in Postgres, the workflow's journal in Restate, and nothing anywhere else.

```json
// POST /transaction/fee                          // 200 OK
{                                                 {
  "transaction_id": "txn_001",                      "transaction_id": "txn_001",
  "amount": 1000,                                   "amount": 1000,
  "asset": "USD",                                   "asset": "USD",
  "asset_type": "FIAT",                             "type": "Mobile Top Up",
  "type": "Mobile Top Up",                          "fee": 1.5,
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
   │  2. already charged? the Caffeine cache, then Postgres → 200 with the stored response, no Restate call
   │  3. submit FeeWorkflow/txn_001/run, then attach  (the id sent again gets PREVIOUSLY_ACCEPTED
   │     and the first run's result; no result within attach-timeout and Restate answers 470 → 504)
   ▼
Restate server (external Docker) ── journals every step ───────────────────────────┐
   │ invokes the app's endpoint (port 9080)                                         │
   ▼                                                                                │
FeeWorkflow.run(request)                                                            │
   step VALIDATE   journaled { RequestValidator.validate(request) } → Rejected? ────┤
   step MAP        TransactionMapper.toTransaction(request)  (pure, deterministic)  │
   step CLAIM      journaled { Postgres: claim txn_001 with this run's token }      │
                   → Conflict? / already charged? ──────────────────────────────────┤
   step CALCULATE  journaled { FeeCalculator.calculate(tx) }                     ───┤
   step RECORD     journaled { Postgres: complete txn_001 with the response }       │
                   (the charge) ────────────────────────────────────────────────────┘
   return FeeOutcome.Charged(response)
```

- **`FeeWorkflow`** (a Restate *workflow*, keyed by `transaction_id`) runs each transaction exactly once. Every
  submission of an id attaches to its one run instead of starting a second one, and gets that run's result. Of several
  payloads sent at once for one id, the first to reach Restate is the one charged, and every caller gets its response.
- **The `fee_record` table in PostgreSQL** holds the permanent fee record of each `transaction_id`: every field of the
  request, the charge, and when it was claimed and charged. A charged id is not charged again even after Restate has
  dropped the finished run. Old records are deleted by the indexed `claimed_at`, never by the client's `created_at` (see
  `docs/design.md`: a deleted record no longer protects its id). A Postgres transaction is opened only to write: at CLAIM,
  after validation passed and only for an id that has no record yet, and at RECORD. The primary key lets one claim win, and
  only the claim's token can record the charge. The lookup of a charged id waits at most `fee-cache.lookup-timeout` for
  Postgres, then leaves the answer to Restate, so a database that hangs can't hold requests up.
- Everything non-deterministic or with side effects (the clock in validation, the fee numbers, the claim token, Postgres)
  runs inside a journaled step (Restate's `runBlock`), so its result is journaled and replayed, never recomputed.

**Crash example.** The app dies right after CALCULATE. Restate retries the invocation, and the endpoint (restarted, or a
new instance) receives the journal. VALIDATE, CLAIM and CALCULATE are **replayed from the journal and not executed
again**. RECORD then runs once, and the waiting caller gets the result. If the app dies *while* CALCULATE is running,
that step was not journaled yet, so it runs again; only its journaled result is recorded. If it dies right after a
Postgres write committed, the step runs again and finds its own write by the claim token: still one record.

**Startup and shutdown.** The app creates its table in Postgres (`schema.sql`) first: if Postgres can't be reached, it
doesn't start. Then it starts its Restate endpoint on port 9080 and registers it with Restate's admin API
(`restate.admin-url`) as `http://<restate.advertised-host>:<port>`, so it works at whatever host and port it runs on. If
Restate can't register it, the app doesn't start and says which URL and settings to fix. At shutdown it removes its
registration again, unless invocations that haven't finished still need it: those resume once the app is back at the same
URL. Work is sent through the ingress (`restate.ingress-url`); while Restate is down, requests get 503.

## Architecture

Two Maven modules under the root `pom.xml` (the parent, `fee-service-parent`):

| Module | Artifact | What it holds |
|--------|----------|---------------|
| [`api/`](api) | `com.example:fee-service-api` | the HTTP contract other services depend on: `contract/` (`FeeRequest`, `FeeResponse`) and `contract/error/` (the `FeeException` classes, `FeeErrorCode`, `FeeProblem`, the error body). Jackson and Kotlin only, no Spring, no Restate |
| [`service/`](service) | `com.example:fee-service` | the app: everything below, plus `application.yml` and every test of it |

The packages of the service module:

```
config/            RestateProperties, FeeCacheProperties, FeeProperties,    every @Configuration and @ConfigurationProperties
                   RestateConfig, ChargedFeesCacheConfig, ClockConfig       class: the bean definitions, and one properties file
                                                                            per top-level section of application.yml
api/               TransactionFeeController, ApiExceptionHandler            HTTP only, errors as RFC 7807 ProblemDetail
application/       FeeService, ChargedFees                                  thin gateway: transaction_id → charged? (cache,
                                                                            Postgres) → submit/attach → HTTP result
workflow/          FeeWorkflow, its durable types, StepHook                 the Restate workflow: every business step
record/            FeeRecordRepository, FeeRecord, ClaimDecider             the fee records in Postgres: one per transaction_id
restate/           RestateEndpointLifecycle, RestateDeployments,            Restate plumbing: the endpoint and its registration,
                   FeeWorkflowClients                                       the ingress client
validation/        TransactionValidator, RequestValidator, ValidationError  run inside the workflow (VALIDATE)
validation/rule/   one TransactionValidator bean per check, by @Order
fee/               FeeRule, FeeCalculator                                   run inside the workflow (CALCULATE)
fee/formula/       PercentageFee, FlatFee, …                                the building blocks
fee/rule/          one FeeRule bean per transaction type
domain/            Transaction and its enums, TransactionMapper             pure Kotlin
```

The controller calls only `FeeService`, `FeeService` holds no business logic, and every business step runs inside
`FeeWorkflow`. Spring builds every object, including the Restate handler, so constructor injection, `@Order` and the
injected rule and validator lists work behind the workflow too.

The full class design, the wiring, the call graph and the idempotency and recovery guarantees are in
[`docs/design.md`](docs/design.md).

## Setup

### Prerequisites

- **JDK 25** and **Maven 3.9+** (the system `mvn`, there is no wrapper), or the dev container in [`dev-setup/`](dev-setup/README.md),
  which has both, plus a dev Restate and a dev Postgres.
- **A Restate server 1.8.x** running in Docker or Podman. The app doesn't start it.
- **PostgreSQL** (18) running in Docker or Podman, with a database `fee` for the user `fee` by default. The app creates its
  table itself, and doesn't start without the database.
- For `mvn verify`: a container engine that Testcontainers can reach (Docker, or Podman with its socket).

### Run

1. Start Restate and Postgres. With the dev environment, `podman compose up -d restate postgres` in `dev-setup/` starts
   both (see its README). Without it:

   ```bash
   docker run --rm --name restate -p 8080:8080 -p 9070:9070 \
     --add-host=host.docker.internal:host-gateway \
     docker.restate.dev/restatedev/restate:1.8.1
   docker run --rm --name fee-postgres -p 5432:5432 \
     -e POSTGRES_DB=fee -e POSTGRES_USER=fee -e POSTGRES_PASSWORD=fee \
     docker.io/library/postgres:18.7
   ```

   The `--add-host` lets Restate reach the app on the host as `host.docker.internal`, on Linux too.

2. Start the app with the `local` profile (the app on this machine, Restate and Postgres in containers):

   ```bash
   mvn spring-boot:run -Dspring-boot.run.profiles=local
   ```

   The API listens on port **8081**, the Restate endpoint on **9080**. The app registers the endpoint with Restate as
   `http://host.docker.internal:9080` (`Restate endpoint registered as …` in the log); there is nothing to register by
   hand. Another API or endpoint port works the same way: `SERVER_PORT`, `RESTATE_ENDPOINT_PORT`.

Inside the dev container the environment sets `RESTATE_INGRESS_URL=http://restate:8080`, `RESTATE_ADMIN_URL=http://restate:9070`,
`RESTATE_ADVERTISED_HOST=dev` and `DATABASE_URL=r2dbc:postgresql://postgres:5432/fee`, so plain `mvn spring-boot:run`
registers the app as `http://dev:9080` and records the fees in the dev Postgres.

Whichever app started last gets the new work, so switching between the dev container and the local profile needs only
a restart of the app you switch to.

## Configuration

Every tunable value comes from Spring Boot's externalized configuration, so a change needs **a restart, not a rebuild**.
The packaged [`application.yml`](service/src/main/resources/application.yml) holds the defaults. Overrides, highest priority first:
command-line arguments (`--restate.ingress-url=…`), environment variables, a `config/application.yml` in the directory the
app starts from (or `--spring.config.additional-location=file:/etc/fee-service/`), the packaged file.

| Key | Environment variable | Default | What it is |
|-----|----------------------|---------|------------|
| `server.port` | `SERVER_PORT` | `8081` | HTTP API port |
| `restate.ingress-url` | `RESTATE_INGRESS_URL` | `http://localhost:8080` | Restate ingress, used by `FeeService` |
| `restate.admin-url` | `RESTATE_ADMIN_URL` | `http://localhost:9070` | Restate's admin API, where the app registers its endpoint |
| `restate.endpoint-port` | `RESTATE_ENDPOINT_PORT` | `9080` | port the Restate endpoint listens on, where Restate calls the workflow |
| `restate.advertised-host` | `RESTATE_ADVERTISED_HOST` | `localhost` | host Restate reaches the endpoint at (`host.docker.internal` in the `local` profile) |
| `restate.attach-timeout` | `RESTATE_ATTACH_TIMEOUT` | `30s` | how long `POST` waits for the result; then it answers 504 only if Restate reports the run as not finished (470) |
| `spring.r2dbc.url` | `DATABASE_URL` | `r2dbc:postgresql://localhost:5432/fee` | the Postgres of the fee records |
| `spring.r2dbc.username`, `.password` | `DATABASE_USERNAME`, `DATABASE_PASSWORD` | `fee`, `fee` | its credentials (the dev Postgres'; set them everywhere else) |
| `fee-cache.maximum-size` | `FEE_CACHE_MAXIMUM_SIZE` | `1000` | how many charged fees `FeeService` caches at most |
| `fee-cache.expire-after-write` | `FEE_CACHE_EXPIRE_AFTER_WRITE` | `1d` | how long a charged fee stays cached; charges never change, so both only bound memory |
| `fee-cache.lookup-timeout` | `FEE_CACHE_LOOKUP_TIMEOUT` | `1s` | how long a cache miss waits for Postgres before it asks Restate |
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
| `restate.ingress-url`, `admin-url`, `attach-timeout`, `server.port`, `fee-cache.*` | Safe |
| The database (`DATABASE_URL`, credentials) | The app starts only if it reaches it. Keep it pointing at the database that holds the records: an empty one forgets every charge |
| `restate.endpoint-port` or `advertised-host` | The app registers the new URL at startup, and it gets all new work. Invocations in flight stay on the old URL and keep retrying it, so let them finish first |

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
{"transaction_id":"txn_001","amount":1000,"asset":"USD","type":"Mobile Top Up","fee":1.5,"rate":0.0015,"description":"Standard fee rate of 0.15%"}
```

Sending the **same `transaction_id` again** returns the same body, whatever the payload, and the fee is not charged again.
Once charged, it is answered from the cache or Postgres without a call to Restate.

**Another type and asset**, with Bank Transfer's minimum applied:

```bash
curl -s -X POST localhost:8081/transaction/fee -H 'Content-Type: application/json' -d '{
  "transaction_id": "txn_002", "amount": 100, "asset": "EUR", "asset_type": "FIAT",
  "type": "Bank Transfer", "state": "SETTLED - PENDING FEE", "created_at": "2023-08-30 15:42:17.610059"}'
```
```json
{"transaction_id":"txn_002","amount":100,"asset":"EUR","type":"Bank Transfer","fee":1,"rate":0.005,"description":"Fee rate of 0.5% (min 1.00 EUR)"}
```


**Errors** are `application/problem+json`. Every error the service raises itself carries a stable `code`:

| Request | Status | Body (shortened) |
|---------|--------|------------------|
| `"state": "PENDING"` | 422 | `"code":"UNSUPPORTED_REQUEST","detail":"Unsupported state 'PENDING'. Supported: 'SETTLED - PENDING FEE'","field":"state","value":"PENDING"` |
| `"amount": -5` | 400 | `"code":"INVALID_REQUEST","detail":"Invalid amount '-5'. Allowed: a number greater than 0","field":"amount","value":"-5"` |
| An id whose earlier run was killed after its claim, once Restate dropped that run | 409 | `"code":"DUPLICATE_TRANSACTION","detail":"Transaction 'txn_001' is claimed by an earlier run that has not finished","transaction_id":"txn_001"` |
| Restate unreachable | 503 | `"code":"WORKFLOW_UNAVAILABLE"`; resending later is safe |
| A run not finished within `restate.attach-timeout` | 504 | `"code":"WORKFLOW_TIMEOUT","detail":"The fee for transaction 'txn_001' is still being processed. Resend the same request later to get it","transaction_id":"txn_001"` |

Spring MVC's own errors (a body that isn't JSON, 415, 405, an unknown path) are problems too, without a `code`.

The 504 comes only when Restate itself reports the run as not finished (470 on the run's output), which includes a run
Restate is retrying because it can't reach a working endpoint; a run that finished meanwhile answers its result. The run
goes on, so send the same transaction again to get the fee once it is charged; it is charged once.

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

It brings `FeeRequest` and `FeeResponse` with their snake_case JSON names, and the errors: read an
error body as `FeeProblem`, and `toException()` gives back the `FeeException` the service raised
(`DuplicateTransactionException`, `ValidationException`, `WorkflowTimeoutException`, …), or `null` for a problem without a known `code`.

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
mvn ktlint:format     # formats the Kotlin sources: every build runs the ktlint check first and fails on a problem
mvn test              # unit tests: no Docker, no Spring context except @WebMvcTest slices
mvn verify            # + integration tests (Testcontainers Restate 1.8.1, Postgres 18.7) and Cucumber features; needs a container engine
scripts/check.sh      # the project's gate: rule checks + mvn clean verify, and every test must actually run
```

- **Unit tests** (`*Test`) cover every production class: each validator and its messages, each fee rule and building
  block (rounding, minimums, `BigDecimal` comparisons), the claim rules, the serde of every durable type, the cache of
  charged fees, the gateway's HTTP mapping, the packaged configuration. Postgres is faked in memory
  (`InMemoryFeeRecordRepository`). `TestCoverageAuditTest` fails when a production file has no test.
- **BDD features** ([`service/src/test/resources/features/`](service/src/test/resources/features)) describe the behaviour end to end against
  a real Restate and Postgres: fee calculation per type and asset, validation, transaction state (a rejected id stays
  rejected), idempotency and workflow recovery.
- **Integration tests** (`*IT`) share one Testcontainers Restate and one Postgres and use only random ports, so a Restate,
  Postgres or app already running on the machine can't interfere. They read the fee records in Postgres itself, past the
  app. `AppContainerIT` is the exception: it runs the packaged jar in a container next to a Restate and a Postgres of its
  own, on a private network, so it needs `service/target/fee-service-*.jar` (`mvn verify` builds it; in an IDE, run
  `mvn -pl service -am package -DskipTests` first).

What the recovery tests prove (each counts the **CALCULATE executions**, not just the response):

| Test | Proves |
|------|--------|
| `CrashRecoveryIT`, `workflow_recovery.feature` | A crash before or after any step boundary is retried by Restate, and the journaled steps are replayed, not run again (validation and calculation closures each run once): one fee, record `COMPLETED`. A crash right after the CLAIM or RECORD write committed, before Restate journaled it: the step runs again and finds its own write, still one record |
| `FeeRecordRepositoryIT` | Against Postgres: a claim inserts only an id without a record, in one transaction, and a claim of an existing id opens none; 10 claims of one id at once leave one, and only its token can complete it; completing with another token rolls back; every value keeps its scale |
| `DatabaseStartupIT` | With Postgres unreachable, or its `fee_record` table lacking a column, the app doesn't start, and its Restate endpoint never starts or registers |
| `EndpointRestartIT`, `workflow_recovery.feature` | Stopping and restarting the endpoint in the middle of CALCULATE keeps its deployment and completes the workflow with one fee record |
| `EndpointRegistrationIT` | The app registers itself at its advertised URL at startup, and a stop with nothing unfinished removes the registration |
| `RestateOutageIT` | A workflow frozen mid-CALCULATE completes with one fee record once Restate thaws |
| `PostgresOutageIT` | With Postgres down while the app runs: a new transaction is accepted by Restate and waits at CLAIM, then is charged once when Postgres is back; an outage between CLAIM and RECORD reports nothing charged until the record is written, then charges once (CALCULATE ran once); a charged id is still answered from the cache, then from Restate; a rejection is still answered and never stored. With Postgres frozen (connections open, nothing answers), the lookup gives up after `fee-cache.lookup-timeout` and Restate answers a charged id or a rejection within seconds |
| `AttachTimeoutIT` | A run that outlasts the attach timeout, and that Restate reports as not finished (470), answers 504 `WORKFLOW_TIMEOUT`; the same payload sent again gets the fee, calculated once |
| `AppContainerIT` | The packaged app in a container registers with Restate under its network alias and charges a fee (an unknown type gets 422 at once); a graceful stop removes the deployment; a run submitted while the app is killed waits (Restate answers 470) and is charged once when the app is back at the same URL, as the same deployment |
| `DeterminismIT` | A validation result is journaled: moving the clock between a crash and the retry doesn't change it |
| `DuplicatesIT`, `idempotency.feature` | One `transaction_id`, sequentially or 10 callers at once, with the same or different payloads, is charged once, every caller gets the first run's response, and Postgres holds exactly that one record; valid and invalid payloads at once all get the accepted payload's answer; an id held by an unfinished run gets 409 |
| `RejectedIT`, `transaction_state.feature` | A rejected id is neither charged nor recorded, and the corrected request with the same id gets the same rejection |
| `RetentionExpiredIT` | After Restate dropped a finished run, a charged id is answered from the cache, or from Postgres when it isn't cached, without a new run or a second charge; a run sent past the app is stopped at CLAIM by the record; a rejected id is charged normally |

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
order or the JSON of `FeeRequest`/`FeeResponse` is a breaking change for in-flight workflows; renaming or removing a
`fee_record` column or status breaks stored records.

## Assumptions

- Only state `SETTLED - PENDING FEE` is processed, and only `USD` and `EUR` (`FIAT`, 2 decimals). Enum values match
  exactly (`"usd"` is unsupported).
- Malformed requests are 400, well-formed but unsupported ones 422; the first failing check (in a fixed order) is reported.
- `created_at` is `yyyy-MM-dd HH:mm:ss[.fraction]` without a zone, read as **UTC**, and may not be in the future.
- `transaction_id` is 1–64 ASCII letters, digits, `_` or `-`, because it becomes part of Restate keys.
- An amount may not have more decimals than its asset (`10.001 USD` is 422); trailing zeros don't count.
- An amount may not exceed 3,000,000,000 in its asset (`3000000000.01 USD` is 422); the bound is a constant
  (`MaxAmountValidator.MAX_AMOUNT`), the same number for USD and EUR.
- `fee` is answered without trailing zeros (`1.5`, as in the challenge's sample; `5`, `10.3`), and stored at the asset's
  2 decimals (`1.50`), the same value; `amount` is echoed as sent.
- One `transaction_id` is processed once: sending it again, with any payload, returns the first run's response (the fee or
  the rejection) without charging again. A rejected id can be charged once Restate drops its run (1 day by default).
- Charging a fee means recording it in Postgres as the transaction's charged fee; there is no external wallet.
- Bank Transfer has a minimum and no maximum.

## Future work

- **Kubernetes:** add **readiness** and **liveness** probes. Actuator is not added yet; it needs approval as a dependency.
- **Several instances behind one URL:** they share one deployment, and the first to stop with nothing unfinished removes
  it for all of them until one restarts. With more than one instance, register as a deploy step (or with the Restate
  operator) instead of at startup.
- **Graceful shutdown of the endpoint:** stopping it today closes open connections, and Restate retries the interrupted
  invocations (safe, but noisy). Stop accepting new invocations and drain the running ones first.
- **Deployments per version:** register each release under its own URL and let the old one drain instead of re-registering
  with `force`.
- **Cancelled, killed or paused workflows** leave the record `IN_PROGRESS`, so once Restate drops the run the id gets 409
  until an operator acts.
- **One Jackson generation:** Spring MVC is on Jackson 3, the Restate SDK's serde still on Jackson 2. Once the SDK has a
  Jackson 3 serde, move to it and drop the Jackson 2 serializer of `@PlainBigDecimal` and the Jackson 2 dependencies.
