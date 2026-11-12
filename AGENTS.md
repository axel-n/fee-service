# AGENTS.md

Instructions for AI coding agents (and humans) working on **fee-service**.

## Project

A Kotlin + Spring Boot service for a backend technology challenge (requirements: `requirements.md`).
`POST /transaction/fee` hands the request to a Restate workflow. The workflow validates, calculates, charges and records
the fee as journaled steps, so processing survives crashes and restarts without charging twice. The result is persisted
in Restate state. The app calls Restate only through the ingress; registering its endpoint with Restate is a one-time
setup step outside the app.

- **The design is the source of truth:** `docs/design.md` (goal, fee table, how Restate is used, class design, wiring,
  call graph, extension points, recovery guarantees). Read it before starting any work.
- If a change conflicts with the design or these rules, stop and ask; don't silently deviate. When the design changes,
  update `docs/design.md` in the same change.

## Stack (fixed: don't change without asking)

- Kotlin, JDK 25 (or 21 if the toolchain requires it), **Maven 3.9+**, the system `mvn` (no wrapper; the dev image in `dev-setup/` pins the version)
- Two Maven modules under the root `pom.xml`: **`api/`** (`fee-service-api`, the HTTP contract other services depend on)
  and **`service/`** (`fee-service`, the app). Run `mvn` from the root
- Spring Boot (`spring-boot-starter-web` only; `kotlinx-coroutines-reactor` so Spring MVC can run the `suspend` controller)
- **Kotlin coroutines** (`kotlinx-coroutines-core`): the Restate handlers, `FeeService` and the controller are `suspend` functions.
  Never block a thread to wait (`runBlocking`, `Future.get`) in production code
- Restate **Kotlin SDK 2.11.0** (`dev.restate:sdk-kotlin-http`): its reflection-based API, no code generation, with Jackson
  (`sdk-serde-jackson`, through `KotlinJacksonSerdeFactory`) as the serde instead of kotlinx-serialization. The Restate server
  runs **externally** in Docker. The root `pom.xml` aligns Kotlin, coroutines, kotlinx-serialization and Byte Buddy with the
  versions the SDK is built against; bump them together with `restate.version`
- Tests: JUnit 5, Mockito, Testcontainers, Cucumber

## Hard rules

1. **No new dependencies** without explicit approval from the user. That includes "small" ones (mockito-kotlin, AssertJ extras,
   Kotest, Lombok-like helpers, a validation starter, a DB…). Prefer the JDK, Kotlin stdlib or Spring features already on the classpath.
2. **No storage outside Restate.** No database, files or caches as a system of record.
3. **Money is `BigDecimal`.** Never `Double`/`Float`. Create values with `BigDecimal("0.0015")`, never `BigDecimal(0.0015)`. Round only with
   `RoundingMode.HALF_EVEN` at the asset's decimals. Compare with `compareTo`, not `==`.
4. **Only `SETTLED - PENDING FEE` is processed. Only `USD` and `EUR` (`FIAT`) are supported.**
5. **Don't install software** or run Docker commands that change the user's environment. The user runs Restate.
6. **Don't commit or push** unless the user asks.

## Architecture rules

Follow the call graph in `docs/design.md`:

- `TransactionFeeController` calls **only** `FeeService`. It handles HTTP concerns only.
- `FeeService` is a **thin gateway with no business logic**. It runs the `transaction_id` pre-check (the id is the workflow key),
  submits or attaches to the `FeeWorkflow` of that id, and maps `FeeOutcome` to HTTP.
- **All business logic runs inside `FeeWorkflow`** as journaled steps: VALIDATE → MAP → CLAIM → CALCULATE → RECORD (RECORD
  is the charge: the fee is stored as the transaction's charged fee).
  Every step is wrapped with `StepHook.before/after`.
- `contract`, `domain`, `fee`, `validation` and `ClaimDecider` are **pure Kotlin** (no Restate imports) and unit-tested without Restate.
- The request/response DTOs live in `contract` and the exceptions with the error body in `contract.error`, both in the **api
  module**, which every layer may import. The api module is published to other services: only Jackson and Kotlin, no Spring,
  no Restate, and nothing of the service. Nothing outside `api` imports `api` (the service's HTTP package).
- Restate is reached only through the Kotlin SDK's typed clients, each behind an interface the tests fake: `FeeWorkflowClients`
  (the ingress, for `FeeService`), `FeeWorkflowContext` (inside `FeeWorkflow`) and `FeeRecordState` (inside
  `TransactionFeeRecord`). Only their `Restate*`/`Ingress*` implementations call the SDK. The app never
  calls Restate's admin API: registration is done outside the app (README), so `restate.*` holds only the ingress URL,
  the endpoint port and the attach timeout. Don't add Restate settings or admin calls without asking.
- The endpoint is started and stopped only by `RestateEndpointLifecycle`. Don't add Actuator until that's approved.
- Nothing calls upward, and no layer is skipped.

### Restate workflow code

- Workflow and virtual object handlers must be **deterministic**. Anything non-deterministic or side-effecting goes
  inside a journaled step, `FeeWorkflowContext.journaled("name", typeTag<T>()) { … }` (Restate's `runBlock`). That covers
  reading the clock, random values, UUIDs and I/O. Validation is journaled too, because it reads the `Clock`.
- Configuration that affects a workflow decision (the fee numbers) is read only inside a journaled step, so a value changed
  between restarts can't make a replay diverge.
- Handlers are `suspend` functions with no `ctx` parameter: the SDK finds the invocation in the coroutine context. Don't
  launch coroutines or switch dispatchers inside a handler; Restate's journal must see every step in order.
- Every Restate service class carries `@CustomSerdeFactory(KotlinJacksonSerdeFactory::class)`. Without it the Kotlin SDK
  falls back to kotlinx-serialization, which can't read the Jackson-annotated durable types or the stored JSON.
- Business results are **returned** as `FeeOutcome` (`Charged`, `Rejected`, `Conflict`), never encoded in exception messages.
  Infrastructure failures → plain exceptions (Restate retries them).
- The workflow key is the `transaction_id`: every submission of an id attaches to its one run and gets that run's result,
  whatever payload it carries. The CLAIM on `TransactionFeeRecord` keeps protecting the id once Restate has dropped the run;
  never bypass it.
- Keep handlers thin. Logic lives in injected, unit-tested classes (`RequestValidator`, `FeeCalculator`, `ClaimDecider`).
- A new workflow step needs a `WorkflowStep` value, the `StepHook` wrapping, and a row in the crash-recovery tests.
- Changing a handler signature, step order or stored state shape is a breaking change for in-flight workflows; call it out.
  That includes the JSON shape of `FeeRequest` and `FeeResponse` (journaled input and permanently stored record): renaming or
  removing a field is breaking, and adding a nullable field is safe.

## Extending

- **New validation:** a `@Component @Order(ValidationOrder.X)` class in `validation.rule` implementing `TransactionValidator` that
  returns `ValidationError?` (never throws). Add its constant to `ValidationOrder`, leaving gaps of 100. Structural checks (400) come
  before business checks (422). Messages must be readable and include the rejected value and the allowed values.
- **New transaction type:** an enum value in `TransactionType` + one `@Component` `FeeRule` in `fee.rule` (compose the building
  blocks in `fee.formula`) + its `@ConfigurationProperties` class in `config/FeeProperties.kt` + a `fee.<type>` section in
  `application.yml` + a unit test + a row in the BDD outline. `FeeCalculator`'s startup check fails if a rule is missing.
- **New asset:** an enum value in `Asset` with its decimals and asset type.
- Never add `when` branches over types or validators in central classes. Extension means **adding a class**.

## Code style

- Idiomatic Kotlin: `val` over `var`, data classes for values, sealed types for closed hierarchies, no `!!`, no `lateinit`
  in production code.
- Constructor injection only (no field injection). Every dependency should be mockable or replaceable.
- Inject `Clock` instead of calling `LocalDateTime.now()` / `Instant.now()` directly.
- Tunable values (fee numbers, URLs, ports, timeouts) come from `application.yml` through `@ConfigurationProperties` classes and are
  never hardcoded. Defaults live only in the packaged `application.yml`. Renaming a key breaks existing override files and
  environment variables; call it out.
- **Every `@ConfigurationProperties` class lives in `config`**, one file per top-level section of `application.yml`, never next to
  the bean that uses it.
- Package and file layout: a package holds one kind of class, and an extension point's implementations get a sub-package
  (`fee.rule`, `fee.formula`, `validation.rule`). A file holds one main type plus the small types and helpers that exist only to
  serve it (its result and field types, its default implementation, internal helpers). Don't create a file for a lone data class,
  enum or helper function that belongs to another type. A test file mirrors its production file, one test class per production class.
- JSON uses snake_case through `@JsonProperty` on DTOs; `BigDecimal` is serialized as a plain number.
- Errors are subclasses of the sealed `FeeException` (api module, each with a `FeeErrorCode`) and are mapped to `ProblemDetail`
  in `ApiExceptionHandler`. A new property of an error goes into `FeeProblem` too, so clients can read it.
- Small classes with one responsibility. Comment only *why*, not *what*. Add KDoc to public extension points (`FeeRule`, `TransactionValidator`).

## Testing

- Every production class gets a test. Test names describe behaviour, e.g.
  ``fun `rejects state PENDING with readable message`()``.
- Structure each test as `// given` / `// when` / `// then`.
- Unit tests (`*Test`, Surefire): no Spring context unless it is a `@WebMvcTest` slice. Use plain constructors, Mockito mocks and
  `Clock.fixed(...)`.
- Suspend code in tests: `fun \`…\`() = runBlocking<Unit> { … }` (the `<Unit>` matters: JUnit silently skips a test that returns
  a value). Fake the Restate-facing interfaces (`FeeWorkflowContext`, `FeeRecordState`, `FeeWorkflowClients`) with small
  in-memory classes. Plain Mockito can stub and verify a suspend function inside `runBlocking`. `@WebMvcTest`s go through
  `performToCompletion`, because the controller answers asynchronously.
- Integration and BDD tests (`*IT`, Failsafe): Testcontainers Restate (`restatedev/restate`), and Cucumber features in
  `service/src/test/resources/features/`.
- Money assertions use `compareTo` / `isEqualByComparingTo`, so `1.5` equals `1.50`.
- Assert idempotency by the number of CALCULATE executions (`CountingFeeCalculator.count(id)`), not just by the response.
- **Workflow and recovery tests are first-class.** Every workflow change must keep these passing: crash at each step boundary
  (`FaultInjectingStepHook`), endpoint restart (`RestateEndpointLifecycle.stop/start`), Restate outage (container frozen with
  SIGSTOP). All of them run in `RestateTestEnvironment` (one shared Testcontainers Restate, which registers
  the shared app's endpoint once it is ready).
- **Tests never use fixed ports.** Restate container ports come from `getMappedPort`, the API runs on `RANDOM_PORT`, and the endpoint
  port is a free port picked per run. Every `restate.*` setting is set by `@DynamicPropertySource`. A Restate or app already running
  on the machine must not affect the tests.
- Never use `Thread.sleep` to wait for workflows. Use latches, attach/await on the workflow, or bounded polling of `status()`.

### Commands

```bash
mvn test             # unit tests only (fast, no Docker)
mvn verify           # + Testcontainers and Cucumber (needs Docker)
mvn spring-boot:run      # from the root: starts the app (service module) and its endpoint; register it once (README, "Run")
mvn -pl api -am install  # puts fee-service-api (and the parent pom it needs) in the local repository for other services
scripts/check.sh     # the gate: rules + mvn clean verify, every test must actually run (--fast, --unit: see --help)
```

Settings change without a rebuild: environment variables (`RESTATE_INGRESS_URL=…`) or a `config/application.yml` in the working
directory (see Configuration in `docs/design.md`).

The dev environment is described in `dev-setup/README.md`: `podman compose` starts the dev Restate and the dev container, which
has nested podman so Testcontainers runs inside it.

Run `scripts/check.sh` before reporting a change as done. Without a container engine run `scripts/check.sh --unit`, and say
explicitly that the ITs didn't run.

## Workflow for agents

1. Implement the change with tests, keeping it small and focused.
2. Run `scripts/check.sh` and report the results honestly, including failures.
3. Update `docs/design.md` (and the README) when the design or the behaviour changes.
4. Commits (only when asked): imperative subject ≤ 72 chars, one change per commit where practical.

## How it was built

The service was built task by task with `scripts/task-runner.sh` and `scripts/autopilot.sh`, one fresh Claude Code
session per task, gated by `scripts/check.sh` (see `docs/ai-usage.md`). The task checklist and the decision log that
drove it were removed once every task was done; the runner needs a new `tasks.md` to run again.
