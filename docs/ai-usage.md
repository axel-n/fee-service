# fee-service — How AI was used

`requirements.md` lists "to see how you use AI" as one purpose of the challenge. This page says how AI was used, what
kept it honest, and what a person still decides. Design: [`design.md`](design.md). Agent rules: [`../AGENTS.md`](../AGENTS.md).

## In one paragraph

A person and an AI assistant (Claude, through Claude Code) worked out the design together first. That conversation
produced written artifacts rather than code: the design, a decision log with the open questions, an ordered task list
and the rules for agents. The code was then written by AI coding sessions, **one fresh session per task**, driven by
`scripts/task-runner.sh`. Each result had to pass `scripts/check.sh` (rule checks plus the full build with every unit,
integration and BDD test) before the runner accepted it. Anything the AI decided alone went into the decision log,
marked for review. Once every task was done, the task list and the decision log were removed: the design and the code
are what remain.

## 1. The design discussion → the plan files

The design came first, in a conversation and not in code. The questions that shaped the service were settled there:

- where the business logic lives (all of it inside `FeeWorkflow`, `FeeService` is a thin gateway)
- how duplicates are handled: the workflow key `{transaction_id}:{payloadHash}`, so a rejected payload doesn't use up the
  id, plus a claim on a virtual object per `transaction_id`
- self-registration that never fails startup, crash simulation through a `StepHook`, and tests on random ports only
- fee numbers in configuration and read inside `ctx.run`, so a restart with new values can't change a replay

The result is four files. Every AI session reads them, and they are the only context it gets:

| File | Role for the AI |
|------|-----------------|
| [`AGENTS.md`](../AGENTS.md) | The rules: fixed stack, hard rules (no new dependencies, no storage outside Restate, money is `BigDecimal`, …), architecture and call graph rules, Restate determinism rules, test rules, and "stop and ask instead of guessing" |
| [`docs/design.md`](design.md) | What to build: classes, wiring, call graph, extension points, recovery guarantees |
| `docs/decisions.md` (removed when done) | Why: one row per decision, plus the open questions. A session that had to decide something added a row |
| `tasks.md` (removed when done) | The order: small tasks, each buildable and testable from the tasks above it, each shipping its own tests |

Why write all this down? A coding session starts with no memory. Whatever isn't in these files gets made up again, and
differently each time. Writing the plan down also gave the person something to review before any code existed.

## 2. Verify assumptions first: the spike

Task 0.2 was a **spike** against a real Restate server: AI-written throwaway code that checked the design's assumptions
about Restate SDK 2.9.3 and server 1.8.1 before production code depended on them. Several assumptions turned out
wrong or incomplete, and each finding became a decision row:

- A duplicate submit is accepted silently, and **its input is ignored**. That is why the payload hash has to be part of the key.
- Re-registering a known URI without `force` **ignores changed services**. "Services changed" therefore needs a dry-run comparison.
- The endpoint server from the SDK's convenience method can't be stopped, so the lifecycle owns its own Vert.x instance.
- A plain exception between steps makes Restate replay the journal. That is what crash injection relies on. After
  70 failed attempts Restate *pauses* the invocation instead of failing it.
- Restate serializes with its own Jackson mapper, not Spring's, and rejects unknown properties. That decides the
  rules for durable types.
- The ctx-based codegen API is deprecated in SDK 2.9 but still there. Keeping it is a recorded decision.

Other tasks found more of the same kind. Spring Boot 4 would need Jackson 3, which is outside the approved stack, so the
build stays on Boot 3.5. Container `pause` doesn't work under the dev container's nested podman, so outages are
simulated with `SIGSTOP`.

## 3. The task runner: one fresh session per task

`scripts/task-runner.sh` works through `tasks.md` from top to bottom. For each task it:

1. **Snapshots** the working tree.
2. Starts a **fresh, non-interactive Claude Code session** with no earlier conversation, no auto-memory and no MCP servers. The prompt is
   the task's section of `tasks.md`, the procedure in `.claude/skills/implement-task/SKILL.md` and the context every
   task needs (design, decisions, the file list, the approved dependencies, the environment). Each session starts
   clean, so it can't carry over a misunderstanding from an earlier one, and it can't rely on something that isn't
   written down.
3. **Gates** the result. `scripts/check.sh` must pass: the rule checks, then `mvn clean verify` with every test actually run and
   none skipped. The runner's guards must hold too:
   - the protected files are untouched (`tasks.md`, `AGENTS.md`, `requirements.md`, `scripts/`, `.claude/skills/`, `dev-setup/`)
   - no dependency outside the approved stack was added
   - there are **no fewer tests than before**
4. Sends a failure back to the same session to fix. A result that still fails is **rolled back**, and the run stops.
5. Ticks the checkbox and writes `.task-runs/<id>/latest/report.md` and `.task-runs/<id>/commit-msg.txt`. It stops for a
   person to review after any task that changed `docs/` or created `pom.xml`.

A session that needs a human decision reports `blocked`. The runner rolls back, shows the question and stops. The
person answers with `--note "…"` or a new decision row, then reruns. `scripts/autopilot.sh` runs the same loop unattended
(`--autonomous`). There, the session takes the option it would recommend, stays within the hard rules, and records the
choice as a decision row ending in **"(autonomous decision, review)"**.

`.task-runs/` is git-ignored, so the per-task reports and the run summary (`.task-runs/summary.md`) stay on the
machine that ran them. The repository keeps what matters from them: the code and the tests.

## 4. What keeps AI-written code honest

These guards are written for the ways an AI agent tends to go wrong:

| Risk | Guard |
|------|-------|
| Dropping or weakening a test to get green | The runner rejects fewer tests than before; `check.sh` fails on skipped tests or on tests that didn't run; AGENTS.md forbids weakening assertions |
| A production class without a test | `TestCoverageAuditTest` fails for any file in `api/src/main/kotlin` or `service/src/main/kotlin` without its own test or a justified entry |
| Quietly adding a library | Hard rule 1, enforced by the runner's dependency guard |
| Changing the rules or the plan to suit the code | Protected files; a change to the design must update `docs/` in the same change, and the runner stops for review |
| Docs that drift from the code | `HttpRequestsFileIT` runs `http/requests.http` and checks its `# Expect:` lines |
| Plausible but untested claims about recovery | Crash at every step boundary, endpoint restart, Restate outage, transient and declined wallet calls, each asserting the **charge count** (README, "Tests") |
| Tests that depend on the machine | Only random ports; every `restate.*` setting from `@DynamicPropertySource` |
| Non-deterministic workflow code | AGENTS.md's Restate rules (clock, wallet, configuration only inside `ctx.run`), checked by `DeterminismIT` and the crash tests |

## 5. What a person decides and reviews

- **Stack, dependencies, scope:** fixed in `AGENTS.md`. Only a person approves a new dependency or a stack change.
- **The environment:** `dev-setup/` and the Restate version it runs belong to the person; agents don't install software or change containers.
- **Autonomous decisions:** every choice made without a person was marked "(autonomous decision, review)" in the
  decision log (44 rows by the end), with its reason, so it could be accepted or changed.
- **Open questions:** the ones that still matter are in the README's Assumptions and Future work.
- **Commits:** agents never commit. The runner suggests a subject per task, and the person commits.

## 6. Lessons

- **Plan in writing, then code.** The person's effort goes into the design discussion, the rules and the reviews. The
  fresh sessions do the implementation, and they are only as good as the plan.
- **Small, ordered tasks.** Every task leaves the build green, so a bad result can be rolled back without losing
  earlier work.
- **Trust the gate, not the session's report.** A session's "done" counts only after `scripts/check.sh` and the
  guards pass.
- **Spike before you trust the AI's model of a library.** The spike corrected several assumptions about Restate (section 2) before
  production code depended on them.
- **Make the AI's decisions visible.** A decision recorded in one place is cheap to review. A decision buried in code isn't.
