# fee-service — Design

What the service does and how it is built. Agent and contributor rules: [`../AGENTS.md`](../AGENTS.md).

## Goal

A Spring Boot service written in **Kotlin**. It exposes `POST /transaction/fee`, **validates** the request, and hands a
valid one to a **Restate workflow**. The workflow validates it again, **calculates** the fee, then **charges** it by
**recording** it for the transaction. Every step is journaled by Restate, so the processing **survives crashes and
restarts**: it resumes where it stopped and never charges twice. An invalid request is answered by the app alone,
without a call to Postgres or Restate (requirements: the company's clarification on validation).
The result, the fee record, is persisted in the service's own database, **PostgreSQL** (requirements: "persist the result
of the fee calculations and charges", clarified as a database in addition to Restate). An already charged
`transaction_id` is answered from a local cache or from Postgres, **without a call to Restate**.

Principles:
- **All business logic runs inside the workflow.** The Spring API is a thin gateway. It runs the workflow's own
  validators first, so an invalid request never costs a Postgres or Restate call. The workflow validates again, because
  it never trusts its input: a run can be started past the app, straight through Restate's ingress.
- **One system of record per fact.** Postgres holds the fee records; Restate holds the workflow's journal; the cache holds
  only copies of charges, which never change.
- **Minimal layers, minimal dependencies.** Anything `requirements.md` does not require is left out.
- **Easy to extend.** A new validation or fee rule is one new class.
- **Easy to test.** Constructor injection everywhere. Restart and recovery are tested explicitly.
- **Registers itself.** At startup the app registers its endpoint with Restate, at whatever host and port it runs on; at
  shutdown it removes it again, unless unfinished invocations still need it.
- **Configurable without rebuilding.** Fee numbers and the Restate ingress URL come from external configuration, and a restart applies them.

Reference example (Mobile Top Up, 0.15%):

```
Request                                     Response (200)
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

`fee` is answered without trailing zeros, as in the challenge's sample (`1.5`, `5`, `10.3`); the stored record keeps the
asset's 2 decimals (`1.50`), the same value. When the run isn't finished within `restate.attach-timeout`, the answer is a 504
problem (`WORKFLOW_TIMEOUT`); the same request sent again gets the fee once it is charged.

---

## Fee table

The numbers below are the defaults in the packaged `application.yml`. They can be changed with a restart, without a rebuild
(see [Configuration](#configuration)). Fixed amounts are in the transaction's asset (USD or EUR, same numbers).
Fees are rounded `HALF_EVEN` to 2 decimals. Bank Transfer has a minimum and, for now, no maximum.

Descriptions are **built from the configured numbers and the transaction's asset** (`{asset}` below), so they never go stale.
A EUR Bank Transfer reads `Fee rate of 0.5% (min 1.00 EUR)`.

| Type | Rule bean | Building block | `rate` | `description` | 1000 USD → fee |
|------|-----------|----------------|--------|---------------|----------------|
| `Mobile Top Up` | `MobileTopUpFeeRule` | `PercentageFee(0.0015)` | `0.0015` | `Standard fee rate of 0.15%` | **1.50** |
| `Bank Transfer` | `BankTransferFeeRule` | `PercentageWithMinimumFee(0.005, min 1.00)` | `0.005` | `Fee rate of 0.5% (min 1.00 {asset})` | 5.00 (100 → 1.00, 10000 → 50.00) |
| `Merchant Payment` | `MerchantPaymentFeeRule` | `PercentagePlusFixedFee(0.01, fixed 0.30)` | `0.01` | `Fee rate of 1% plus 0.30 {asset} fixed` | 10.30 |
| `Cash Withdrawal` | `CashWithdrawalFeeRule` | `FlatFee(2.00)` | `0` | `Flat fee of 2.00 {asset}` | 2.00 |

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
Spring app: TransactionFeeController → FeeService           (thin gateway, no business logic of its own)
   │  1. valid? RequestValidator, every check (400/422 otherwise, with no Postgres or Restate call)
   │  2. charged already? ChargedFees: Caffeine cache, then Postgres (COMPLETED only) → 200, no Restate call
   │  3. submit FeeWorkflow/txn_001/run, then attach  (the id sent again gets PREVIOUSLY_ACCEPTED
   │     and the first run's result; no result within attach-timeout → ask Restate for the output once:
   │     470 (not ready) → 504, the output → its result); a charge is put into the cache
   ▼
Restate server (external Docker) ── journals every step ───────────────────────────┐
   │ invokes the app's endpoint (port 9080)                                         │
   ▼                                                                                │
FeeWorkflow.run(request)                                                            │
   step VALIDATE   journaled { RequestValidator.validate(request) } → Rejected? ────┤
   step MAP        TransactionMapper.toTransaction(request)  (pure, deterministic)  │
                   journaled { a new claim token (UUID) }                           │
   step CLAIM      journaled { FeeRecordRepository.claim(tx, token) → ClaimDecider }│
                   → Conflict? / AlreadyCompleted? ─────────────────────────────────┤
   step CALCULATE  journaled { FeeCalculator.calculate(tx) }                     ───┤
   step RECORD     journaled { FeeRecordRepository.complete(id, token, response) }  │
                   (the charge, in Postgres) ───────────────────────────────────────┘
   return FeeOutcome.Charged(response)
```

**The fee records in Postgres.** One row per `transaction_id` in `fee_record` (`schema.sql`). CLAIM writes every field of
the validated request (`amount`, `asset`, `asset_type`, `type`, `state`, `created_at`, exactly as sent; `created_at` to the
microsecond), the status `IN_PROGRESS`, the claim token of the run, and `claimed_at`. RECORD adds the charge (`fee`, `rate`,
`description`), `charged_at` and the status `COMPLETED`. Numbers are `NUMERIC` without precision, so every value keeps its
scale; `claimed_at` and `charged_at` come from the app's `Clock`. A check constraint keeps the charge (fee, rate,
description, `charged_at`) all set when the record is `COMPLETED` and all empty while it is `IN_PROGRESS`.

- **Old records are deleted by `claimed_at`**, the app's own clock, which is indexed: a retention job deletes with
  `DELETE FROM fee_record WHERE claimed_at < …` without scanning the table. Never by `created_at`: that is the client's
  date, which only has to be in the past, so a transaction dated years ago may have been charged today. A deleted record
  no longer protects its id: once Restate has dropped the run too, the same `transaction_id` sent again is charged again.
  So delete only records older than any client could still resend.

- **Every write is one statement, and its row count decides**, only after the request passed validation (the gateway
  validates before its lookup, and VALIDATE comes before CLAIM, so a rejected request never touches Postgres). Postgres
  runs a single statement as one transaction of its own, so no write opens an explicit one. CLAIM first reads the id; a
  row exists → it decides from that row. No row → one `INSERT … ON CONFLICT (transaction_id) DO NOTHING`: 1 row → the
  row is this run's claim (`New`), known without a read; 0 rows → a concurrent claim committed its row between the read
  and the insert, and CLAIM reads that row to decide from it. RECORD is one
  `UPDATE … WHERE transaction_id = ? AND claim_token = ?`; any other row count than 1 means nothing was written: it
  throws, and Restate retries.
- **READ COMMITTED is enough.** The primary key serializes concurrent inserts of one id: an insert that meets a row being
  inserted waits for that transaction, then does nothing, and the read after it sees the winner's row (another token →
  `InProgress`). Only the token's holder can complete a row, and that check sits in the `UPDATE` itself.
- **The claim token makes the writes safe to repeat.** Restate runs a journaled step again when the app crashed after the
  step's commit but before its journal entry. The token is journaled before CLAIM, so the CLAIM run again finds its own
  row (`New`, not someone else's claim), and RECORD writes the same values again (CALCULATE is journaled).

**Concurrent submissions of one `transaction_id`** (same or different payloads). Each invalid payload is rejected by
the gateway on its own and never reaches Restate. Every valid caller misses the cache and Postgres and submits. Restate
accepts exactly one submission, the first to reach it; every other gets `PREVIOUSLY_ACCEPTED`, its payload ignored, and
attaches to that one run, so every valid caller gets the same outcome. Postgres would hold even if two runs of one id
met, which Restate doesn't allow: one claim wins, and only its token can record the charge. The cache and the lookup
return only `COMPLETED` rows, so they never hand out a payload that wasn't the accepted one.

**Crash recovery example.** The app dies right after CALCULATE. Restate retries the invocation, and the endpoint
(restarted, or a new instance) receives the journal. VALIDATE, CLAIM and CALCULATE are **replayed from the journal and not
executed again**. RECORD then runs once, and the waiting HTTP caller gets the result.

**Startup needs the database.** `schema.sql` (`CREATE TABLE IF NOT EXISTS`, its index, then a query of every column the
app uses) runs at every start, while the context starts and before the Restate endpoint starts or registers. An
unreachable database, or a `fee_record` table that lacks a column (which `IF NOT EXISTS` would keep), fails startup, so an
app that couldn't record a charge never takes work. The app's database user owns the table: it creates it and its index.

**The lookup never holds a request up.** `FeeService` asks Postgres only on a cache miss, and waits at most
`fee-cache.lookup-timeout` (1 s). A read that fails or takes longer (a database that hangs, as in a network partition) is
logged and treated as "not charged": Restate answers every `transaction_id` correctly, so the lookup only saves it a call.

**Startup.** `RestateEndpointLifecycle` starts the endpoint on `restate.endpoint-port` (9080). The app sends work through
the ingress (`restate.ingress-url`); Restate calls the app through this endpoint, and sends work only to a registered one. So
once the endpoint listens, the lifecycle registers it with Restate's admin API (`restate.admin-url`):
`POST /deployments {"uri":"http://<restate.advertised-host>:<the port it listens on>","force":true}`. Restate discovers the
services by calling that URL. The app can't find out how Restate reaches it (Restate in a container reaches the machine as
`host.docker.internal`), so the host is a setting; the port is the one the endpoint really got. `force` replaces a deployment
with the same URL in place: it keeps its id, so a restart at the same URL resumes the invocations pinned to it, and a changed
handler signature updates the services. The newest registration gets all new work. A failed registration fails startup, with
the URL and the settings in the message: an app Restate can't reach would only ever answer 504.

**Shutdown.** The lifecycle closes the endpoint, then asks Restate how many invocations that haven't finished need its
deployment: those pinned to it, and those of its services not started yet (they go to the newest deployment, which may be
this one). None → it removes the deployment (`DELETE /deployments/{id}?force=true`). Some → it keeps it and logs why: Restate
never resumes an invocation whose deployment is gone, not even at the same URL, so removing it would strand a claimed but
uncharged transaction. Kept, they resume once the endpoint is back at that URL. If Restate can't be asked, the deployment is
kept too.

---

## Class design

Two Maven modules under the root `pom.xml` (`fee-service-parent`):

| Module | Artifact | Holds |
|--------|----------|-------|
| `api/` | `com.example:fee-service-api` | the HTTP contract other services depend on: `contract` and `contract.error` below. Compile dependencies: Jackson (the Kotlin modules of Jackson 3 and Jackson 2) and the Kotlin stdlib only |
| `service/` | `com.example:fee-service` | the app: every other package below, `application.yml`, and all the ITs and BDD features |

Base package: `com.example.fee`. The tree lists **files**. A package holds one kind of class, and an extension point's
implementations get their own sub-package (`fee.rule`, `fee.formula`, `validation.rule`). A file holds one main type plus the
small types and helpers that exist only to serve it: its result and field types, its default implementation, its internal
helpers. Every `@ConfigurationProperties` class lives in `config`, one file per top-level section of `application.yml`.
A test file mirrors its production file, with one test class per production class.

```
com.example.fee
├── FeeServiceApplication.kt            @SpringBootApplication + @ConfigurationPropertiesScan + main(); no beans of its own
│
├── config/                             every @Configuration and @ConfigurationProperties class: the bean definitions, and what
│   │                                   application.yml and its overrides can set
│   ├── RestateProperties.kt            restate.*: ingressUrl, adminUrl, endpointPort, advertisedHost, attachTimeout
│   ├── FeeCacheProperties.kt           fee-cache.*: maximumSize, expireAfterWrite of the cache of charged fees, lookupTimeout of
│   │                                   the Postgres read on a cache miss
│   ├── FeeProperties.kt                fee.*: MobileTopUpFeeProperties, BankTransferFeeProperties, MerchantPaymentFeeProperties,
│   │                                   CashWithdrawalFeeProperties: one class per type, fee.<type>.{rate, min, fixed} as the formula needs;
│   │                                   init checks requiredRate / requiredAmount fail startup on a bad value
│   ├── RestateConfig.kt                @Configuration: the ingress Client bean (with KotlinJacksonSerdeFactory) and the
│   │                                   FeeWorkflowClients bean (IngressFeeWorkflowClients over that client)
│   ├── ChargedFeesCacheConfig.kt       @Configuration: the Cache<String, FeeResponse> bean of ChargedFees, sized by FeeCacheProperties
│   └── ClockConfig.kt                  @Configuration: the Clock bean, Clock.systemUTC(), the only source of "now"
│
├── api/                                HTTP only
│   ├── TransactionFeeController.kt     POST /transaction/fee (200), answers FeeResponse → calls only FeeService
│   └── ApiExceptionHandler.kt          @RestControllerAdvice: FeeException (+ its `code`, + `transaction_id` for 409 and 504) and
│                                       Spring MVC errors → ProblemDetail
│
├── contract/                           [api module] DTOs shared by HTTP and Restate (pure Kotlin + Jackson annotations, snake_case)
│   ├── FeeRequest.kt                   all fields nullable; enums/dates as String, amount as BigDecimal. Journaled as the workflow input
│   ├── FeeResponse.kt                  transaction_id, amount, asset, type, fee, rate, description: the 200 body, exactly the
│   │                                   requirements' Expected Response Payload. Stored permanently in the fee_record table
│   ├── PlainBigDecimal.kt              @PlainBigDecimal: BigDecimal as a plain JSON number, with a serializer for each Jackson
│   │                                   generation (3 for HTTP, 2 for Restate), bundled by @JacksonAnnotationsInside
│   └── error/                          [api module] what the API answers instead of a fee
│       ├── FeeException.kt             sealed, Spring-free: ValidationException(field, value, message, kind) (400/422), DuplicateTransactionException (409),
│       │                               WorkflowUnavailableException (503), WorkflowTimeoutException (504);
│       │                               + FeeErrorCode: the problem's `code`, with its HTTP status. Gateway only: the workflow returns FeeOutcome
│       │                               and never throws these
│       └── FeeProblem.kt               the problem+json body as a client reads it; toException() rebuilds the FeeException from the `code`
│
├── application/                        thin gateway to Restate
│   ├── FeeService.kt                   FeeService: RequestValidator (400/422, no Postgres or Restate call) → ChargedFees.find (a charge → 200, no Restate call) →
│   │                                   submit/attach FeeWorkflow/{transaction_id} (bounded by attachTimeout) → FeeOutcome →
│   │                                   FeeResponse (200, the fee without trailing zeros; remembered in ChargedFees) or FeeException (a
│   │                                   ValidationError becomes a ValidationException here); no result within attachTimeout and Restate
│   │                                   answers 470 (not ready) for the run's output → WorkflowTimeoutException (504)
│   └── ChargedFees.kt                  find(id): the Caffeine cache, then FeeRecordRepository.findCharged within lookupTimeout (a hit
│                                       is cached; a failed or slower read is null, Restate answers then); remember(response). Only
│                                       charges, which never change
│
├── validation/                         executed by FeeService before any Postgres or Restate call, and again inside the workflow (VALIDATE)
│   ├── TransactionValidator.kt         interface validate(request): ValidationError? + ValidationOrder: `const val`s spaced by 100
│   │                                   (`@Order` needs compile-time constants)
│   ├── ValidationError.kt              data class(field, value, message, kind: INVALID | UNSUPPORTED) + its builders: UnsupportedValue
│   │                                   (the 422 message), ValueDisplay (how a rejected value appears, shortened)
│   ├── RequestValidator.kt             sorts by @Order (so hand-built lists in unit tests are ordered too), fail-fast, returns ValidationError?
│   └── rule/                           one TransactionValidator @Component per check
│       ├── RequiredFieldsValidator.kt  @Order(100)  → 400
│       ├── TransactionIdValidator.kt   @Order(200)  → 400   (the transaction_id is the workflow key)
│       ├── AmountValidator.kt          @Order(300)  → 400
│       ├── CreatedAtValidator.kt       @Order(400)  → 400   (Clock)
│       ├── StateValidator.kt           @Order(500)  → 422
│       ├── AssetValidator.kt           @Order(600)  → 422
│       ├── AssetTypeValidator.kt       @Order(700)  → 422
│       ├── TransactionTypeValidator.kt @Order(800)  → 422   (FeeCalculator.supports)
│       ├── AmountScaleValidator.kt     @Order(900)  → 422   (a rule of the asset: needs its decimals, so it runs after AssetValidator)
│       └── MaxAmountValidator.kt       @Order(1000) → 422   (amount ≤ 3000000000, same number in every asset)
│
├── domain/                             pure Kotlin
│   ├── Transaction.kt                  data class Transaction + the enums of its fields: TransactionType, TransactionState, Asset (with its
│   │                                   decimals and AssetType), AssetType; each with value, UNKNOWN and fromValue()
│   ├── TransactionMapper.kt            FeeRequest → Transaction? (after validation, never throws, null if unvalidated)
│   └── CreatedAtFormat.kt              parseOrNull(created_at): the one created_at format, shared with CreatedAtValidator
│
├── fee/
│   ├── FeeRule.kt                      interface FeeRule (the extension point) + FeeQuote, its result (journaled by CALCULATE)
│   ├── FeeCalculator.kt                List<FeeRule> → map by type; calculate(tx), supports(type); startup check
│   ├── FeeNumbers.kt                   internal helpers: roundedFor(asset) (HALF_EVEN), requireRate / requireNonNegative, percent / money (wording)
│   ├── formula/                        the building blocks, each a FeeRule
│   │   └── PercentageFee.kt, PercentageWithMinimumFee.kt, PercentagePlusFixedFee.kt, FlatFee.kt
│   └── rule/                           one @Component FeeRule per transaction type, built from its *FeeProperties in config
│       └── MobileTopUpFeeRule.kt, BankTransferFeeRule.kt, MerchantPaymentFeeRule.kt, CashWithdrawalFeeRule.kt
│
├── workflow/                           the FeeWorkflow Restate service
│   ├── FeeWorkflow.kt                  @Component @Workflow suspend run(FeeRequest): FeeOutcome (keyed by transaction_id);
│   │                                   @Shared suspend status(): WorkflowStep?
│   │                                   ctor: RequestValidator, TransactionMapper, FeeCalculator, ClaimDecider, FeeRecordRepository,
│   │                                   StepHook, FeeWorkflowContext
│   │                                   + FeeWorkflowContext: the only way the handlers reach Restate (key, journaled { }, step
│   │                                   state), faked in unit tests
│   │                                   + RestateFeeWorkflowContext, the @Component over the Kotlin SDK (workflowKey(), runBlock,
│   │                                   state()); holds the STEP state key
│   ├── FeeWorkflowTypes.kt             its durable types: FeeOutcome (sealed: Charged(response), Rejected(error), Conflict(transactionId));
│   │                                   WorkflowStep (VALIDATE … RECORD, DONE)
│   └── StepHook.kt                     interface before(step, key)/after(step, key) + NoOpStepHook, the default @Component
│
├── record/                             the fee records in Postgres (table fee_record, resources/schema.sql)
│   ├── FeeRecordRepository.kt          FeeRecordRepository: suspend claim(transaction, token): FeeRecord (stores every request
│   │                                   field), complete(id, token, response), findCharged(id): FeeResponse?; an interface, faked
│   │                                   in unit tests + PostgresFeeRecordRepository, the @Repository over R2DBC (DatabaseClient,
│   │                                   Clock for claimed_at/charged_at; its SQL in const vals): one statement per write,
│   │                                   see "The fee records in Postgres"
│   ├── FeeRecord.kt                    a row: FeeRecord (status, claimToken, response) + FeeRecordStatus (IN_PROGRESS / COMPLETED)
│   └── ClaimDecider.kt                 pure function (FeeRecord, token) → ClaimResult (unit-testable without Restate or Postgres)
│                                       + ClaimResult (sealed: New, AlreadyCompleted(response), InProgress), journaled by CLAIM
│
├── restate/                            Restate plumbing: the endpoint and its registration, the ingress client and the serde
│   ├── RestateEndpointLifecycle.kt     SmartLifecycle: binds the FeeWorkflow bean, starts/stops the endpoint server (port 9080)
│   │                                   on its own Vert.x instance, reached by reflection (vertx-core is runtime-only);
│   │                                   registers it at start, removes its deployment at stop only when nothing unfinished needs it
│   ├── RestateDeployments.kt           RestateDeployments: register(uri), unfinishedInvocations(deployment), remove(deployment), an interface
│   │                                   so the lifecycle is tested with fakes; + Deployment(id, services); + AdminApiRestateDeployments,
│   │                                   the @Component over Restate's admin API (JDK HTTP client; the count is a sys_invocation query)
│   ├── FeeWorkflowClients.kt           FeeWorkflowClients: suspend submit(transactionId, request) → FeeWorkflowRun (suspend attach(),
│   │                                   suspend output(): the outcome, or null when Restate answers 470),
│   │                                   an interface so FeeService is tested with fakes; + IngressFeeWorkflowClients, which sends
│   │                                   through client.toWorkflow<FeeWorkflow>(id).request { run(request) }
│   └── serde/
│       └── KotlinJacksonSerdeFactory.kt  Restate's JacksonSerdeFactory (same mapper, same JSON) that also reads the Kotlin SDK's
│                                       KtTypeTag; named by @CustomSerdeFactory on FeeWorkflow and used by the ingress client.
│                                       Its own package, so workflow doesn't import restate (which imports it)
```

### Wiring: Spring builds, Restate invokes

- Spring creates every object. `FeeWorkflow` is an ordinary `@Component` bean with constructor injection.
  `RestateEndpointLifecycle` binds that instance to the endpoint, so Restate calls methods on the object Spring built.
- Spring annotations therefore keep working behind the workflow. `@Component`, `@Order` and the injected `List<TransactionValidator>` /
  `List<FeeRule>` are resolved once at startup, and Restate never sees them. Restate annotations (`@Workflow`, `@Shared`)
  appear only on the handler class.
- The handler bean is a singleton shared by all invocations, so it holds no mutable state. The handlers are `suspend` functions
  without a `ctx` parameter: the Kotlin SDK keeps the invocation in the coroutine context, and `RestateFeeWorkflowContext`
  (a stateless bean too) reads it from there. The SDK binds the bean by reflection, with no code generation, and runs the
  handlers on `Dispatchers.Default`. Postgres is reached with R2DBC inside journaled steps: the handler suspends, it
  doesn't hold a thread. The connections come from Boot's R2DBC pool (`r2dbc-pool`, at Boot's default size), so a
  statement doesn't open a new connection.
- The Restate clients are proxies the SDK generates at runtime by subclassing the handler class (Byte Buddy). Spring's
  Kotlin plugin opens `@Component` classes, which keeps that possible.
- Everything that crosses Restate's serde (handler inputs and outputs, journaled results, workflow state) is part of the
  durable contract. That includes the workflow input `FeeRequest`, `FeeOutcome`, `ValidationError`, `ClaimResult`, the claim
  token, and the `contract` DTOs inside them. `FeeRecord` doesn't cross it: it is a Postgres row.
  Restate serializes them with its own Jackson mapper, not Spring's, and that mapper rejects unknown properties. The Kotlin
  SDK would default to kotlinx-serialization, so `FeeWorkflow` names `KotlinJacksonSerdeFactory` with `@CustomSerdeFactory`.
  It keeps the JSON exactly as the Java SDK wrote it.
  Restate's mapper is **Jackson 2**, while Spring MVC (Boot 4) writes HTTP bodies with **Jackson 3**. The `contract` DTOs
  meet both, so they use only the annotations the two share (`com.fasterxml.jackson.annotation`), plus `@PlainBigDecimal`,
  which bundles a serializer for each. `ContractMapper` (the api module's test-jar) runs the contract tests through both.
  Sealed types need `@JsonTypeInfo` with **explicit `@JsonSubTypes` names**, so the stored type ids don't depend on class names.
  Subtypes without fields are `data object`: deserialization returns a new instance that is `==` to the object but not `===`, so compare with `==`, `is` or `when`.

### Call graph (who calls whom)

```
TransactionFeeController (suspend) ──▶ FeeService (only)

FeeService (suspend; the attach waits at most restate.attach-timeout)
  ├─▶ RequestValidator.validate(request)         every check, first (400/422 if bad; nothing below runs)
  ├─▶ ChargedFees.find(transaction_id)           cache, then FeeRecordRepository.findCharged; a charge → 200, nothing else
  ├─▶ FeeWorkflowClients.submit(transaction_id, request) → FeeWorkflowRun.attach()  (also when PREVIOUSLY_ACCEPTED)
  │     attach-timeout reached → FeeWorkflowRun.output(); Restate's 470 → throw WorkflowTimeoutException (504)
  └─▶ FeeOutcome → FeeResponse (fee without trailing zeros; a charge → ChargedFees.remember) | throw FeeException

  ─ ─ ─ ─ Restate server (external) invokes the app's endpoint (port 9080) ─ ─ ─ ─

FeeWorkflow.run(request)             StepHook.before/after around every step; Restate only through FeeWorkflowContext,
                                     Postgres only through FeeRecordRepository, inside journaled steps
  ├─ VALIDATE  journaled("validate") ─▶ RequestValidator ─▶ [TransactionValidator…] (@Order, fail-fast)
  ├─ MAP       ─▶ TransactionMapper
  │            journaled("claim-token") ─▶ UUID
  ├─ CLAIM     journaled("claim") ─▶ FeeRecordRepository.claim(transaction, token) ─▶ ClaimDecider.decide(record, token)
  ├─ CALCULATE journaled("calculate") ─▶ FeeCalculator ─▶ FeeRule
  └─ RECORD    journaled("record") ─▶ FeeRecordRepository.complete(id, token, response)   (the charge)

Startup:  schema.sql ─▶ Postgres (unreachable → the app doesn't start)
          RestateEndpointLifecycle.start() ─▶ endpoint listens ─▶ RestateDeployments.register(http://<advertised-host>:<port>)
Shutdown: RestateEndpointLifecycle.stop()  ─▶ endpoint closes ─▶ RestateDeployments.unfinishedInvocations = 0 ? remove : keep
Errors:  ApiExceptionHandler ◀── Spring, for FeeException
```

Rules:
1. **The controller calls only `FeeService`. `FeeService` contains no business logic of its own;** it validates with
   `RequestValidator` (the logic stays in the validators), routes, answers a stored charge, and maps.
2. **Every business step runs inside `FeeWorkflow`,** validation included: the gateway's check only spares Postgres and
   Restate an invalid request. Non-deterministic work (clock, random, I/O, Postgres) goes inside a
   journaled step (`FeeWorkflowContext.journaled`, Restate's `runBlock`). So does reading configuration that affects a
   decision (the fee numbers), so a restart with new values can't make a replay diverge. A journaled Postgres write is safe
   to run again (the claim token).
3. **Restate is reached only through the Kotlin SDK's typed clients, behind interfaces** (`FeeWorkflowClients` in `FeeService`,
   `FeeWorkflowContext` in the handlers). The admin API is called only by `RestateEndpointLifecycle`, through
   `RestateDeployments`, to register and remove the endpoint. **Postgres is reached only through `FeeRecordRepository`.**
   Nothing waits by blocking a thread: `FeeService` suspends, R2DBC suspends, and Spring MVC serves the suspend controller
   asynchronously.
4. **`contract`, `domain`, `fee`, `validation` and `ClaimDecider` are pure.** They are unit-tested without Restate or Postgres.
   The api module (`contract`, `contract.error`) is published to other services, so it imports neither Spring nor anything
   of the service.
5. **The workflow returns `FeeOutcome` for business results** and throws only for infrastructure problems (which Restate retries).

### Extension points

| To add… | Do this |
|---------|---------|
| a validation rule | a `@Component @Order(ValidationOrder.X)` `TransactionValidator` in `validation.rule` + a unit test (it runs in `FeeService` and in the workflow automatically) |
| a transaction type | an enum value + a `@Component` `FeeRule` in `fee.rule` + its `*FeeProperties` class in `config/FeeProperties.kt` + a `fee.<type>` section in `application.yml` + a unit test + a BDD outline row |
| a fee formula | a new building block implementing `FeeRule` in `fee.formula` |
| an asset | an enum value in `Asset` (with its decimals) |
| a workflow step | a `WorkflowStep` value + a step in `FeeWorkflow` wrapped with `StepHook` (recovery tests then cover it) |
| a tunable value | a field in the matching `@ConfigurationProperties` class in `config` (a new top-level section gets its own file there) + its default in `application.yml` (+ a `${ENV_VAR:…}` placeholder for an operational setting) |
| an error the API answers with | in the api module: a `FeeException` subclass + its `FeeErrorCode` value, its properties in `FeeProblem` and a branch in `toException()`; in the service: an `@ExceptionHandler` in `ApiExceptionHandler` that adds those properties |

---

## Configuration

Every tunable value comes from Spring Boot's externalized configuration, so changing it needs **a restart, not a rebuild**.
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
| `restate.admin-url` | `RESTATE_ADMIN_URL` | Restate's admin API, where the endpoint is registered at startup and removed at shutdown |
| `restate.endpoint-port` | `RESTATE_ENDPOINT_PORT` | port the Restate endpoint listens on (0: the OS picks one); the registered URL takes the port it really got |
| `restate.advertised-host` | `RESTATE_ADVERTISED_HOST` | host Restate reaches the endpoint at: `localhost` by default, `host.docker.internal` in the local profile, `dev` in the dev container |
| `restate.attach-timeout` | `RESTATE_ATTACH_TIMEOUT` | how long `POST` waits for the result (30 s by default); then it answers 504 only if Restate reports the run as not finished (470) |
| `spring.r2dbc.url` | `DATABASE_URL` | the Postgres of the fee records: `r2dbc:postgresql://localhost:5432/fee` by default (the dev Postgres), `r2dbc:postgresql://postgres:5432/fee` in the dev container |
| `spring.r2dbc.username`, `spring.r2dbc.password` | `DATABASE_USERNAME`, `DATABASE_PASSWORD` | its credentials, `fee`/`fee` by default (the dev Postgres); set them everywhere else |
| `fee-cache.maximum-size` | `FEE_CACHE_MAXIMUM_SIZE` | how many charged fees `FeeService` caches at most (1000 by default) |
| `fee-cache.expire-after-write` | `FEE_CACHE_EXPIRE_AFTER_WRITE` | how long a charged fee stays cached (1 day by default). Charges never change, so both only bound memory |
| `fee-cache.lookup-timeout` | `FEE_CACHE_LOOKUP_TIMEOUT` | how long a cache miss waits for Postgres before it leaves the answer to Restate (1 s by default) |
| `fee.<type>.rate`, `.min`, `.fixed` | (use a file) | fee numbers per transaction type, see the [fee table](#fee-table) |
| `spring.mvc.async.request-timeout` | (fixed at `0`) | off: the controller is a `suspend` function served asynchronously, and `FeeService` bounds the wait for the result by `restate.attach-timeout`. Tomcat's own 30 s would cut a longer attach timeout short |
| `spring.sql.init.mode` | (fixed at `always`) | `schema.sql` runs at every start, which also fails startup when Postgres can't be reached |

The `restate.*`, `fee-cache.*` and database keys use explicit placeholders in `application.yml`, e.g.
`ingress-url: ${RESTATE_INGRESS_URL:http://localhost:8080}`. Spring's automatic mapping would drop the dashes
(`RESTATE_INGRESSURL`). The placeholders keep the variable names readable and list them all in one file. Fees are easier to
change in a file than through many variables:

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
| Fee numbers | A workflow that already passed CALCULATE keeps its journaled fee. Every other workflow, and every new request, uses the new numbers. Completed records are never recalculated: resubmitting the transaction returns the original response |
| `restate.ingress-url`, `admin-url`, `attach-timeout`, `server.port`, `fee-cache.*` | Safe |
| The database (`DATABASE_URL`, credentials) | The app starts only if it reaches it. Point it at the database that holds the records: a new, empty one forgets every charge, and a charged id sent again after Restate dropped its run would be charged again |
| The endpoint's URL (`advertised-host` or `endpoint-port`) | The app registers the new URL at startup, and it gets all new work. In-flight invocations stay pinned to the old URL's deployment, which is kept for them, and keep retrying it, so let them finish first |

---

## Idempotency & recovery guarantees

| Situation | What happens |
|-----------|--------------|
| An invalid request, any `transaction_id` | `FeeService` rejects it first (400/422) → **no Postgres or Restate call**, no workflow, no record. A charged id sent with an invalid payload is rejected too; its charge stands |
| A charged `transaction_id` sent again, any valid payload | `FeeService` answers the stored response from the cache, or from Postgres when it isn't cached (restart, expiry, another instance) → **no Restate call**, no charge |
| Same id sent again before it is charged, same or changed valid payload (sequentially or concurrently) | Same workflow key → Restate runs it once, for the first payload it received; every other caller attaches → that run's response, **1 fee record** |
| Valid and invalid payloads of one id at once | Each invalid one is rejected by the gateway; the valid ones go to the one run → its charge for all of them, **1 fee record** |
| Same id, payload corrected after a **rejection** | The rejection started no workflow and wrote no record → the corrected payload is charged normally |
| A run started past the app (straight through Restate's ingress) with an invalid payload, or a `created_at` the gateway passed but VALIDATE, on an instance whose clock is behind, finds in the future | The workflow's VALIDATE rejects it before CLAIM → no record. Restate keeps the finished run (workflow retention, 1 day by default), so a valid payload of the same id gets that rejection until then |
| A run of a charged id that starts anyway (sent past the app, after retention) | CLAIM → `AlreadyCompleted(response)` → the stored response, whatever the payload, no charge |
| Same id after a run that never finished (killed after CLAIM) was dropped | New workflow → CLAIM sees `IN_PROGRESS` with another token → `InProgress` → `Conflict` → 409 |
| App crashes at any step boundary | Restate retries; journaled steps are replayed, not re-executed → **1 fee record**, `COMPLETED` |
| App crashes after a CLAIM or RECORD commit, before its journal entry | The step runs again: CLAIM finds its own row by the journaled token (`New`), RECORD writes the same values → **1 fee record**, `COMPLETED` |
| Postgres unreachable while a workflow runs | The journaled step throws; Restate retries it with backoff → charged once Postgres is back (504 meanwhile if it outlasts the attach timeout; resending is safe). `FeeService`'s lookup failing just leaves the answer to Restate; a charge still cached or still kept by Restate is answered during the outage, and a rejection never needs Postgres (`PostgresOutageIT`) |
| Postgres unreachable at startup, or its `fee_record` lacks a column | The app doesn't start (`schema.sql` fails), so it never registers with Restate (`DatabaseStartupIT`) |
| Postgres hangs (connections open, nothing answers) | `FeeService`'s lookup gives up after `fee-cache.lookup-timeout` and asks Restate: a charge Restate still keeps is answered, and a rejection, answered before any lookup, doesn't wait at all (`PostgresOutageIT`). A run's CLAIM or RECORD waits; Restate's own timeouts abort and retry the attempt, so it is charged once when Postgres answers again |
| Endpoint stops while a journaled step is running (e.g. CALCULATE) | The step was not journaled, so it runs again after the restart; only its journaled result is recorded → **1 fee record** |
| Endpoint keeps failing (70 attempts, about an hour at the default retry policy) | Restate **pauses** the invocation; the record stays `IN_PROGRESS` until an operator resumes or kills it |
| Restate unreachable | `FeeService` → 503 |
| Restate unreachable at startup, or it can't reach the advertised URL | The app doesn't start; the message names the URL, `restate.advertised-host` and `restate.admin-url` |
| App stops (shutdown, or an endpoint restart) | Nothing unfinished needs its deployment → removed. Otherwise kept, and the invocations resume once the endpoint is back at the same URL → **1 fee record** |
| App restarted with changed fee numbers | Workflows past CALCULATE keep the journaled fee; completed records keep their response; new workflows use the new numbers |
