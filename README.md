# fee-service

A Kotlin + Spring Boot service that calculates, charges and records the fee of a transaction. The work runs as a
durable [Restate](https://restate.dev) workflow, so it survives crashes and restarts and never charges twice.

```
POST /transaction/fee          → 200 with the fee (or 202 while it is still running)
GET  /transaction/fee/{id}     → the stored fee record: IN_PROGRESS, COMPLETED or FAILED
```

- [Requirements](requirements.md): the challenge
- [Design](docs/design.md): goal, fee table, how Restate is used, class design, wiring, call graph, guarantees
- [Decisions](docs/decisions.md): the decision log (D1…) and the open questions
- [Tasks](tasks.md): the implementation checklist
- [Dev environment](dev-setup/README.md): the dev Restate and the dev container (`podman compose`)
- [How AI was used](docs/ai-usage.md): the plan files, the spike, the task runner and its gate, what a person reviews
- [Live demo](docs/demo.md): `demo/demo.sh`, self-registration → a fee → resubmit → `kill -9` mid-charge → recovery → the journal

## What it does

`POST /transaction/fee` receives a transaction and hands it to the `FeeWorkflow` in Restate. The workflow:

1. **validates** the request (required fields, formats, only `SETTLED - PENDING FEE`, only `USD`/`EUR` `FIAT`, a known type),
2. **claims** the `transaction_id`, so one transaction is charged at most once, whatever the payloads and callers,
3. **calculates** the fee with the rule of the transaction's type,
4. **charges** the fee to the wallet (idempotent by key),
5. **records** the result permanently in Restate state.

The caller waits for the result (up to `restate.attach-timeout`) and gets the fee. Sending the same payload again
returns the same response without a second charge. A rejected request does not use up its `transaction_id`: the
corrected payload is processed normally. Nothing is stored outside Restate.

```json
// POST /transaction/fee                          // 200 OK
{                                                 {
  "transaction_id": "txn_001",                      "transaction_id": "txn_001",
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
   │  1. transaction_id present and well-formed?  (needed to build the key; otherwise 400)
   │  2. key = txn_001:<payloadHash>
   │  3. submit FeeWorkflow/<key>/run, then attach  (a duplicate gets PREVIOUSLY_ACCEPTED
   │     and the first run's result; no result within attach-timeout → 202)
   ▼
Restate server (external Docker) ── journals every step ───────────────────────────┐
   │ invokes the app's endpoint (port 9080)                                         │
   ▼                                                                                │
FeeWorkflow.run(input)                                                              │
   step VALIDATE   ctx.run { RequestValidator.validate(request) }   → Rejected? ────┤
   step MAP        TransactionMapper.toTransaction(request)  (pure, deterministic)  │
   step CLAIM      TransactionFeeRecord/txn_001.claim(hash)   → Conflict? / Done? ──┤
   step CALCULATE  ctx.run { FeeCalculator.calculate(tx) }                       ───┤
   step CHARGE     ctx.run { WalletGateway.charge("fee-txn_001", fee) }          ───┤
   step RECORD     TransactionFeeRecord/txn_001.complete(response)               ───┘
   return FeeOutcome.Charged(response)
```

- **`FeeWorkflow`** (a Restate *workflow*, keyed by `{transaction_id}:{payloadHash}`) runs each request exactly once.
  The same payload maps to the same key, so a duplicate attaches to the first run instead of starting a second one.
- **`TransactionFeeRecord`** (a Restate *virtual object*, keyed by `transaction_id`) holds the permanent fee record. Its
  handlers run one at a time per key, so two different payloads for one transaction can't both claim it: one is charged,
  the others get 409.
- Everything non-deterministic (the clock in validation, the fee numbers, the wallet call) runs inside `ctx.run`, so its
  result is journaled and replayed, never recomputed.

**Crash example.** The app dies right after CHARGE. Restate retries the invocation, and the endpoint (restarted, or a new
instance) receives the journal. VALIDATE, CLAIM, CALCULATE and CHARGE are **replayed from the journal and not executed
again**, so the wallet is not charged twice. RECORD then runs, and the waiting caller gets the result. If the app dies
*while* the wallet call is running, that step was not journaled yet, so it runs again; the wallet's idempotency key
(`fee-{transaction_id}`) keeps it to one charge.

**Startup.** The app starts its Restate endpoint on port 9080 and then registers it with Restate's admin API: it registers
a missing endpoint, re-registers one whose services changed, and leaves an up-to-date one alone. If Restate isn't
reachable, the app still starts, logs a WARN and keeps retrying in the background. Until then, requests get 503.

## Architecture

```
config/            RestateProperties, FeeProperties, FakeWalletProperties   every @ConfigurationProperties class: one file per
                                                                            top-level section of application.yml
api/               TransactionFeeController, ApiExceptionHandler            HTTP only, errors as RFC 7807 ProblemDetail
application/       FeeService, WorkflowKey, FeeException                    thin gateway: key → submit/attach → HTTP result
workflow/          FeeWorkflow, its durable types, StepHook                 the Restate workflow: every business step
record/            TransactionFeeRecord, FeeRecord, ClaimDecider            the Restate virtual object: one fee per transaction_id
restate/           RestateEndpointLifecycle, RestateRegistrar, …            Restate plumbing: endpoint, self-registration, clients
validation/        TransactionValidator, RequestValidator, ValidationError  run inside the workflow (VALIDATE)
validation/rule/   one TransactionValidator bean per check, by @Order
fee/               FeeRule, FeeCalculator                                   run inside the workflow (CALCULATE)
fee/formula/       PercentageFee, FlatFee, …                                the building blocks
fee/rule/          one FeeRule bean per transaction type
domain/            Transaction and its enums, TransactionMapper             pure Kotlin
contract/          FeeRequest, FeeResponse, FeeStatusResponse               the JSON shared by HTTP and Restate
wallet/            WalletGateway, FakeWalletGateway                         the charge (an in-memory fake)
```

The controller calls only `FeeService`, `FeeService` holds no business logic, and every business step runs inside
`FeeWorkflow`. Spring builds every object, including the two Restate handlers, so constructor injection, `@Order` and the
injected rule and validator lists work behind the workflow too.

The full class design, the wiring, the call graph and the idempotency and recovery guarantees are in
[`docs/design.md`](docs/design.md). Why each choice was made, and what is still open, is in
[`docs/decisions.md`](docs/decisions.md).

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

   Restate must reach the app's endpoint at `restate.advertised-url` (default `http://host.docker.internal:9080`; the
   `--add-host` makes that name work on Linux too).

2. Start the app:

   ```bash
   mvn spring-boot:run
   ```

   The API listens on port **8081**, the Restate endpoint on **9080**. Registration is automatic; look for
   `RestateRegistrar : Restate endpoint http://…:9080: registered …` (or `already registered`) in the log.

Inside the dev container the Restate addresses come from environment variables (`RESTATE_INGRESS_URL=http://restate:8080`,
`RESTATE_ADMIN_URL=http://restate:9070`, `RESTATE_ADVERTISED_URL=http://dev:9080`), so `mvn spring-boot:run` works unchanged.

### Watch a crash recovery yourself

`demo/demo.sh` does all of this step by step and checks each claim. See [`docs/demo.md`](docs/demo.md). By hand: the
fake wallet can wait before it charges, which leaves time to kill the app in the middle of CHARGE:

1. Start the app with `WALLET_FAKE_DELAY=20s mvn spring-boot:run`.
2. Send a request (see below) and kill the app (`kill -9`) while it waits.
3. Start the app again. Restate retries the invocation on the new process (the log shows `Invocation started` again):
   VALIDATE, CLAIM and CALCULATE come from the journal, and only CHARGE and RECORD run.
4. Send the same payload again: it attaches to the same workflow and returns the same fee. `GET /transaction/fee/{id}`
   shows `COMPLETED`.

## Configuration

Every tunable value comes from Spring Boot's externalized configuration, so a change needs **a restart, not a rebuild**.
The packaged [`application.yml`](src/main/resources/application.yml) holds the defaults. Overrides, highest priority first:
command-line arguments (`--restate.ingress-url=…`), environment variables, a `config/application.yml` in the directory the
app starts from (or `--spring.config.additional-location=file:/etc/fee-service/`), the packaged file.

| Key | Environment variable | Default | What it is |
|-----|----------------------|---------|------------|
| `server.port` | `SERVER_PORT` | `8081` | HTTP API port |
| `restate.ingress-url` | `RESTATE_INGRESS_URL` | `http://localhost:8080` | Restate ingress, used by `FeeService` |
| `restate.admin-url` | `RESTATE_ADMIN_URL` | `http://localhost:9070` | Restate admin API, used for registration |
| `restate.endpoint-port` | `RESTATE_ENDPOINT_PORT` | `9080` | port the Restate endpoint listens on |
| `restate.advertised-url` | `RESTATE_ADVERTISED_URL` | `http://host.docker.internal:9080` | the endpoint's URL as Restate reaches it |
| `restate.attach-timeout` | `RESTATE_ATTACH_TIMEOUT` | `10s` | how long `POST` waits for the result before answering 202 |
| `restate.registration.enabled` | `RESTATE_REGISTRATION_ENABLED` | `true` | self-registration on startup |
| `restate.registration.max-attempts` | `RESTATE_REGISTRATION_MAX_ATTEMPTS` | `10` | attempts before the WARN (retries continue) |
| `restate.registration.backoff` | `RESTATE_REGISTRATION_BACKOFF` | `2s` | wait between registration attempts |
| `spring.http.client.connect-timeout`, `.read-timeout` | `SPRING_HTTP_CLIENT_CONNECTTIMEOUT`, `…_READTIMEOUT` | `5s`, `30s` | timeouts of the admin API calls |
| `fee.<type>.rate`, `.min`, `.fixed` | (use a file) | see [Fee table](#fee-table) | fee numbers per transaction type |
| `wallet.fake.delay` | `WALLET_FAKE_DELAY` | `0s` | the fake wallet's wait before it charges (demo only) |

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
| Fee numbers | Safe. A workflow that already passed CALCULATE keeps its journaled fee; everything else uses the new numbers. Completed records are never recalculated: resending a charged payload returns the original response |
| `restate.ingress-url`, `admin-url`, `attach-timeout`, `registration.*`, `server.port`, timeouts, `wallet.fake.delay` | Safe |
| `restate.advertised-url` | Registers a new deployment. Invocations in flight stay on the old URL and keep retrying it, so let them finish first |
| `restate.endpoint-port` | Safe if the port in `advertised-url` changes with it (unless something forwards between the two) |

Renaming a key or a variable breaks existing override files and environments.

## API examples

The responses below were recorded against a running app.

Every scenario below, and more (each type, duplicate, conflict, rejected → corrected, invalid payloads, status
lookups), is ready to run in [`http/requests.http`](http/requests.http) with the IntelliJ HTTP Client or the VS Code REST
Client. Its `# Expect:` lines give each answer, and `HttpRequestsFileIT` runs the file in the tests, so they stay true.

**Charge a fee** (200):

```bash
curl -s -X POST localhost:8081/transaction/fee -H 'Content-Type: application/json' -d '{
  "transaction_id": "txn_001", "amount": 1000, "asset": "USD", "asset_type": "FIAT",
  "type": "Mobile Top Up", "state": "SETTLED - PENDING FEE", "created_at": "2023-08-30 15:42:17.610059"}'
```
```json
{"transaction_id":"txn_001","amount":1000,"asset":"USD","type":"Mobile Top Up","fee":1.50,"rate":0.0015,"description":"Standard fee rate of 0.15%"}
```

Sending the **same payload again** returns the same body, and the wallet is not charged again.

**Another type and asset**, with Bank Transfer's minimum applied:

```bash
curl -s -X POST localhost:8081/transaction/fee -H 'Content-Type: application/json' -d '{
  "transaction_id": "txn_002", "amount": 100, "asset": "EUR", "asset_type": "FIAT",
  "type": "Bank Transfer", "state": "SETTLED - PENDING FEE", "created_at": "2023-08-30 15:42:17.610059"}'
```
```json
{"transaction_id":"txn_002","amount":100,"asset":"EUR","type":"Bank Transfer","fee":1.00,"rate":0.005,"description":"Fee rate of 0.5% (min 1.00 EUR)"}
```

**Look up the record**:

```bash
curl -s localhost:8081/transaction/fee/txn_001
```
```json
{"transaction_id":"txn_001","status":"COMPLETED","amount":1000,"asset":"USD","type":"Mobile Top Up","fee":1.50,"rate":0.0015,"description":"Standard fee rate of 0.15%"}
```

**Errors** are `application/problem+json`:

| Request | Status | Body (shortened) |
|---------|--------|------------------|
| `"state": "PENDING"` | 422 | `"detail":"Unsupported state 'PENDING'. Supported: 'SETTLED - PENDING FEE'","field":"state","value":"PENDING"` |
| `"amount": -5` | 400 | `"detail":"Invalid amount '-5'. Allowed: a number greater than 0","field":"amount","value":"-5"` |
| `txn_001` again with `"amount": 2000` | 409 | `"detail":"Transaction 'txn_001' was already charged or is being processed with a different payload","transaction_id":"txn_001"` |
| `GET /transaction/fee/unknown` | 404 | `"detail":"No fee record for transaction 'unknown'","transaction_id":"unknown"` |
| a charge the wallet declines | 422 | `"transaction_id"` and the wallet's `"reason"` |
| Restate unreachable | 503 | resending the same payload later is safe |

A run that takes longer than `restate.attach-timeout` answers **202** with `Location: /transaction/fee/{id}` and
`{"transaction_id":"…","status":"IN_PROGRESS"}`; poll the location or resend the same payload.

A rejected request can be corrected and sent again with the same `transaction_id` (e.g. `PENDING` → `SETTLED - PENDING FEE`).

## Tests

```bash
mvn test              # unit tests: no Docker, no Spring context except @WebMvcTest slices
mvn verify            # + integration tests (Testcontainers Restate 1.8.1) and Cucumber features; needs a container engine
scripts/check.sh      # the project's gate: rule checks + mvn clean verify, and every test must actually run
```

- **Unit tests** (`*Test`) cover every production class: each validator and its messages, each fee rule and building
  block (rounding, minimums, `BigDecimal` comparisons), the claim rules, the payload hash, the serde of every durable type,
  the gateway's HTTP mapping, the registrar's retries. `TestCoverageAuditTest` fails when a production file has no test.
- **BDD features** ([`src/test/resources/features/`](src/test/resources/features)) describe the behaviour end to end against
  a real Restate: fee calculation per type and asset, validation, transaction state (rejected → corrected), idempotency
  and workflow recovery.
- **Integration tests** (`*IT`) share one Testcontainers Restate and use only random ports, so a Restate or an app already
  running on the machine can't interfere.

What the recovery tests prove (each asserts the wallet's **charge count**, not just the response):

| Test | Proves |
|------|--------|
| `CrashRecoveryIT`, `workflow_recovery.feature` | A crash before or after any step boundary is retried by Restate, and the journaled steps are replayed, not run again (validation, calculation and charge closures each run once): one charge, record `COMPLETED` |
| `EndpointRestartIT`, `workflow_recovery.feature` | Stopping and restarting the endpoint in the middle of CHARGE completes the workflow with one charge |
| `RestateOutageIT` | With Restate frozen, a request gets 503 and the same payload is charged once after the thaw; a workflow frozen mid-CHARGE completes with one charge |
| `TransientWalletFailureIT` | A wallet that fails 3 times is retried to exactly one charge |
| `WalletDeclineIT` | A declined charge is recorded as `FAILED`, answered with 422 and not retried on resubmission |
| `DeterminismIT` | A validation result is journaled: moving the clock between a crash and the retry doesn't change it |
| `DuplicatesIT`, `idempotency.feature` | The same payload, sequentially or 10 callers at once, is charged once; different payloads for one id charge one and conflict the rest |
| `RetentionExpiredIT` | After Restate dropped a finished run, the same payload returns the stored response without charging again |
| `StartupRegistrationIT` | A fresh Restate gets the endpoint registered on startup; a second start changes nothing; an app started while Restate is down starts with a WARN and registers once Restate is back |

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
| a real wallet | a `WalletGateway` implementation that replaces `FakeWalletGateway` (it must be idempotent by key) |

Details: [Extension points in `docs/design.md`](docs/design.md#extension-points). Changing a handler signature, the step
order or the JSON of `FeeRequest`/`FeeResponse` is a breaking change for in-flight workflows and stored records.

## Assumptions

- Only state `SETTLED - PENDING FEE` is processed, and only `USD` and `EUR` (`FIAT`, 2 decimals). Enum values match
  exactly (`"usd"` is unsupported).
- Malformed requests are 400, well-formed but unsupported ones 422; the first failing check (in a fixed order) is reported.
- `created_at` is `yyyy-MM-dd HH:mm:ss[.fraction]` without a zone, read as **UTC**, and may not be in the future.
- `transaction_id` is 1–64 ASCII letters, digits, `_` or `-`, because it becomes part of Restate keys and the `GET` path.
- An amount may not have more decimals than its asset (`10.001 USD` is 422); trailing zeros don't count, and `1000` and
  `1000.00` are the same payload.
- `fee` is returned at the asset's scale (`1.50`, which equals the challenge's `1.5`); `amount` is echoed as sent.
- The same `transaction_id` with a different payload after a charge is a **409** (open question 1); a rejected payload
  doesn't use up the id.
- The wallet is an in-memory fake, idempotent by key. Its charges are lost on restart; the fee records in Restate are not.
- Bank Transfer has a minimum and no maximum.

## Future work

- **Kubernetes:** expose `RegistrationStatus` as an Actuator **readiness** indicator (not ready until the endpoint is
  registered; a disabled registration counts as ready) and add a **liveness** probe. Actuator is not added yet; it needs
  approval as a dependency.
- **Graceful shutdown of the endpoint:** stopping it today closes open connections, and Restate retries the interrupted
  invocations (safe, but noisy). Stop accepting new invocations and drain the running ones first.
- **Deployments per version:** register each release under its own URL and let the old one drain instead of re-registering
  with `force` (open question 4).
- **Cancelled, killed or paused workflows** leave the record `IN_PROGRESS`, so the id gets 409 until an operator acts
  (open question 5).
- A real wallet integration; the Restate SDK's newer handler API before an upgrade removes the current one (D29); Spring
  Boot 4 with Jackson 3 (D25).
