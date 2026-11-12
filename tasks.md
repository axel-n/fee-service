# fee-service — Tasks

Source: `requirements.md` (Technology Challenge: Backend Kotlin & Restate).
Design: [`docs/design.md`](docs/design.md). Decisions (`D1`…) and open questions: [`docs/decisions.md`](docs/decisions.md).
Agent and contributor rules: see `AGENTS.md`.

Required deliverables (`requirements.md`): Kotlin, Restate, README covering architecture, setup and how to run it, unit tests (BDD earns
bonus points). Optional: more transaction types (done: 3 extra types, see the fee table in `docs/design.md`).

**Order.** Each task can be implemented and tested using only the tasks above it, and leaves `scripts/check.sh` green. That is what
lets `scripts/task-runner.sh` run them from top to bottom, one fresh session per task.
- Each task ships the tests for what it builds. The exception is the two Restate handlers (4.4–4.6): they need a Restate server, so the
  integration tests in section 7 cover them.
- A task doesn't implement a later task's part. If it needs a piece of one, it builds the smallest version that works and says so.
- When you add or move a task, keep that property: no task may depend on a task below it. The runner follows the file order;
  ids are labels and must be unique.

## 0. Setup & spike
- [x] 0.1 Build: `pom.xml` with the Spring Boot parent, Kotlin (`spring` plugin, kapt with `sdk-api-gen`), `sdk-java-http` 2.9.3,
      the Jackson Kotlin module, and the test deps (JUnit 5, Mockito, Testcontainers, Cucumber BOM). Use JDK 25 if supported, otherwise 21.
      Surefire runs `*Test`, Failsafe runs `*IT` under `mvn verify`. Declare both now, so every IT added later actually runs.
      A default test timeout (`junit.jupiter.execution.timeout.default` in `src/test/resources/junit-platform.properties`, a few
      minutes), so a hanging test fails instead of stalling the build.
      `FeeServiceApplication` (`@SpringBootApplication`, `@ConfigurationPropertiesScan`, `main`, the `Clock` bean) with a unit test of the
      `Clock` bean (no Spring context). No package placeholders: a package appears with its first class.
      `.gitignore` covers `target`. No Maven wrapper: builds use the system `mvn` (D1)
- [x] 0.2 **Spike** (against the external dev Restate; the dev container's `RESTATE_*` variables point to it). Verify in 2.9.3:
      - kapt + the generated clients from Kotlin (ingress and `ctx`)
      - workflow submit → "previously accepted" → attach behaviour; the default workflow retention
      - the admin API for listing and registering deployments (`GET/POST /deployments`, `force`) and how "services changed" is detected
      - starting and stopping the `sdk-java-http` endpoint programmatically (needed for `RestateEndpointLifecycle`)
      - whether a non-terminal exception thrown **between** steps makes Restate retry the invocation with a journal replay (needed for `StepHook` fault injection)
      - binding Spring-built bean instances (`FeeWorkflow`, `TransactionFeeRecord`) to the endpoint with the kapt-generated definitions
      - Jackson serde through Restate for Kotlin types: sealed `FeeOutcome` / `ClaimResult` with `@JsonTypeInfo`, `data object` subtypes,
        and a nullable `ValidationError?` returned from `ctx.run`
      Output: each finding in `docs/decisions.md` (a new `D` row, or an open question resolved or added), and in `docs/design.md` where
      the design changes. Record the Restate server version to pin, for the tests' Testcontainers image and for `dev-setup/compose.yaml`
      (the user pins that file). The spike code is throwaway: none of it stays in `src/`
- [x] 0.3 `application.yml`, the packaged defaults (D22): `server.port=8081`; every `restate.*` key through an explicit env placeholder
      (`ingress-url: ${RESTATE_INGRESS_URL:http://localhost:8080}`, `admin-url: ${RESTATE_ADMIN_URL:http://localhost:9070}`,
      `endpoint-port: ${RESTATE_ENDPOINT_PORT:9080}`, `advertised-url: ${RESTATE_ADVERTISED_URL:http://host.docker.internal:9080}`,
      `attach-timeout`, `registration.*`). The classes that bind them come later (5.1)

## 1. Domain & contract
- [x] 1.1 Enums with `value`, `UNKNOWN` and `fromValue(raw)`: `TransactionType`, `TransactionState`, `Asset` (with its decimals and asset
      type), `AssetType`. Tests: `fromValue` for every value; unknown or null input → `UNKNOWN`, never an exception (D10)
- [x] 1.2 `contract`: `FeeRequest`, `FeeResponse`, `FeeStatusResponse` (snake_case via `@JsonProperty`, `BigDecimal` as a plain number).
      They come before the mapper, validation, the workflow and the record, which all use them. Add a JSON round-trip test that pins
      the field names (D21)
- [x] 1.3 `Transaction`, `TransactionMapper` (`FeeRequest` → `Transaction`; runs after validation and never throws).
      Tests: a valid request maps every field

## 2. Fees
Fees come before validation, because `TransactionTypeValidator` asks `FeeCalculator.supports`.
- [x] 2.1 `FeeQuote`, `FeeRule` (with KDoc), the 4 building blocks: `PercentageFee`, `PercentageWithMinimumFee`, `PercentagePlusFixedFee`,
      `FlatFee`. Rounding `HALF_EVEN` at the asset's decimals. Tests: every building block, including rounding and the minimum
- [x] 2.2 The 4 rule beans. Each is built from its own `*FeeProperties` (`@ConfigurationProperties("fee.<type>")`, `require` checks in
      `init`, D23). Add the `fee:` section with the fee table's numbers (quoted) to `application.yml`. Descriptions are built from the
      numbers and the transaction's asset. Tests: every rule, including descriptions in USD and EUR (`0.0015` → `0.15%`, `0.01` → `1%`,
      `min 1.00 EUR`); every `*FeeProperties` check (negative amount, rate ≥ 1); config binding (`YamlPropertySourceLoader` + `Binder`,
      no Spring context): the packaged `application.yml` binds to the fee table's numbers (`compareTo`), and a missing or invalid fee
      value fails binding with a readable message
- [x] 2.3 `FeeCalculator` (`calculate(tx)`, `supports(type)`) + startup check. Tests: every `TransactionType` except `UNKNOWN` is
      covered; a duplicate or missing rule fails

## 3. Validation
- [x] 3.1 `TransactionValidator` (with KDoc), `ValidationError`, `ValidationOrder`, `RequestValidator` (`AnnotationAwareOrderComparator`,
      fail-fast). Tests: order and fail-fast with small `@Order`-annotated stub validators, because Mockito mocks of the interface carry
      no `@Order`
- [x] 3.2 The 4 structural validators (400): `RequiredFieldsValidator`, `TransactionIdValidator`, `AmountValidator`, `CreatedAtValidator`
      (`Clock`). Readable messages that include the rejected value. Tests: every validator, with `Clock.fixed(...)` for the date
- [x] 3.3 The 5 business validators (422): `StateValidator`, `AssetValidator`, `AssetTypeValidator`, `TransactionTypeValidator`
      (`FeeCalculator.supports`), `AmountScaleValidator`. Readable messages with the allowed values
      (*"Unsupported asset 'BTC'. Supported: 'USD', 'EUR'"*, *"Amount 10.001 has 3 decimals. USD allows 2"*).
      Tests: every validator; `RequestValidator` with all 9 validators reports a 400 problem before a 422 one (D11)

## 4. Restate: workflow
- [x] 4.1 `WalletGateway`, `FakeWalletGateway` (in-memory, idempotent by key, `chargeCount(key)`; waits `wallet.fake.delay` before it
      records a charge, default `0s` in `application.yml`, D24), `ChargeReceipt`, `ChargeDeclinedException` (the workflow depends on them).
      Tests: idempotency by key, charge count, the delay comes before the charge
- [x] 4.2 `FeeRecord`, `FeeRecordStatus`, `ClaimResult`, `ClaimDecider` (pure). Tests: no record → New; IN_PROGRESS → InProgress;
      COMPLETED with the same hash → AlreadyCompleted; other hash → Conflict; FAILED → New
- [x] 4.3 `FeeWorkflowInput`, `FeeOutcome`, `WorkflowStep`, `StepHook` + `NoOpStepHook`. Tests: a Jackson round trip of every durable
      type (`FeeWorkflowInput`, each `FeeOutcome` and `ClaimResult` subtype, `FeeRecord`, `ValidationError`) with the serde setup the
      spike confirmed
- [x] 4.4 `TransactionFeeRecord` VO: `claim(hash)` through `ClaimDecider`, `complete(response)`, `fail(reason)`; `get()` is `@Shared`.
      Its logic is in `ClaimDecider`; the ITs in section 7 cover the handler
- [x] 4.5 `FeeWorkflow.run`: VALIDATE → MAP → CLAIM → CALCULATE → CHARGE → RECORD, each wrapped by `StepHook`. The current step is
      stored in workflow state for `status()`. The ITs in section 7 cover it
- [x] 4.6 Failure handling: inside the CHARGE `ctx.run` closure, catch `ChargeDeclinedException` and rethrow it as `TerminalException`
      (a plain exception escaping `ctx.run` is retried) → the workflow catches it → `fail(reason)` → `FeeOutcome.Failed`; other exceptions propagate (retried)

## 5. Restate: endpoint & registration
- [x] 5.1 `RestateProperties`, `RestateConfig` (ingress `Client` bean; keyed client factories for the generated clients).
      Tests: config binding (`YamlPropertySourceLoader` + `Binder`, no Spring context): the packaged `restate.*` defaults bind, and a
      `RESTATE_*` variable overrides its placeholder
- [x] 5.2 `RestateEndpointLifecycle` (`SmartLifecycle`; binds the `FeeWorkflow` and `TransactionFeeRecord` beans; graceful stop;
      `isRunning`). Tests: it starts on a free port, `isRunning` follows start and stop, and stop releases the port
- [x] 5.3 `RestateAdminClient` (Spring `RestClient`, no extra dependency). Tests: `MockRestServiceServer` (from the Spring test starter)
      for listing deployments and registering with and without `force`
- [x] 5.4 `RegistrationStatus` holder (`PENDING` / `REGISTERED` / `FAILING` + last error) with the code comment
      `// TODO(k8s): expose as an Actuator readiness HealthIndicator so the pod is not ready until Restate knows the endpoint`, and
      `RestateRegistrar`, which updates it: already registered and up to date → no-op; missing → register; services changed → `force`;
      admin unreachable → retry with backoff → after `maxAttempts` **log a WARN, don't fail startup**, keep retrying in the background
      (Spring's built-in `TaskScheduler`, no extra dependency); `enabled=false` → skip.
      Tests (Mockito mocks `RestateAdminClient`): registered → no-op; missing → register; changed → force; down → retries then succeeds
      (status `REGISTERED`); down past `maxAttempts` → WARN logged, **startup not failed**, status `FAILING`, the background retry later
      succeeds → `REGISTERED`; disabled → nothing

## 6. API & application
- [x] 6.1 `PayloadHash` (canonical, amount normalized), `WorkflowKey`. Tests: normalization (`1000` == `1000.00`), field order doesn't
      change the hash, the key format
- [x] 6.2 `FeeException` hierarchy, `FeeSubmission`
- [x] 6.3 `FeeService`: pre-check → key → submit/attach → map `FeeOutcome`; no result within `attachTimeout` → `FeeSubmission.Processing`.
      Tests (Mockito mocks the Restate clients): each `FeeOutcome` → the right `FeeSubmission` or exception; previously accepted →
      attach; ingress down → 503; timeout → `Processing` (202)
- [x] 6.4 `TransactionFeeController`: `POST` → 200, or 202 + `Location: /transaction/fee/{id}`; `GET /{id}` → `FeeStatusResponse`.
      Tests: `@WebMvcTest` with a mocked `FeeService` for 200, 202 + `Location`, and `GET`
- [x] 6.5 `ApiExceptionHandler` → `ProblemDetail` (`field`, `value`, `allowed`/`message`): 400 / 409 / 422 / 503.
      Tests: `@WebMvcTest` of the JSON error contract for each status

## 7. Integration tests (`*IT`, Testcontainers Restate)
Harness: `RestateTestEnvironment`, a singleton `restatedev/restate` container (the version pinned by the spike). The app boots with
`@SpringBootTest`, so **registration on startup is exercised for real**.

**Only random ports.** A Restate or app already running on the machine (the dev Restate on 8080/9070, the app on 8081/9080) must not
affect the tests, and the tests must not touch them:
- The container's ingress (8080) and admin (9070) ports are mapped by Testcontainers to random host ports (`getMappedPort`). No fixed port bindings.
- The endpoint port is a free port picked once per test run (`ServerSocket(0)`) before the container starts. Then
  `Testcontainers.exposeHostPorts(port)`, `restate.endpoint-port=<port>` and `advertised-url=http://host.testcontainers.internal:<port>`.
  The port stays the same for the whole run, so `stop()`/`start()` (7.7) rebinds the URL Restate already knows.
- The HTTP API uses `@SpringBootTest(webEnvironment = RANDOM_PORT)`, and tests reach it through `@LocalServerPort`.
- `@DynamicPropertySource` sets every `restate.*` URL and port. It wins over environment variables (`RESTATE_INGRESS_URL`, …), so a
  developer's local settings can't point the tests at the background Restate. Tests load only the classpath config
  (`spring.config.location=classpath:/`), so a local `config/application.yml` can't change the fees under test.

Test beans: `TestWalletGateway` (programmable: fail N times, decline, block on a latch, count charges)
and `FaultInjectingStepHook` (throw once before or after a given step). Create each in the first task that needs it, and extend it later.

These tasks test code that already exists. When a test finds a bug, fix the code in the same task, and keep the earlier tests green.

- [x] 7.1 Harness + **startup**: build `RestateTestEnvironment` as described above. Then: a fresh Restate → the app registers itself →
      services are visible in the admin API; a second app start → no-op; Restate frozen at startup (SIGSTOP, D27) → the app still starts (WARN) →
      thaw (SIGCONT) → the background retry registers it
- [x] 7.2 **Happy path**: each of the 4 types × USD/EUR → `Charged`, record `COMPLETED`, 1 charge
- [x] 7.3 **Rejected**: each validator kind → `Rejected` with the right error, **no record, no charge**; then the same id with a corrected payload → `Charged`
- [x] 7.4 **Duplicates**: same payload sequentially → same response, 1 charge; same payload ×10 concurrently → 1 charge;
      changed payload after a charge → `Conflict`; different payloads concurrently → one `Charged`, the others `Conflict`
- [x] 7.5 **Retention-expired path**: call CLAIM through a new workflow key for an already `COMPLETED` record → `AlreadyCompleted`, no charge
- [x] 7.6 **Crash at every step boundary** (parameterized over `WorkflowStep` except `DONE`, × before/after): `FaultInjectingStepHook` throws once →
      Restate retries → final `Charged`, **exactly 1 charge**, record `COMPLETED`, and journaled steps before the crash were not re-executed.
      Count calls inside the `ctx.run` closures (validator, calculator, wallet), not hook calls: the handler code, hooks included, runs again during replay
- [x] 7.7 **Endpoint restart**: the wallet blocks on a latch mid-CHARGE → `RestateEndpointLifecycle.stop()` → `start()` → release the latch → the workflow completes, 1 charge
- [x] 7.8 **Restate outage**: freeze the Restate container (SIGSTOP, D27) → `POST` → 503; thaw (SIGCONT) → resubmit → `Charged`.
      Also: freeze during a running workflow → thaw → it completes
- [x] 7.9 **Transient wallet failures**: fail 3 times → success → 1 charge
- [x] 7.10 **Wallet decline** → `Failed`, record `FAILED`, 422; resubmitting the same payload → same `Failed` result (no retry storm)
- [x] 7.11 **Status while in flight**: `GET /transaction/fee/{id}` while CHARGE is blocked → `IN_PROGRESS`; after release → `COMPLETED`
- [x] 7.12 **Determinism**: the `Clock` advances between the crash and the retry → the validation result is unchanged (it is journaled)
- [x] 7.13 **Test audit**: every production class has a unit test or is covered by an IT (AGENTS.md). Add what is missing

## 8. Tests: BDD (Cucumber, `*IT`)
Classes: `CucumberIT`, `CucumberSpringConfiguration` (reuses `RestateTestEnvironment` and its random ports), `FeeSteps`, `RecoverySteps`.
- [x] 8.1 `fee_calculation.feature`: the `requirements.md` sample case + an outline over 4 types × USD/EUR (including the Bank Transfer
      minimum and the asset in the description, e.g. `min 1.00 EUR`)
- [x] 8.2 `transaction_state.feature`: an outline over rejected states with the exact message; then the corrected state is accepted
- [x] 8.3 `validation.feature`: missing fields, bad id, amount ≤ 0, bad or future date (400); `GBP`/`BTC`, `CRYPTO`, unknown type, too many decimals (422)
- [x] 8.4 `idempotency.feature`: same payload / changed payload / concurrent submissions
- [x] 8.5 `workflow_recovery.feature`:
      ```gherkin
      Feature: Fee workflow survives failures
        Scenario Outline: The service crashes during processing
          Given a "Mobile Top Up" transaction "txn_200" of 1000 "USD" "FIAT" in state "SETTLED - PENDING FEE"
          And the service will crash <moment> the "<step>" step
          When the transaction is submitted to "/transaction/fee"
          Then the response status is 200
          And the fee is 1.5 with rate 0.0015 and description "Standard fee rate of 0.15%"
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
      ```

## 9. Docs
- [x] 9.1 README: what it does, "How Restate is used" (the diagram + crash example), an architecture overview that links to
      `docs/design.md` and `docs/decisions.md` for the full class design and decision log (link, don't copy: one source of truth),
      setup (prerequisites: JDK 25 and Maven 3.9+, or the dev container in `dev-setup/`; start Restate → `mvn spring-boot:run`; registration is automatic), configuration (the keys and environment
      variables, a `config/application.yml` example for fees, which changes are safe on restart), curl examples, tests
      (what the recovery tests prove), fee table, extension points, assumptions, future work
      (Kubernetes: Actuator readiness/liveness probes backed by `RegistrationStatus`, graceful shutdown of the endpoint)
- [x] 9.2 `http/requests.http`: each type, duplicate, rejected → corrected, invalid payload, status lookup

## 10. Presentation prep
- [x] 10.1 Notes on how AI was used (`requirements.md` asks explicitly): `AGENTS.md`, `tasks.md`, `docs/`, the design discussion, and the
      task runner (`scripts/task-runner.sh`: one fresh session per task, gated by `scripts/check.sh`; the reports in `.task-runs/`)
- [x] 10.2 Demo: start the app (watch the self-registration) → submit txn_001 → resubmit (same result, one charge) →
      kill the app while the wallet is slow (`WALLET_FAKE_DELAY=20s`, D24) → restart → Restate resumes, one charge → show the journal in the Restate UI
