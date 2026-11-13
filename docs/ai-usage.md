# fee-service — How AI is used

`requirements.md` asks "to see how you use AI". This page says how AI works on this project, what keeps its code honest,
and what a person decides. Design: [`design.md`](design.md). Agent rules: [`../AGENTS.md`](../AGENTS.md).

## In short

A person and an AI assistant (Claude, through Claude Code) agree on the design in writing, and AI sessions write the code
and its tests against it. A change counts as done only when `scripts/check.sh` passes: the rule checks, then the full build
with every unit, integration and BDD test actually run. A person owns the stack, the dependencies, the scope and the commits.

## The context every session reads

A coding session starts with no memory. Whatever isn't written down gets made up again, and differently each time. So
the plan lives in three files, and every session reads them before it changes anything:

| File | Role for the AI |
|------|-----------------|
| [`AGENTS.md`](../AGENTS.md) | The rules: priorities and approach (KISS, YAGNI, DRY, SOLID), the fixed stack, the hard rules (no new dependencies, no storage outside Restate, money is `BigDecimal`, …), the architecture and call graph rules, Restate determinism, tests, and "stop and ask instead of guessing" |
| [`docs/design.md`](design.md) | What is built: fee table, how Restate is used, classes, wiring, call graph, extension points, configuration, recovery guarantees |
| [`requirements.md`](../requirements.md) | The scope: nothing beyond it without asking |

These files describe **only the current state**. History (old plans, task lists, superseded decisions) is left out: a
session reads every line as an instruction, and an outdated line is a wrong one. A change to the design updates
`docs/design.md` in the same change.

## How a change is made

1. A person describes the change.
2. The session reads `AGENTS.md` and `docs/design.md`. It stops and asks when the change conflicts with them, needs a new
   dependency, or goes beyond `requirements.md`.
3. It makes the change with its tests, keeping it small, and updates `docs/design.md` and the README when the design or
   the behaviour changes.
4. It runs `scripts/check.sh` and reports the result as it is, failures included. Without a container engine it runs
   `scripts/check.sh --unit` and says that the integration tests didn't run.
5. The person reviews the change and commits it. Agents commit only when asked.

## What keeps AI-written code honest

These guards target the ways an AI agent tends to go wrong:

| Risk | Guard |
|------|-------|
| "Done" without proof | `scripts/check.sh` must pass, and its last line states what ran: `check: OK unit=<n> it=<n>` |
| Dropping a test to get green | `check.sh` fails on a disabled or skipped test, and on a module whose tests didn't run |
| A production class without a test | `TestCoverageAuditTest` fails for any file in `api/src/main/kotlin` or `service/src/main/kotlin` without its own test or a justified entry |
| Quietly adding a library | Hard rule 1; `check.sh` fails on the dependencies `AGENTS.md` rules out |
| Eroding the architecture | `check.sh` rules: pure packages import no Restate, the api module imports no Spring and nothing of the service, nothing outside `api` imports `api`, constructor injection only, `Clock` injected, no `!!` or `lateinit` |
| Money as floating point | `check.sh` rules: no `Double`/`Float` in code that handles money, `BigDecimal` only from a `String` |
| Plausible but untested claims about recovery | Crash at every step boundary, endpoint restart, Restate outage, each counting the **CALCULATE executions** (README, "Tests") |
| Mocking the library instead of testing it | Integration and BDD tests run against a real Restate (Testcontainers) |
| Non-deterministic workflow code | `AGENTS.md`'s Restate rules (clock and fee numbers only inside a journaled step), checked by `DeterminismIT` and the crash tests |
| Tests that depend on the machine | Only random ports; every `restate.*` setting from `@DynamicPropertySource` |
| Docs drifting from the code | The design is updated in the same change, and the docs hold only the current state |

## What a person decides

- **Stack, dependencies, scope:** fixed in `AGENTS.md` and `requirements.md`. Only a person approves a new dependency, a
  stack change or behaviour the requirements don't ask for.
- **The design:** a change that conflicts with `docs/design.md` or `AGENTS.md` stops the session with a question.
- **The environment:** `dev-setup/` and the Restate version it runs belong to the person. Agents don't install software
  or change containers.
- **Open questions:** the README's Assumptions and Future work.
- **Commits:** agents commit only when asked.

## Practices

- **Plan in writing, then code.** The person's effort goes into the design, the rules and the reviews. A session is only
  as good as what it reads.
- **Small changes, green every time.** Every change leaves `check.sh` passing, so a bad one is easy to spot and undo.
- **Trust the gate, not the session's report.** "Done" counts only after `scripts/check.sh` passes.
- **Test against the real thing.** What the design assumes about Restate is proven by tests against a real Restate server.
- **Docs hold the current state.** One fact in one place, rewritten when it changes, never appended to.
