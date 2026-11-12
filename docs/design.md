# fee-service — Design

What the service does and how it is built. Decisions (`D1`…) and open questions: [`decisions.md`](decisions.md).
Task checklist: [`../tasks.md`](../tasks.md). Agent and contributor rules: [`../AGENTS.md`](../AGENTS.md).

## Goal

A Spring Boot service written in **Kotlin**. It exposes `POST /transaction/fee` and hands the request to a **Restate
workflow**. The workflow **validates**, **calculates**, **charges** and **records** the fee for the transaction. Every step
is journaled by Restate, so the processing **survives crashes and restarts**: it resumes where it stopped and never charges twice.
The result is persisted **in Restate**.

Principles:
- **All business logic runs inside the workflow.** The Spring API is a thin gateway.
- **Minimal layers, minimal dependencies.** Anything `requirements.md` does not require is left out.
- **Easy to extend.** A new validation or fee rule is one new class.
- **Easy to test.** Constructor injection everywhere. Restart and recovery are tested explicitly.
- **Self-registering.** On startup the app registers its Restate endpoint if Restate doesn't know it yet.
- **Configurable without rebuilding.** Fee numbers and Restate addresses come from external configuration, and a restart applies them.

Reference example (Mobile Top Up, 0.15%):

```
Request                                     Response
{                                           {
  "transaction_id": "txn_001",                "transaction_id": "txn_001",
  "amount": 1000,                             "amount": 1000,
  "asset": "USD",                             "asset": "USD",
  "asset_type": "FIAT",                       "type": "Mobile Top Up",
  "type": "Mobile Top Up",                    "fee": 1.5,
  "state": "SETTLED - PENDING FEE",           "rate": 0.0015,
  "created_at": "2023-08-30 15:42:17.610059"  "description": "Standard fee rate of 0.15%"
}                                           }
```

---

## Fee table

The numbers below are the defaults in the packaged `application.yml`. They can be changed with a restart, without a rebuild
(see [Configuration](#configuration), D22, D23). Fixed amounts are in the transaction's asset (USD or EUR, same numbers).
Fees are rounded `HALF_EVEN` to 2 decimals. Bank Transfer has a minimum and, for now, no maximum.

Descriptions are **built from the configured numbers and the transaction's asset** (`{asset}` below), so they never go stale.
A EUR Bank Transfer reads `Fee rate of 0.5% (min 1.00 EUR)`.

| Type | Rule bean | Building block | `rate` | `description` | 1000 USD → fee |
|------|-----------|----------------|--------|---------------|----------------|
| `Mobile Top Up` | `MobileTopUpFeeRule` | `PercentageFee(0.0015)` | `0.0015` | `Standard fee rate of 0.15%` | **1.5** |
| `Bank Transfer` | `BankTransferFeeRule` | `PercentageWithMinimumFee(0.005, min 1.00)` | `0.005` | `Fee rate of 0.5% (min 1.00 {asset})` | 5.0 (100 → 1.0, 10000 → 50.0) |
| `Merchant Payment` | `MerchantPaymentFeeRule` | `PercentagePlusFixedFee(0.01, fixed 0.30)` | `0.01` | `Fee rate of 1% plus 0.30 {asset} fixed` | 10.3 |
| `Cash Withdrawal` | `CashWithdrawalFeeRule` | `FlatFee(2.00)` | `0` | `Flat fee of 2.00 {asset}` | 2.0 |

The same defaults in `application.yml`:

```yaml
fee:
  mobile-top-up:    { rate: "0.0015" }
  bank-transfer:    { rate: "0.005", min: "1.00" }
  merchant-payment: { rate: "0.01",  fixed: "0.30" }
  cash-withdrawal:  { fixed: "2.00" }
```

---

## How Restate is used

```
POST /transaction/fee
   ▼
Spring app: TransactionFeeController → FeeService           (thin gateway, no business logic)
   │  1. transaction_id present and well-formed?  (needed to build the key; otherwise 400)
   │  2. key = txn_001:<payloadHash>
   │  3. submit FeeWorkflow/<key>/run, then attach  (a duplicate gets PREVIOUSLY_ACCEPTED
   │     and the first run's result, D30; no result within attach-timeout → 202)
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

**Crash recovery example.** The app dies right after CHARGE. Restate retries the invocation, and the endpoint (restarted, or
a new instance) receives the journal. VALIDATE, CLAIM, CALCULATE and CHARGE are **replayed from the journal and not
executed again**, so the wallet is not charged twice. RECORD then runs, and the waiting HTTP caller gets the result.

**Startup.** `RestateEndpointLifecycle` starts the endpoint on port 9080. `RestateRegistrar` then makes sure Restate
knows this endpoint (it registers it if missing and re-registers it if the services changed). Only then is the app fully ready.
Restate ignores changed services when a known URI is re-registered without `force`, so the registrar detects a change itself.
It asks Restate to discover the endpoint in a dry run and compares that with the registered deployment (D31).

---

## Class design

Base package: `com.example.fee`.

```
com.example.fee
├── FeeServiceApplication               @SpringBootApplication + @ConfigurationPropertiesScan + main(); Clock bean
│
├── api/                                HTTP only
│   ├── TransactionFeeController        POST /transaction/fee (200, or 202 + Location), GET /transaction/fee/{id} → calls only FeeService
│   └── ApiExceptionHandler             @RestControllerAdvice: FeeException and Spring MVC errors → ProblemDetail (D60)
│
├── contract/                           DTOs shared by HTTP and Restate (pure Kotlin + Jackson annotations, snake_case), D21
│   ├── FeeRequest                      all fields nullable; enums/dates as String, amount as BigDecimal. Journaled as the workflow input
│   ├── FeeResponse                     transaction_id, amount, asset, type, fee, rate, description. Stored permanently in TransactionFeeRecord
│   └── FeeStatusResponse               GET body: transaction_id, status, the FeeResponse fields once COMPLETED, failure_reason once FAILED
│                                       (HTTP only, never stored)
│
├── application/                        thin gateway to Restate
│   ├── FeeService                      key pre-check → WorkflowKey → submit/attach (bounded by attachTimeout) → FeeOutcome → FeeSubmission or FeeException
│   ├── FeeSubmission                   sealed: Completed(response) → 200, Processing(transactionId) → 202 + Location
│   ├── WorkflowKey                     value class: "{transactionId}:{payloadHash}"
│   ├── PayloadHash                     SHA-256 of the canonical FeeRequest (amount normalized: 1000 == 1000.00)
│   └── FeeException                    sealed: ValidationException (400/422), DuplicateTransactionException (409),
│                                       ChargeFailedException (422), TransactionNotFoundException (404, GET), WorkflowUnavailableException (503).
│                                       Gateway only: the workflow returns FeeOutcome and never throws these
│
├── validation/                         executed inside the workflow
│   ├── TransactionValidator            interface: validate(request): ValidationError?
│   ├── ValidationError                 data class(field, value, message, kind: INVALID | UNSUPPORTED)
│   ├── ValidationOrder                 object of `const val`s spaced by 100 (`@Order` needs compile-time constants)
│   ├── RequestValidator                sorts by @Order (so hand-built lists in unit tests are ordered too), fail-fast, returns ValidationError?
│   ├── RequiredFieldsValidator         @Order(100)  → 400
│   ├── TransactionIdValidator          @Order(200)  → 400   (also reused by FeeService for the key pre-check)
│   ├── AmountValidator                 @Order(300)  → 400
│   ├── CreatedAtValidator              @Order(400)  → 400   (Clock)
│   ├── StateValidator                  @Order(500)  → 422
│   ├── AssetValidator                  @Order(600)  → 422
│   ├── AssetTypeValidator              @Order(700)  → 422
│   ├── TransactionTypeValidator        @Order(800)  → 422   (FeeCalculator.supports)
│   └── AmountScaleValidator            @Order(900)  → 422   (a rule of the asset: needs its decimals, so it runs after AssetValidator)
│
├── domain/                             pure Kotlin
│   ├── Transaction                     data class
│   ├── TransactionMapper               FeeRequest → Transaction? (after validation; never throws; null if unvalidated, D39)
│   ├── CreatedAtFormat                 parseOrNull(created_at): the one created_at format, shared with CreatedAtValidator
│   └── TransactionType / TransactionState / Asset / AssetType    enums + UNKNOWN + fromValue()
│
├── fee/
│   ├── FeeQuote, FeeRule
│   ├── PercentageFee, PercentageWithMinimumFee, PercentagePlusFixedFee, FlatFee      building blocks
│   ├── MobileTopUpFeeRule, BankTransferFeeRule, MerchantPaymentFeeRule, CashWithdrawalFeeRule   @Component, built from its *FeeProperties
│   ├── MobileTopUpFeeProperties, …     one per rule, @ConfigurationProperties("fee.<type>"): its numbers (rate, min, fixed).
│   │                                   init { require(…) } fails startup on a bad value (D23)
│   └── FeeCalculator                   List<FeeRule> → map by type; calculate(tx), supports(type); startup check
│
├── restate/
│   ├── FeeWorkflow                     @Component @Workflow run(FeeWorkflowInput): FeeOutcome; @Shared status(): WorkflowStep? (D50)
│   │                                   ctor: RequestValidator, TransactionMapper, FeeCalculator, WalletGateway, StepHook
│   │                                   (the ctx-based codegen API of SDK 2.9.3, deprecated but kept, D29)
│   ├── FeeWorkflowInput                data class(request: FeeRequest, payloadHash)
│   ├── FeeOutcome                      sealed: Charged(response), Rejected(error), Conflict(transactionId), Failed(reason)
│   ├── WorkflowStep                    enum: VALIDATE, MAP, CLAIM, CALCULATE, CHARGE, RECORD, DONE
│   ├── StepHook                        interface before(step, key)/after(step, key); NoOpStepHook is the default @Component
│   ├── TransactionFeeRecord            @Component @VirtualObject (key = transaction_id): claim(hash), complete(response), fail(reason); @Shared get()
│   ├── FeeRecord, FeeRecordStatus      stored state (IN_PROGRESS / COMPLETED / FAILED, payloadHash, response, failureReason)
│   ├── ClaimDecider                    pure function (FeeRecord?, hash) → ClaimResult (unit-testable without Restate)
│   ├── ClaimResult                     sealed: New, AlreadyCompleted(response), InProgress, Conflict
│   ├── RestateEndpointLifecycle        SmartLifecycle: binds the FeeWorkflow and TransactionFeeRecord beans, starts/stops the endpoint server (port 9080)
│   │                                   on its own Vert.x instance, reached by reflection (vertx-core is runtime-only, D32)
│   ├── RestateRegistrar                ApplicationReadyEvent: ensure the deployment is registered (missing → register, changed → force);
│   │                                   retries on Boot's TaskScheduler every `backoff`, WARN + FAILING after maxAttempts (D55);
│   │                                   "changed" = dry-run discovery differs from the registered services (D31)
│   ├── RegistrationStatus              bean: PENDING / REGISTERED / FAILING + last error; the future readiness indicator (D15)
│   ├── RestateAdminClient              Spring RestClient wrapper: listDeployments(), deployment(id), register(uri, force, dryRun);
│   │                                   failures → RestateAdminException; response types in RestateAdminTypes (D54)
│   ├── RestateConfig                   @Configuration: ingress Client bean; keyed client factories (key → generated ingress client),
│   │                                   because generated clients are created per key and FeeService must be testable with mocks
│   ├── FeeWorkflowClients, TransactionFeeRecordClients   the keyed factories: fun interface forKey(key) → generated IngressClient (D52)
│   └── RestateProperties               @ConfigurationProperties("restate"): ingressUrl, adminUrl, endpointPort, advertisedUrl, attachTimeout, registration.{enabled, maxAttempts, backoff}
│   (generated by sdk-api-gen: FeeWorkflowClient, TransactionFeeRecordClient)
│
└── wallet/
    ├── WalletGateway                   interface charge(idempotencyKey, amount, asset): ChargeReceipt
    ├── ChargeDeclinedException         business failure; rethrown as TerminalException inside the CHARGE ctx.run → FeeOutcome.Failed
    ├── FakeWalletGateway               @Component, in-memory, idempotent by key, chargeCount(key); waits wallet.fake.delay first (demo, D24); logs each wait and charge (D74)
    ├── FakeWalletProperties            @ConfigurationProperties("wallet.fake"): delay (D24, D46)
    └── ChargeReceipt                   data class
```

### Wiring: Spring builds, Restate invokes

- Spring creates every object. `FeeWorkflow` and `TransactionFeeRecord` are ordinary `@Component` beans with constructor
  injection. `RestateEndpointLifecycle` binds those two instances to the endpoint, so Restate calls methods on the objects Spring built.
- Spring annotations therefore keep working behind the workflow. `@Component`, `@Order` and the injected `List<TransactionValidator>` /
  `List<FeeRule>` are resolved once at startup, and Restate never sees them. Restate annotations (`@Workflow`, `@VirtualObject`,
  `@Shared`) appear only on the two handler classes.
- Handler beans are singletons shared by all invocations, so they hold no mutable state. Per-invocation state lives in `ctx`.
- Everything that crosses Restate's serde (handler inputs and outputs, `ctx.run` results, VO state) is part of the durable contract.
  That includes `FeeWorkflowInput`, `FeeOutcome`, `ValidationError`, `ClaimResult`, `FeeRecord`, and the `contract` DTOs inside them (D21).
  Restate serializes them with its own Jackson mapper, not Spring's (D35), and that mapper rejects unknown properties.
  Sealed types need `@JsonTypeInfo` with **explicit `@JsonSubTypes` names**, so the stored type ids don't depend on class names.
  Subtypes without fields are `data object`: deserialization returns a new instance that is `==` to the object but not `===`, so compare with `==`, `is` or `when`.

### Call graph (who calls whom)

```
TransactionFeeController ──▶ FeeService (only)

FeeService
  ├─▶ TransactionIdValidator.validate(request)   key pre-check only (400 if bad)
  ├─▶ PayloadHash.of(request) → WorkflowKey
  ├─▶ FeeWorkflowClient(ingress).submit(key, input) → attach  (also when PREVIOUSLY_ACCEPTED, D30)
  └─▶ FeeOutcome → FeeSubmission | throw FeeException
      (GET /{id} → TransactionFeeRecordClient(ingress).get(id) → FeeStatusResponse)

  ─ ─ ─ ─ Restate server (external) invokes the app's endpoint (port 9080) ─ ─ ─ ─

FeeWorkflow.run(ctx, input)          StepHook.before/after around every step
  ├─ VALIDATE  ctx.run ─▶ RequestValidator ─▶ [TransactionValidator…] (@Order, fail-fast)
  ├─ MAP       ─▶ TransactionMapper
  ├─ CLAIM     ─▶ TransactionFeeRecordClient(ctx).claim(hash) ─▶ ClaimDecider
  ├─ CALCULATE ctx.run ─▶ FeeCalculator ─▶ FeeRule
  ├─ CHARGE    ctx.run ─▶ WalletGateway   (decline → TerminalException, thrown inside the closure)
  └─ RECORD    ─▶ TransactionFeeRecordClient(ctx).complete(response) | fail(reason)

Startup: RestateEndpointLifecycle.start() → (ApplicationReadyEvent) RestateRegistrar ─▶ RestateAdminClient
Errors:  ApiExceptionHandler ◀── Spring, for FeeException
```

Rules:
1. **The controller calls only `FeeService`. `FeeService` contains no business logic;** it routes and maps.
2. **Every business step runs inside `FeeWorkflow`.** Non-deterministic work (clock, wallet, I/O) goes inside `ctx.run`. So does
   reading configuration that affects a decision (the fee numbers), so a restart with new values can't make a replay diverge.
3. **Restate is reached only through the generated clients** (ingress clients in `FeeService`, `ctx` clients in the workflow) and through `RestateAdminClient` for registration.
4. **`contract`, `domain`, `fee`, `validation` and `ClaimDecider` are pure.** They are unit-tested without Restate.
5. **The workflow returns `FeeOutcome` for business results** and throws only for infrastructure problems (which Restate retries) or for `TerminalException` (charge declined).

### Extension points

| To add… | Do this |
|---------|---------|
| a validation rule | a `@Component @Order(ValidationOrder.X)` `TransactionValidator` + a unit test (it runs in the workflow automatically) |
| a transaction type | an enum value + a `@Component` `FeeRule` + its `*FeeProperties` + a `fee.<type>` section in `application.yml` + a unit test + a BDD outline row |
| a fee formula | a new building block implementing `FeeRule` |
| an asset | an enum value in `Asset` (with its decimals) |
| a workflow step | a `WorkflowStep` value + a step in `FeeWorkflow` wrapped with `StepHook` (recovery tests then cover it) |
| a real wallet | a `WalletGateway` implementation that replaces the fake |
| a tunable value | a field in the matching `@ConfigurationProperties` class + its default in `application.yml` (+ a `${ENV_VAR:…}` placeholder for an operational setting) |

---

## Configuration

Every tunable value comes from Spring Boot's externalized configuration, so changing it needs **a restart, not a rebuild** (D22).
The packaged `application.yml` holds the defaults and is the only place they are written down. Kotlin code doesn't repeat them.
Overrides, highest priority first:

1. command-line arguments: `--restate.ingress-url=http://…`
2. environment variables: `RESTATE_INGRESS_URL=http://…`
3. `config/application.yml` in the directory the app starts from, or in a directory given by
   `--spring.config.additional-location=file:/etc/fee-service/`. It needs only the keys that change; the rest keep their defaults
4. the packaged `application.yml`

On Kubernetes the file comes from a ConfigMap mounted at `config/`, and the variables come from `env`. There is no hot reload,
because a restart is enough.

| Key | Environment variable | What it is |
|-----|----------------------|------------|
| `server.port` | `SERVER_PORT` | HTTP API port |
| `restate.ingress-url` | `RESTATE_INGRESS_URL` | Restate ingress, used by `FeeService` |
| `restate.admin-url` | `RESTATE_ADMIN_URL` | Restate admin API, used by `RestateRegistrar` |
| `restate.endpoint-port` | `RESTATE_ENDPOINT_PORT` | port the Restate endpoint listens on |
| `restate.advertised-url` | `RESTATE_ADVERTISED_URL` | the endpoint's URL as Restate reaches it; sent at registration |
| `restate.attach-timeout` | `RESTATE_ATTACH_TIMEOUT` | how long `POST` waits for the result before answering 202 |
| `restate.registration.enabled`, `.max-attempts`, `.backoff` | `RESTATE_REGISTRATION_ENABLED`, `…_MAX_ATTEMPTS`, `…_BACKOFF` | registration switch and retry (D15) |
| `spring.http.client.connect-timeout`, `.read-timeout` | `SPRING_HTTP_CLIENT_CONNECTTIMEOUT`, `…_READTIMEOUT` | timeouts of the admin API calls (D54) |
| `fee.<type>.rate`, `.min`, `.fixed` | (use a file) | fee numbers per transaction type, see the [fee table](#fee-table) |
| `wallet.fake.delay` | `WALLET_FAKE_DELAY` | how long the fake wallet waits before it charges; `0s`, raised only for the demo (D24) |

The `restate.*` keys use explicit placeholders in `application.yml`, e.g. `ingress-url: ${RESTATE_INGRESS_URL:http://localhost:8080}`.
Spring's automatic mapping would drop the dashes (`RESTATE_INGRESSURL`). The placeholders keep the variable names readable and list
them all in one file. Fees are easier to change in a file than through many variables:

```yaml
# config/application.yml: raises the Bank Transfer fee; every other value keeps its default
fee:
  bank-transfer:
    rate: "0.006"
    min: "1.50"
```

Money values are quoted so they reach `BigDecimal` as strings. A missing or invalid fee value fails startup with a readable message.

**What a restart with changed values does:**

| Change | Effect |
|--------|--------|
| Fee numbers | A workflow that already passed CALCULATE keeps its journaled fee. Every other workflow, and every new request, uses the new numbers. Completed records are never recalculated: resubmitting the same payload returns the original response |
| `restate.ingress-url`, `admin-url`, `attach-timeout`, `registration.*`, `server.port` | Safe |
| `restate.advertised-url` | Restate treats the new URL as a new deployment and the registrar registers it. In-flight invocations stay pinned to the old URL and keep retrying it, so let them finish first (open question 4) |
| `restate.endpoint-port` | Keep it consistent with the port in `advertised-url`, unless something forwards between the two |

---

## Idempotency & recovery guarantees

| Situation | What happens |
|-----------|--------------|
| Same payload sent twice (sequentially or concurrently) | Same workflow key → Restate runs it once; the second caller attaches → same response, **1 charge** |
| Same id, payload changed after a **rejection** | New key → new workflow → processed normally (the id was not used up) |
| Same id, payload changed after a **charge** | New workflow → CLAIM sees `COMPLETED` with another hash → `Conflict` → 409 |
| Same id, different payloads, concurrently | Both workflows CLAIM on the same Virtual Object (exclusive) → one `New`, the other `InProgress`/`Conflict` → 409 |
| Same payload after workflow retention expired | New workflow → CLAIM → `AlreadyCompleted(response)` → same response, no charge |
| App crashes at any step boundary | Restate retries; journaled steps are replayed, not re-executed → **1 charge**, record `COMPLETED` |
| Endpoint stops while a `ctx.run` is running (e.g. CHARGE) | The step was not journaled, so it runs again after the restart. The wallet is idempotent by key → **1 charge** (D32) |
| Endpoint keeps failing (70 attempts, about an hour at the default retry policy) | Restate **pauses** the invocation; the record stays `IN_PROGRESS` until an operator resumes or kills it (D33, open question 5) |
| Wallet fails transiently | `ctx.run` is retried by Restate → eventually charged once |
| Wallet declines | `TerminalException` → record `FAILED` → `FeeOutcome.Failed` → 422 |
| Restate unreachable | `FeeService` → 503 |
| Restate doesn't know our endpoint at startup | `RestateRegistrar` registers it |
| Restate admin unreachable at startup | the app starts anyway with a WARN; the background retry registers it once Restate is up; until then requests get 503 |
| App restarted with changed fee numbers | Workflows past CALCULATE keep the journaled fee; completed records keep their response; new workflows use the new numbers |
