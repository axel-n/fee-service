# fee-service — Design

What the service does and how it is built. Agent and contributor rules: [`../AGENTS.md`](../AGENTS.md).

## Goal

A Spring Boot service written in **Kotlin**. It exposes `POST /transaction/fee` and hands the request to a **Restate
workflow**. The workflow **validates** and **calculates** the fee, then **charges** it by **recording** it for the
transaction. Every step is journaled by Restate, so the processing **survives crashes and restarts**: it resumes where it
stopped and never charges twice.
The result is persisted **in Restate**.

Principles:
- **All business logic runs inside the workflow.** The Spring API is a thin gateway.
- **Minimal layers, minimal dependencies.** Anything `requirements.md` does not require is left out.
- **Easy to extend.** A new validation or fee rule is one new class.
- **Easy to test.** Constructor injection everywhere. Restart and recovery are tested explicitly.
- **Only the Restate connection it needs.** The app calls only the Restate ingress; registering its endpoint is a one-time setup step.
- **Configurable without rebuilding.** Fee numbers and the Restate ingress URL come from external configuration, and a restart applies them.

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
(see [Configuration](#configuration)). Fixed amounts are in the transaction's asset (USD or EUR, same numbers).
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
   │  1. transaction_id present and well-formed?  (it is the workflow key; otherwise 400)
   │  2. submit FeeWorkflow/txn_001/run, then attach  (the id sent again gets PREVIOUSLY_ACCEPTED
   │     and the first run's result, no result within attach-timeout → 202)
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

**Crash recovery example.** The app dies right after CALCULATE. Restate retries the invocation, and the endpoint
(restarted, or a new instance) receives the journal. VALIDATE, CLAIM and CALCULATE are **replayed from the journal and not
executed again**. RECORD then runs once, and the waiting HTTP caller gets the result.

**Startup.** `RestateEndpointLifecycle` starts the endpoint on port 9080. The app talks to Restate only through the ingress
(`restate.ingress-url`); Restate talks to the app through this endpoint. Restate sends work only to a registered endpoint, so
whoever runs the app registers it once with Restate's admin API:
`POST <admin>/deployments {"uri":"<endpoint URL as Restate reaches it>","force":true}`. Restate then discovers the services by
calling the endpoint, and keeps the registration across app restarts while the URL stays the same. After a change to a
handler's signature, register again: `force` updates the deployment in place, without it Restate keeps the old services.

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
├── FeeServiceApplication.kt            @SpringBootApplication + @ConfigurationPropertiesScan + main(); Clock bean
│
├── config/                             every @ConfigurationProperties class: what application.yml and its overrides can set
│   ├── RestateProperties.kt            restate.*: ingressUrl, endpointPort, attachTimeout
│   └── FeeProperties.kt                fee.*: MobileTopUpFeeProperties, BankTransferFeeProperties, MerchantPaymentFeeProperties,
│                                       CashWithdrawalFeeProperties: one class per type, fee.<type>.{rate, min, fixed} as the formula needs;
│                                       init checks requiredRate / requiredAmount fail startup on a bad value
│
├── api/                                HTTP only
│   ├── TransactionFeeController.kt     POST /transaction/fee (200, or 202), answers FeeStatusResponse → calls only FeeService
│   └── ApiExceptionHandler.kt          @RestControllerAdvice: FeeException (+ its `code`) and Spring MVC errors → ProblemDetail;
│                                       FeeInProgressException → 202 + FeeStatusResponse IN_PROGRESS
│
├── contract/                           [api module] DTOs shared by HTTP and Restate (pure Kotlin + Jackson annotations, snake_case)
│   ├── FeeRequest.kt                   all fields nullable; enums/dates as String, amount as BigDecimal. Journaled as the workflow input
│   ├── FeeResponse.kt                  transaction_id, amount, asset, type, fee, rate, description. Stored permanently in TransactionFeeRecord;
│   │                                   HTTP clients get it only inside FeeStatusResponse
│   ├── FeeStatusResponse.kt            POST body: transaction_id, status, the FeeResponse fields once COMPLETED
│   │                                   (HTTP only, never stored)
│   ├── PlainBigDecimal.kt              @PlainBigDecimal: BigDecimal as a plain JSON number, with a serializer for each Jackson
│   │                                   generation (3 for HTTP, 2 for Restate), bundled by @JacksonAnnotationsInside
│   └── error/                          [api module] what the API answers instead of a fee
│       ├── FeeException.kt             sealed, Spring-free: ValidationException(field, value, message, kind) (400/422), DuplicateTransactionException (409),
│       │                               WorkflowUnavailableException (503);
│       │                               + FeeErrorCode: the problem's `code`, with its HTTP status. Gateway only: the workflow returns FeeOutcome
│       │                               and never throws these
│       └── FeeProblem.kt               the problem+json body as a client reads it; toException() rebuilds the FeeException from the `code`
│
├── application/                        thin gateway to Restate
│   └── FeeService.kt                   FeeService: transaction_id pre-check → submit/attach FeeWorkflow/{transaction_id} (bounded by attachTimeout) → FeeOutcome →
│                                       FeeStatusResponse COMPLETED (200) or FeeException (a ValidationError becomes a ValidationException here);
│                                       FeeInProgressException(transactionId): no result within attachTimeout → 202 (not a FeeException)
│
├── validation/                         executed inside the workflow
│   ├── TransactionValidator.kt         interface validate(request): ValidationError? + ValidationOrder: `const val`s spaced by 100
│   │                                   (`@Order` needs compile-time constants)
│   ├── ValidationError.kt              data class(field, value, message, kind: INVALID | UNSUPPORTED) + its builders: UnsupportedValue
│   │                                   (the 422 message), ValueDisplay (how a rejected value appears, shortened)
│   ├── RequestValidator.kt             sorts by @Order (so hand-built lists in unit tests are ordered too), fail-fast, returns ValidationError?
│   └── rule/                           one TransactionValidator @Component per check
│       ├── RequiredFieldsValidator.kt  @Order(100)  → 400
│       ├── TransactionIdValidator.kt   @Order(200)  → 400   (also reused by FeeService for the key pre-check)
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
│   │                                   ctor: RequestValidator, TransactionMapper, FeeCalculator, StepHook, FeeWorkflowContext
│   │                                   + FeeWorkflowContext: the only way the handlers reach Restate (key, journaled { }, step state,
│   │                                   claim/complete on TransactionFeeRecord), faked in unit tests
│   │                                   + RestateFeeWorkflowContext, the @Component over the Kotlin SDK (workflowKey(), runBlock,
│   │                                   state(), the virtualObject<TransactionFeeRecord> proxy); holds the STEP state key
│   ├── FeeWorkflowTypes.kt             its durable types: FeeOutcome (sealed: Charged(response), Rejected(error), Conflict(transactionId));
│   │                                   WorkflowStep (VALIDATE … RECORD, DONE)
│   └── StepHook.kt                     interface before(step, key)/after(step, key) + NoOpStepHook, the default @Component
│
├── record/                             the TransactionFeeRecord Restate service
│   ├── TransactionFeeRecord.kt         @Component @VirtualObject (key = transaction_id): suspend claim(), complete(response); @Shared get()
│   │                                   + FeeRecordState (key, get, set), faked in unit tests
│   │                                   + RestateFeeRecordState, the @Component over objectKey() and state(); holds the RECORD state key
│   ├── FeeRecord.kt                    stored state FeeRecord (status, response) + FeeRecordStatus (IN_PROGRESS / COMPLETED)
│   └── ClaimDecider.kt                 pure function (FeeRecord?) → ClaimResult (unit-testable without Restate) + ClaimResult
│                                       (sealed: New, AlreadyCompleted(response), InProgress)
│
├── restate/                            Restate plumbing: the endpoint, the ingress client and the serde
│   ├── RestateEndpointLifecycle.kt     SmartLifecycle: binds the FeeWorkflow and TransactionFeeRecord beans, starts/stops the endpoint server (port 9080)
│   │                                   on its own Vert.x instance, reached by reflection (vertx-core is runtime-only)
│   ├── RestateConfig.kt                @Configuration: ingress Client bean (with KotlinJacksonSerdeFactory) and the FeeWorkflowClients bean
│   │                                   + FeeWorkflowClients: suspend submit(transactionId, request) → FeeWorkflowRun (suspend attach()),
│   │                                   an interface so FeeService is tested with fakes; its implementation sends through
│   │                                   client.toWorkflow<FeeWorkflow>(id).request { run(request) }
│   └── serde/
│       └── KotlinJacksonSerdeFactory.kt  Restate's JacksonSerdeFactory (same mapper, same JSON) that also reads the Kotlin SDK's
│                                       KtTypeTag; named by @CustomSerdeFactory on both services and used by the ingress client.
│                                       Its own package, so workflow and record don't import restate (which imports them)
```

### Wiring: Spring builds, Restate invokes

- Spring creates every object. `FeeWorkflow` and `TransactionFeeRecord` are ordinary `@Component` beans with constructor
  injection. `RestateEndpointLifecycle` binds those two instances to the endpoint, so Restate calls methods on the objects Spring built.
- Spring annotations therefore keep working behind the workflow. `@Component`, `@Order` and the injected `List<TransactionValidator>` /
  `List<FeeRule>` are resolved once at startup, and Restate never sees them. Restate annotations (`@Workflow`, `@VirtualObject`,
  `@Shared`) appear only on the two handler classes.
- Handler beans are singletons shared by all invocations, so they hold no mutable state. The handlers are `suspend` functions
  without a `ctx` parameter: the Kotlin SDK keeps the invocation in the coroutine context, and `RestateFeeWorkflowContext` /
  `RestateFeeRecordState` (stateless beans too) read it from there. The SDK binds the beans by reflection, with no code generation,
  and runs the handlers on `Dispatchers.Default`.
- The Restate clients are proxies the SDK generates at runtime by subclassing the handler class (Byte Buddy). Spring's
  Kotlin plugin opens `@Component` classes, which keeps that possible.
- Everything that crosses Restate's serde (handler inputs and outputs, journaled results, VO state) is part of the durable contract.
  That includes the workflow input `FeeRequest`, `FeeOutcome`, `ValidationError`, `ClaimResult`, `FeeRecord`, and the `contract` DTOs inside them.
  Restate serializes them with its own Jackson mapper, not Spring's, and that mapper rejects unknown properties. The Kotlin
  SDK would default to kotlinx-serialization, so both services name `KotlinJacksonSerdeFactory` with `@CustomSerdeFactory`.
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
  ├─▶ TransactionIdValidator.validate(request)   key pre-check only (400 if bad): the transaction_id is the workflow key
  ├─▶ FeeWorkflowClients.submit(transaction_id, request) → FeeWorkflowRun.attach()  (also when PREVIOUSLY_ACCEPTED)
  └─▶ FeeOutcome → FeeStatusResponse | throw FeeException   (still running → throw FeeInProgressException)

  ─ ─ ─ ─ Restate server (external) invokes the app's endpoint (port 9080) ─ ─ ─ ─

FeeWorkflow.run(request)             StepHook.before/after around every step; Restate only through FeeWorkflowContext
  ├─ VALIDATE  journaled("validate") ─▶ RequestValidator ─▶ [TransactionValidator…] (@Order, fail-fast)
  ├─ MAP       ─▶ TransactionMapper
  ├─ CLAIM     ─▶ FeeWorkflowContext.claim(id) = TransactionFeeRecord/{id}.claim() ─▶ ClaimDecider
  ├─ CALCULATE journaled("calculate") ─▶ FeeCalculator ─▶ FeeRule
  └─ RECORD    ─▶ FeeWorkflowContext.complete(id, response) = TransactionFeeRecord/{id}.complete(response)   (the charge)

Startup: RestateEndpointLifecycle.start()   (registering the endpoint with Restate is a setup step outside the app)
Errors:  ApiExceptionHandler ◀── Spring, for FeeException
```

Rules:
1. **The controller calls only `FeeService`. `FeeService` contains no business logic;** it routes and maps.
2. **Every business step runs inside `FeeWorkflow`.** Non-deterministic work (clock, I/O) goes inside a journaled step
   (`FeeWorkflowContext.journaled`, Restate's `runBlock`). So does reading configuration that affects a decision (the fee
   numbers), so a restart with new values can't make a replay diverge.
3. **Restate is reached only through the Kotlin SDK's typed clients, behind interfaces** (`FeeWorkflowClients` in `FeeService`,
   `FeeWorkflowContext` and `FeeRecordState` in the handlers). The app never calls the admin API. Nothing waits by blocking a
   thread: `FeeService` suspends, and Spring MVC serves the suspend controller asynchronously.
4. **`contract`, `domain`, `fee`, `validation` and `ClaimDecider` are pure.** They are unit-tested without Restate. The api module
   (`contract`, `contract.error`) is published to other services, so it imports neither Spring nor anything of the service.
5. **The workflow returns `FeeOutcome` for business results** and throws only for infrastructure problems (which Restate retries).

### Extension points

| To add… | Do this |
|---------|---------|
| a validation rule | a `@Component @Order(ValidationOrder.X)` `TransactionValidator` in `validation.rule` + a unit test (it runs in the workflow automatically) |
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
| `restate.endpoint-port` | `RESTATE_ENDPOINT_PORT` | port the Restate endpoint listens on; Restate calls it at the URL it was registered with |
| `restate.attach-timeout` | `RESTATE_ATTACH_TIMEOUT` | how long `POST` waits for the result before answering 202 |
| `fee.<type>.rate`, `.min`, `.fixed` | (use a file) | fee numbers per transaction type, see the [fee table](#fee-table) |
| `spring.mvc.async.request-timeout` | (fixed at `0`) | off: the controller is a `suspend` function served asynchronously, and `FeeService` bounds the wait for the result by `restate.attach-timeout`. Tomcat's own 30 s would cut a longer attach timeout short |

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
| Fee numbers | A workflow that already passed CALCULATE keeps its journaled fee. Every other workflow, and every new request, uses the new numbers. Completed records are never recalculated: resubmitting the transaction returns the original response |
| `restate.ingress-url`, `attach-timeout`, `server.port` | Safe |
| The endpoint's URL (another host or `endpoint-port`) | Register the new URL with Restate; it becomes a new deployment. In-flight invocations stay pinned to the old URL and keep retrying it, so let them finish first |

---

## Idempotency & recovery guarantees

| Situation | What happens |
|-----------|--------------|
| Same `transaction_id` sent again, same or changed payload (sequentially or concurrently) | Same workflow key → Restate runs it once, for the first payload it received; every other caller attaches → that run's response, **1 fee record** |
| Same id, payload corrected after a **rejection** | Same workflow → the same rejection while Restate keeps the finished run (workflow retention, 1 day by default); after that a new workflow processes it normally |
| Same id after workflow retention expired | New workflow → CLAIM → `AlreadyCompleted(response)` → the stored response, whatever the payload, no charge |
| Same id after a run that never finished (killed after CLAIM) was dropped | New workflow → CLAIM sees `IN_PROGRESS` → `InProgress` → `Conflict` → 409 |
| App crashes at any step boundary | Restate retries; journaled steps are replayed, not re-executed → **1 fee record**, `COMPLETED` |
| Endpoint stops while a journaled step is running (e.g. CALCULATE) | The step was not journaled, so it runs again after the restart; only its journaled result is recorded → **1 fee record** |
| Endpoint keeps failing (70 attempts, about an hour at the default retry policy) | Restate **pauses** the invocation; the record stays `IN_PROGRESS` until an operator resumes or kills it |

| Restate unreachable | `FeeService` → 503 |
| Restate doesn't know our endpoint | Requests get 503 until it is registered (README, "Run"); registering it once is enough, restarts keep it |
| App restarted with changed fee numbers | Workflows past CALCULATE keep the journaled fee; completed records keep their response; new workflows use the new numbers |
