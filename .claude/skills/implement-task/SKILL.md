---
name: implement-task
description: Implement exactly one task from tasks.md (the given id, or the next unchecked one) with tests, and finish only when scripts/check.sh passes. Run it in a fresh session (/clear first) so the task starts from a clean context. scripts/task-runner.sh uses the same procedure for its headless sessions.
argument-hint: "[task id, e.g. 2.1]"
disable-model-invocation: true
---

# Implement one task from tasks.md

Task: **$ARGUMENTS** (if empty: the first unchecked `- [ ]` task in `tasks.md`).

One task per session. Your context is the plan and the code, not earlier conversations.

## 1. Understand

- `AGENTS.md` is already loaded. Its hard rules, architecture rules and testing rules apply to every line you write.
- Read `docs/design.md`, `docs/decisions.md`, and the task's whole section in `tasks.md`, including the text above the
  checkboxes. For example, section 8 defines the test harness and the random-port rules there.
  In runner mode they are already in your prompt, with the file list and the environment: don't read them again.
- Look at what earlier tasks built (the `pom.xml` files, `api/src/`, `service/src/`) before adding anything. Extend it; don't duplicate it. Read the
  files your task builds on or changes, not the whole tree, and never the runner's `.task-runs/` or `scripts/`.
- If an earlier task that this one depends on is missing or broken, stop (see 4) instead of quietly doing its work.

## 2. Implement

- Do exactly this task and nothing from later tasks. If it needs a piece that a later task owns, build the smallest version
  that works and say so in your summary.
- Write the production code together with its tests (AGENTS.md "Testing"). Workflow changes keep the recovery tests passing.
- No new dependencies, no software installs, no `podman`/`docker` commands, no git. Stop any process you start
  (e.g. `mvn spring-boot:run`) before you finish.
- A new decision or open question goes into `docs/decisions.md` (and `docs/design.md` if the design changes). Call out
  breaking changes to journaled or stored state (AGENTS.md "Restate workflow code").
- A D row holds the decision, its one-line reason, and only the facts a later task acts on (an API's behaviour, a version).
  Evidence (what you ran and saw), narration and facts design.md already states go in your summary, not in docs/: every
  later session reads decisions.md in full. Aim for 1–3 sentences, like D1–D24.

## 3. Verify

- While you work: `scripts/check.sh --fast` (rules + `mvn test`).
- Before you finish: `scripts/check.sh` (rules + `mvn clean verify` including the Testcontainers/Cucumber ITs; every test
  must actually run and none may be skipped). It must end with `check: OK`.
- Fix the code, not the check. Never delete, disable or weaken a test or an assertion to get green. Never edit `scripts/`.
- No container engine? Run `scripts/check.sh --unit` and say that the ITs did not run.

## 4. Stop and ask instead of guessing

Stop if the task conflicts with `docs/design.md` or `docs/decisions.md`, needs a new dependency, depends on an open question,
or can't be verified. Say what is needed, the options, and your recommendation.
In an interactive session, ask the user. In runner mode, return status `blocked` with that as the question; the runner rolls
the tree back and shows it to the user.

## 5. Finish

- Report what changed, the tests you added, the `scripts/check.sh` result (test counts), decisions recorded, anything
  deferred, and a commit subject (imperative, at most 72 characters). Don't commit.
- In an interactive session, tick the task's checkbox in `tasks.md` only after `scripts/check.sh` has passed.
- In runner mode, don't edit `tasks.md`, `AGENTS.md`, `requirements.md`, `scripts/`, `.claude/` or `dev-setup/`. The runner
  ticks the checkbox after its own gate. Your final answer is the JSON result.
