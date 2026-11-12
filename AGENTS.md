# AGENTS.md

Instructions for AI coding agents (and humans) working on **fee-service**.

## Project

A Kotlin + Spring Boot service for a backend technology challenge (requirements: `requirements.md`).
`POST /transaction/fee` hands the request to a Restate workflow. The workflow validates, calculates, charges and records
the fee as journaled steps, so processing survives crashes and restarts without charging twice. The result is persisted
in Restate state, and the app registers its Restate endpoint itself on startup.

- **The plan lives in three files.** Read them before starting any work. Together they are the source of truth:
  - `docs/design.md`: goal, fee table, how Restate is used, class design, wiring, call graph, extension points, recovery guarantees.
  - `docs/decisions.md`: the decisions D1–D24 and the open questions.
  - `tasks.md`: the ordered task checklist.
- If a change conflicts with the design or a decision, stop and ask; don't silently deviate. When a decision changes,
  update `docs/decisions.md` (and `docs/design.md` if the design changes) in the same change.

## Stack (fixed: don't change without asking)

- Kotlin, JDK 25 (or 21 if the toolchain requires it), **Maven 3.9+**, the system `mvn` (no wrapper; the dev image in `dev-setup/` pins the version)
- Spring Boot (`spring-boot-starter-web` only)
- Restate **Java SDK 2.9.3** (`dev.restate:sdk-java-http`, `sdk-api-gen` through kapt), used from Kotlin. The Restate server runs
  **externally** in Docker
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
- `FeeService` is a **thin gateway with no business logic**. It runs the `transaction_id` pre-check (needed for the workflow key),
  builds `WorkflowKey`, submits or attaches to `FeeWorkflow`, and maps `FeeOutcome` to HTTP.
- **All business logic runs inside `FeeWorkflow`** as journaled steps: VALIDATE → MAP → CLAIM → CALCULATE → CHARGE → RECORD.
  Every step is wrapped with `StepHook.before/after`.
- `contract`, `domain`, `fee`, `validation` and `ClaimDecider` are **pure Kotlin** (no Restate imports) and unit-tested without Restate.
- The request/response DTOs live in `contract`, which every layer may import. Nothing outside `api` imports `api`.
- Restate is reached only through the generated clients (ingress in `FeeService`, `ctx` in `FeeWorkflow`) and through
  `RestateAdminClient` (registration).
- The endpoint is started and stopped only by `RestateEndpointLifecycle`. Registration is done only by `RestateRegistrar`.
  A registration failure **must not fail startup**: log a WARN, keep retrying in the background, and update `RegistrationStatus`.
  The app is meant to run on Kubernetes later, where `RegistrationStatus` becomes an Actuator readiness indicator. Keep the
  `TODO(k8s)` comment, and don't add Actuator until that's approved.
- Nothing calls upward, and no layer is skipped.

### Restate workflow code

- Workflow and virtual object handlers must be **deterministic**. Anything non-deterministic or side-effecting goes
  inside `ctx.run("name") { … }`. That covers calling `WalletGateway`, reading the clock, random values, UUIDs and I/O.
  Validation runs in `ctx.run` too, because it reads the `Clock`.
- Configuration that affects a workflow decision (the fee numbers) is read only inside `ctx.run`, so a value changed between
  restarts can't make a replay diverge.
- Business results are **returned** as `FeeOutcome` (`Charged`, `Rejected`, `Conflict`, `Failed`), never encoded in exception messages.
  A wallet decline → `TerminalException` (not retried). Infrastructure failures → plain exceptions (Restate retries them).
- The workflow key is `{transaction_id}:{payloadHash}`. Duplicate protection per `transaction_id` is the CLAIM on
  `TransactionFeeRecord`; never bypass it.
- Keep handlers thin. Logic lives in injected, unit-tested classes (`RequestValidator`, `FeeCalculator`, `ClaimDecider`, `WalletGateway`).
- A new workflow step needs a `WorkflowStep` value, the `StepHook` wrapping, and a row in the crash-recovery tests.
- Changing a handler signature, step order or stored state shape is a breaking change for in-flight workflows; call it out.
  That includes the JSON shape of `FeeRequest` and `FeeResponse` (journaled input and permanently stored record): renaming or
  removing a field is breaking, and adding a nullable field is safe.

## Extending

- **New validation:** a `@Component @Order(ValidationOrder.X)` class implementing `TransactionValidator` that returns `ValidationError?`
  (never throws). Add its constant to `ValidationOrder`, leaving gaps of 100. Structural checks (400) come before business checks (422).
  Messages must be readable and include the rejected value and the allowed values.
- **New transaction type:** an enum value in `TransactionType` + one `@Component` `FeeRule` (compose the building blocks) + its
  `@ConfigurationProperties` class + a `fee.<type>` section in `application.yml` + a unit test + a row in the BDD outline.
  `FeeCalculator`'s startup check fails if a rule is missing.
- **New asset:** an enum value in `Asset` with its decimals and asset type.
- Never add `when` branches over types or validators in central classes. Extension means **adding a class**.

## Code style

- Idiomatic Kotlin: `val` over `var`, data classes for values, sealed types for closed hierarchies, no `!!`, no `lateinit`
  in production code.
- Constructor injection only (no field injection). Every dependency should be mockable or replaceable.
- Inject `Clock` instead of calling `LocalDateTime.now()` / `Instant.now()` directly.
- Tunable values (fee numbers, URLs, ports, timeouts) come from `application.yml` through `@ConfigurationProperties` classes and are
  never hardcoded. Defaults live only in the packaged `application.yml` (D22). Renaming a key breaks existing override files and
  environment variables; call it out.
- JSON uses snake_case through `@JsonProperty` on DTOs; `BigDecimal` is serialized as a plain number.
- Errors are subclasses of the sealed `FeeException` and are mapped to `ProblemDetail` in `ApiExceptionHandler`.
- Small classes with one responsibility. Comment only *why*, not *what*. Add KDoc to public extension points (`FeeRule`, `TransactionValidator`).

## Testing

- Every production class gets a test. Test names describe behaviour, e.g.
  ``fun `rejects state PENDING with readable message`()``.
- Structure each test as `// given` / `// when` / `// then`.
- Unit tests (`*Test`, Surefire): no Spring context unless it is a `@WebMvcTest` slice. Use plain constructors, Mockito mocks and
  `Clock.fixed(...)`.
- Integration and BDD tests (`*IT`, Failsafe): Testcontainers Restate (`restatedev/restate`), and Cucumber features in
  `src/test/resources/features/`.
- Money assertions use `compareTo` / `isEqualByComparingTo`, so `1.5` equals `1.50`.
- Assert idempotency by charge count (`chargeCount(key)`), not just by the response.
- **Workflow and recovery tests are first-class.** Every workflow change must keep these passing: crash at each step boundary
  (`FaultInjectingStepHook`), endpoint restart (`RestateEndpointLifecycle.stop/start`), Restate outage (container frozen with SIGSTOP, D27), transient
  and declined wallet calls, and startup self-registration. All of them run in `RestateTestEnvironment` (one shared Testcontainers Restate).
- **Tests never use fixed ports.** Restate container ports come from `getMappedPort`, the API runs on `RANDOM_PORT`, and the endpoint
  port is a free port picked per run. Every `restate.*` setting is set by `@DynamicPropertySource`. A Restate or app already running
  on the machine must not affect the tests.
- Never use `Thread.sleep` to wait for workflows. Use latches, attach/await on the workflow, or bounded polling of `status()`.

### Commands

```bash
mvn test             # unit tests only (fast, no Docker)
mvn verify           # + Testcontainers and Cucumber (needs Docker)
mvn spring-boot:run      # starts the endpoint and registers it with the external Restate automatically
scripts/check.sh     # the gate: rules + mvn clean verify, every test must actually run (--fast, --unit: see --help)
scripts/task-runner.sh   # implements tasks.md task by task (see "Task automation")
```

Settings change without a rebuild: environment variables (`RESTATE_INGRESS_URL=…`) or a `config/application.yml` in the working
directory (see Configuration in `docs/design.md`).

The dev environment is described in `dev-setup/README.md`: `podman compose` starts the dev Restate and the dev container, which
has nested podman so Testcontainers runs inside it.

Run `scripts/check.sh` before reporting a task as done. Without a container engine run `scripts/check.sh --unit`, and say
explicitly that the ITs didn't run.

## Workflow for agents

1. Pick the next unchecked task in `tasks.md` (in order unless the user says otherwise).
2. Implement it with tests, keeping the change small and focused.
3. Run `scripts/check.sh` and report the results honestly, including failures.
4. Tick the checkbox in `tasks.md` (in a task-runner session the runner ticks it after its own gate). Record any new decision
   or open question in `docs/decisions.md`.
5. Commits (only when asked): imperative subject ≤ 72 chars, one task per commit where practical.

## Task automation

`scripts/task-runner.sh` (run it in the dev container) works through `tasks.md` so that the system is stable after every task:

- **Order:** `tasks.md` is in dependency order (see its header): each task builds only on the tasks above it. The runner goes top to
  bottom, refuses a `--task` that would skip an unchecked task (unless `--out-of-order`), and tells each session which later tasks
  of its section are not its job. Keep that property when you add or move a task.
- **Clean context:** every task runs in a fresh, non-interactive Claude Code session with no earlier conversation, no auto-memory
  and no MCP servers. Its prompt is the task's section plus the procedure in `.claude/skills/implement-task/SKILL.md`, and
  the runner puts the context every task needs into it (`docs/design.md`, `docs/decisions.md`, the file list, the approved
  dependencies, the environment), so sessions don't spend tokens reading or exploring it again.
  To do the same by hand: `/clear`, then `/implement-task [id]`.
- **Checkpoint and gate:** the runner snapshots the tree first. It accepts the result only if `scripts/check.sh` passes and its guards
  hold: protected files untouched (`tasks.md`, `AGENTS.md`, `requirements.md`, `scripts/`, `.claude/skills/`, `dev-setup/`), no
  unapproved dependency, and no fewer tests than before. A failure goes back to the agent (`--fix-attempts`). A result that still
  fails is rolled back, and the run stops.
- **People decide:** a runner session can't ask questions, so the agent reports `blocked`. The runner rolls back, shows the question
  and stops; answer it with `--note "…"` or a decision in `docs/decisions.md`, then rerun. The run also halts after a task that
  changed `docs/` or created `pom.xml`, so a person reviews it.
- **Unattended:** `scripts/autopilot.sh` (`status`, `log`, `stop`) runs `--autonomous` with Opus, detached from the terminal. Nothing
  waits for a person: the agent decides within the hard rules and records each such decision in `docs/decisions.md` as
  "(autonomous decision, review)"; usage limits are waited out until they reset, API errors with backoff; a rejected task starts
  over from its checkpoint (`--task-retries`). It stops only when everything is done, or a task still fails after its retries
  (rolled back). Afterwards read `.task-runs/summary.md` and grep `docs/decisions.md` for "autonomous decision".
- **Dependencies:** the modules of the stack above (Spring Boot web/test, Kotlin, Jackson, the Restate SDK, JUnit 5, Mockito,
  Testcontainers, Cucumber) count as approved; any other dependency is rejected unless the run has `--allow-new-deps`.
- For each accepted task the runner ticks the checkbox and writes `.task-runs/<id>/latest/report.md` and
  `.task-runs/<id>/commit-msg.txt`. git runs on the host: `--pause` waits after each task so you can commit it. `--undo` rolls
  back the last accepted task.
