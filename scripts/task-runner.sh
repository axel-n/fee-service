#!/usr/bin/env bash
# Implements tasks.md one task at a time, so that the system is stable again after every task.
#
#  - Clean context: every task runs in a fresh, non-interactive Claude Code session (no earlier conversation, no
#    auto-memory, no MCP servers). Its prompt is the task's section of tasks.md, .claude/skills/implement-task/SKILL.md,
#    and the context every session needs (docs/design.md, docs/decisions.md, the file list, the approved dependencies,
#    the environment), so that no session spends tokens reading or exploring it again.
#  - Order: tasks.md is in dependency order; the runner goes top to bottom.
#  - Checkpoint: the tree is snapshotted before each task. git isn't available in the dev container, so it's a tar.
#  - Gate: a result is accepted only if the guards hold (protected files untouched, no dependency outside the approved
#    stack, no fewer tests) and scripts/check.sh passes (rules + mvn clean verify, every test actually ran). Otherwise the
#    agent gets the failure to fix in the same session. After --fix-attempts the tree is rolled back to the checkpoint.
#  - Then the runner ticks the checkbox, writes a report and a suggested commit message, and moves on to the next task.
#  - Interactive (default): it halts when the agent reports "blocked" and, for review, after a task that changed docs/
#    or created pom.xml. --autonomous: nobody is waiting, so nothing halts for a person. The agent decides and records
#    its decisions, usage limits and API errors are waited out, and a rejected task is retried from its checkpoint.
#
# Run it in the dev container (dev-setup/). Unattended: scripts/autopilot.sh. Options: --help. More: AGENTS.md.
set -euo pipefail

usage() {
  cat <<'EOF'
Usage: scripts/task-runner.sh [options]

Implements the unchecked tasks of tasks.md in order. Each task gets its own fresh Claude Code session, and its result
is gated by scripts/check.sh and the runner's guards. A result that can't be made to pass is rolled back.

Which tasks (tasks.md is in dependency order: each task builds on the ones above it)
  --task ID          run only this task (e.g. 2.1). It must be the next unchecked one
  --out-of-order     let --task skip unchecked tasks above it
  --until ID         stop after this task
  --max-tasks N      run at most N tasks
How
  --autonomous       nobody is around: no review halts, no "blocked" (the agent decides and records it in
                     docs/decisions.md), usage limits and API errors are waited out, rejected tasks are retried.
                     Defaults become --fix-attempts 3 --task-retries 2 --timeout 120. scripts/autopilot.sh uses it
  --pause            after each accepted task, wait for Enter (review and commit it on the host first)
  --fix-attempts N   how many times a rejected result goes back to the agent, in the same session (default 2)
  --task-retries N   how many times a rolled-back task starts over in a fresh session (default 0)
  --note TEXT        guidance for the first task of this run, e.g. your answer to a "blocked" question
  --allow-new-deps   you approve dependencies outside the approved stack for this run (AGENTS.md, hard rule 1)
  --no-review-stop   don't halt after a task that changed docs/ or created pom.xml
  --unit-only        gate without the Testcontainers ITs (no container engine). Not a full gate
  --model M          model for the sessions (default: your Claude Code setting)
  --effort E         effort level for the sessions (default: your Claude Code setting)
  --timeout MIN      wall-clock limit per session, in minutes (default 90)
Other
  --list             show the tasks and their status
  --dry-run          print the next task's prompt and exit
  --undo             roll back the last accepted task (only if nothing changed since) and untick it
  -h, --help         this help

Environment: TASK_RUNNER_PERMISSION_MODE (default auto) is passed to claude --permission-mode.
             TASK_RUNNER_BACKOFF (default 300 s) first wait after an API error, doubled up to an hour.
             TASK_RUNNER_MAX_WAIT_HOURS (default 24) total waiting for usage limits and API errors before giving up.

Exit codes: 0 done, 1 rejected and rolled back, 2 blocked (a decision is needed), 3 halted for review,
            4 setup problem, unstable starting tree or Claude Code can't run, 130/143 interrupted (rolled back)
Files:      .task-runs/summary.md           one line per task outcome
            .task-runs/<id>/<run>/          prompt, session logs, gate logs, report.md, snapshots
            .task-runs/<id>/latest          the newest run of that task
            .task-runs/<id>/commit-msg.txt  suggested commit message once the task is accepted
EOF
}

ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
cd "$ROOT"

RUNS=.task-runs
STATE=$RUNS/state
HISTORY=$RUNS/history
SUMMARY=$RUNS/summary.md
SKILL=.claude/skills/implement-task/SKILL.md
# Not part of the tree that the runner snapshots, compares and restores (gitignored, or the runner's own files).
PRUNE=(./.git ./target ./api/target ./service/target ./.task-runs ./.idea ./out ./logs)
# The plan, the rules, the gate and the environment. A task that changes them is rejected outright.
PROTECTED=(./tasks.md ./AGENTS.md ./requirements.md ./scripts/ ./.claude/skills/ ./.claude/settings.json ./dev-setup/)
# A task may change these, but then an interactive run halts so a person reviews it (AGENTS.md: decisions change knowingly).
REVIEW=(./docs/)
# The stack AGENTS.md fixes (Spring Boot webmvc, Kotlin, Jackson 3 and 2, the Restate SDK, JUnit 6, Mockito, Testcontainers,
# Cucumber): its modules are approved. Anything else needs the user's approval (hard rule 1). check.sh rejects the ones ruled out.
STACK_DEPS='^(com\.example:fee-service-api|org\.springframework\.boot:spring-boot-(starter|starter-webmvc|starter-webmvc-test|starter-jackson|starter-test|testcontainers)|org\.jetbrains\.kotlin:.+|tools\.jackson(\.[a-z.]+)?:.+|com\.fasterxml\.jackson(\.[a-z.]+)?:.+|dev\.restate:.+|org\.junit(\.[a-z.]+)?:.+|org\.mockito:.+|org\.testcontainers:.+|io\.cucumber:.+)$'
# The same list for the sessions' prompt (keep both in sync)
STACK_DEPS_TEXT='com.example:fee-service-api (the api module of this project); org.springframework.boot:spring-boot-starter, -starter-webmvc, -starter-webmvc-test, -starter-jackson, -starter-test, -testcontainers;
org.jetbrains.kotlin:*; tools.jackson*:*; com.fasterxml.jackson*:*; dev.restate:*; org.junit*:*; org.mockito:*; org.testcontainers:*; io.cucumber:*'
# Defense in depth for the sessions; the runner checks the result itself anyway.
DENY='Bash(git *),Bash(podman *),Bash(docker *),Bash(claude *),Bash(scripts/task-runner.sh *),Bash(scripts/autopilot.sh *),'\
'Edit(tasks.md),Write(tasks.md),Edit(AGENTS.md),Write(AGENTS.md),Edit(requirements.md),Write(requirements.md),'\
'Edit(scripts/**),Write(scripts/**),Edit(.claude/**),Write(.claude/**),Edit(dev-setup/**),Write(dev-setup/**),'\
'Edit(.task-runs/**),Write(.task-runs/**),Read(.task-runs/**)'
RESULT_SCHEMA='{"type":"object","additionalProperties":false,"required":["status","summary","commit_subject"],"properties":{
"status":{"type":"string","enum":["done","blocked"],"description":"done: the task is implemented and scripts/check.sh passes. blocked: a human decision or approval is needed first"},
"summary":{"type":"string","description":"Short bullet lines: what changed and why, the tests added, the scripts/check.sh result with test counts"},
"question":{"type":"string","description":"Only when blocked: the decision or approval needed, the options, and your recommendation"},
"decisions":{"type":"string","description":"Decisions or open questions recorded in docs/, or empty"},
"deferred":{"type":"string","description":"Anything left for a later task, or empty"},
"commit_subject":{"type":"string","description":"Imperative commit subject, at most 72 characters"}}}'

TASK= UNTIL= MAX_TASKS=0 PAUSE=0 FIX_ATTEMPTS= TASK_RETRIES= NOTE= ALLOW_NEW_DEPS=0 REVIEW_STOP=1 UNIT_ONLY=0
MODEL= EFFORT= TIMEOUT= ACTION=run OUT_OF_ORDER=0 AUTONOMOUS=0
while (($#)); do
  case $1 in
    --task) TASK=${2:?--task needs an id}; shift ;;
    --out-of-order) OUT_OF_ORDER=1 ;;
    --until) UNTIL=${2:?--until needs an id}; shift ;;
    --max-tasks) MAX_TASKS=${2:?--max-tasks needs a number}; shift ;;
    --autonomous) AUTONOMOUS=1 ;;
    --pause) PAUSE=1 ;;
    --fix-attempts) FIX_ATTEMPTS=${2:?--fix-attempts needs a number}; shift ;;
    --task-retries) TASK_RETRIES=${2:?--task-retries needs a number}; shift ;;
    --note) NOTE=${2:?--note needs a text}; shift ;;
    --allow-new-deps) ALLOW_NEW_DEPS=1 ;;
    --no-review-stop) REVIEW_STOP=0 ;;
    --unit-only) UNIT_ONLY=1 ;;
    --model) MODEL=${2:?--model needs a name}; shift ;;
    --effort) EFFORT=${2:?--effort needs a level}; shift ;;
    --timeout) TIMEOUT=${2:?--timeout needs minutes}; shift ;;
    --list) ACTION=list ;;
    --dry-run) ACTION=dry-run ;;
    --undo) ACTION=undo ;;
    -h | --help) usage; exit 0 ;;
    *) echo "unknown option: $1 (see --help)" >&2; exit 4 ;;
  esac
  shift
done
if ((AUTONOMOUS)); then
  REVIEW_STOP=0 PAUSE=0
  FIX_ATTEMPTS=${FIX_ATTEMPTS:-3} TASK_RETRIES=${TASK_RETRIES:-2} TIMEOUT=${TIMEOUT:-120}
else
  FIX_ATTEMPTS=${FIX_ATTEMPTS:-2} TASK_RETRIES=${TASK_RETRIES:-0} TIMEOUT=${TIMEOUT:-90}
fi
BACKOFF0=${TASK_RUNNER_BACKOFF:-300}
MAX_WAIT=$((${TASK_RUNNER_MAX_WAIT_HOURS:-24} * 3600))

say() { printf '%s\n' "$*"; }
die() {
  local code=$1
  shift
  printf 'task-runner: %s\n' "$*" >&2
  exit "$code"
}
for n in "$MAX_TASKS" "$FIX_ATTEMPTS" "$TASK_RETRIES" "$TIMEOUT" "$BACKOFF0" "$MAX_WAIT"; do
  [[ $n =~ ^[0-9]+$ ]] || die 4 "not a number: $n"
done

# ---------------------------------------------------------------- tasks.md

id_re() { printf '%s' "${1//./\\.}"; }
unchecked_ids() { sed -nE 's/^- \[ \] ([0-9]+\.[0-9]+) .*/\1/p' tasks.md; }
next_id() { sed -nE '/^- \[ \] [0-9]+\.[0-9]+ /{s/^- \[ \] ([0-9]+\.[0-9]+) .*/\1/p;q;}' tasks.md; }
is_unchecked() { grep -qE "^- \[ \] $(id_re "$1") " tasks.md; }
task_exists() { grep -qE "^- \[[ x]\] $(id_re "$1") " tasks.md; }
task_line() { sed -nE "s/^- \[[ x]\] $(id_re "$1") (.*)/\1/p" tasks.md; }
task_title() { task_line "$1" | cut -c1-100; }
tick() { sed -i -E "s/^- \[ \] ($(id_re "$1")) /- [x] \1 /" tasks.md; }
untick() { sed -i -E "s/^- \[x\] ($(id_re "$1")) /- [ ] \1 /" tasks.md; }
# ids used more than once: a tick would hit all of them
duplicate_ids() { sed -nE 's/^- \[[ x]\] ([0-9]+\.[0-9]+) .*/\1/p' tasks.md | LC_ALL=C sort | uniq -d; }

# The unchecked tasks above <id>. tasks.md is in dependency order, so a task normally starts with none.
open_before() {
  awk -v id="$1" '
    index($0, "- [ ] " id " ") == 1 || index($0, "- [x] " id " ") == 1 { exit }
    /^- \[ \] [0-9]+\.[0-9]+ / { split($0, part, " "); print part[4] }
  ' tasks.md
}

# "<id> <text>" of the tasks after <id> in the same section
later_in_section() {
  awk -v id="$1" '
    found && /^## / { exit }
    found && /^- \[[ x]\] [0-9]+\.[0-9]+ / { print substr($0, 7, 110) }
    index($0, "- [ ] " id " ") == 1 || index($0, "- [x] " id " ") == 1 { found = 1 }
  ' tasks.md
}

# Where the task sits: what is done above it, and what later sessions own.
scope_note() {
  local before later
  before=$(open_before "$1" | paste -sd " " -)
  later=$(later_in_section "$1")
  if [[ -z $before ]]; then
    echo "Every task above this one in tasks.md is done: build on what they made."
  else
    echo "OUT OF ORDER (--out-of-order): these tasks above this one are not done yet: ${before}. Don't rely on them, and don't implement them."
  fi
  if [[ -n $later ]]; then
    echo "The later tasks of this section get their own sessions. Don't implement them here:"
    printf '%s\n' "$later" | sed 's/^/  - /'
  fi
}

skill_body() { awk 'NR == 1 && /^---$/ { fm = 1; next } fm && /^---$/ { fm = 0; next } !fm' "$SKILL"; }

# The task's whole section of tasks.md (heading, intro, every task), the task itself marked.
section_text() {
  awk -v id="$1" '
    /^## / { if (found) exit; sec = "" }
    {
      line = $0
      if (index($0, "- [ ] " id " ") == 1 || index($0, "- [x] " id " ") == 1) { found = 1; line = line "   ◀ YOUR TASK" }
      sec = sec line "\n"
    }
    END { if (found) printf "%s", sec }
  ' tasks.md
}

# The files of the tree the runner snapshots (the same pruning), one path per line
project_files() {
  local args
  mapfile -d '' -t args < <(prune_args)
  find . \( "${args[@]}" \) -prune -o -type f -print | sed 's|^\./||' | LC_ALL=C sort
}

environment_facts() {
  local java mvn_v
  java=$(java -version 2>&1 | head -n 1 || true)
  mvn_v=$(mvn -v 2>/dev/null | head -n 1 | sed 's/\x1b\[[0-9;]*m//g' || true)
  echo "- JDK: ${java:-not found}; Maven: ${mvn_v:-not found}"
  if ((UNIT_ONLY)); then
    echo "- The gate runs with --unit-only: no container engine for the ITs. Use scripts/check.sh --unit and say so."
  elif engine_ok; then
    echo "- A container engine is reachable (DOCKER_HOST=${DOCKER_HOST:-unset}): Testcontainers ITs run, scripts/check.sh is the gate."
  else
    echo "- No container engine is reachable (DOCKER_HOST=${DOCKER_HOST:-unset}): use scripts/check.sh --unit and say the ITs didn't run."
  fi
  if env | grep -q '^RESTATE_'; then
    echo "- The external dev Restate: $(env | grep '^RESTATE_' | LC_ALL=C sort | paste -sd ' ' -)"
  fi
}

# What the sessions would otherwise read or work out themselves, every task again. Every token read stays in the
# context and is paid for again on every later turn of the session, so the runner hands it over once, up front.
runner_context() {
  local f
  cat <<EOF
## Context the runner gathered for you

This prompt already contains your task's whole section of tasks.md (above), docs/design.md and docs/decisions.md (below,
as they are now), the project's file list, the approved dependencies, the environment and the usage of scripts/check.sh.
Don't read those files again, and don't explore the tree with find/ls: the list below is complete. Read the code your task
builds on or changes (the pom.xml files, the api/ and service/ sources it touches or uses), and nothing beyond that.

Not your context, don't open them:
- \`.task-runs/\`: the runner's logs, snapshots and the copy of this prompt. What earlier tasks did is in the code and docs/.
- \`scripts/\`: run scripts/check.sh, don't read it. Its usage is below, and a failure names the rule that failed.
- \`dev-setup/\`, \`.claude/\`, \`.idea/\`: the environment, summarised below.
- The rest of tasks.md. \`requirements.md\`: design.md covers it; read it only if your task is about it (README, presentation).

### Environment
$(environment_facts)

### Approved dependencies (hard rule 1)
EOF
  if ((ALLOW_NEW_DEPS)); then
    echo "This run has --allow-new-deps: a new dependency is accepted, but name it and say why in your summary."
  else
    echo "Only modules of the fixed stack: $STACK_DEPS_TEXT. Anything else is rejected by the runner."
  fi
  printf '\n### scripts/check.sh\n'
  scripts/check.sh --help 2>&1 | sed -n '/^  scripts\/check.sh/,$p' || true
  printf '\n### Project files (excluding target/, .git/, .task-runs/, .idea/)\n```\n'
  project_files
  printf '```\n'
  for f in docs/design.md docs/decisions.md; do
    printf '\n<file path="%s">\n' "$f"
    cat "$f"
    printf '</file>\n'
  done
}

AUTONOMOUS_RULES='AUTONOMOUS RUN: the user is away for hours, and nobody will answer. This overrides "Stop and ask" below: never
return status "blocked". Where you would ask, decide yourself:
- Take the option you would recommend, as long as it stays within AGENTS.md: the hard rules still hold (no dependency
  outside the approved stack, no software installs, fee records only in Postgres and no other storage, money as
  BigDecimal), and so do the protected files. Record the decision in docs/decisions.md as a new D row whose text ends
  with "(autonomous decision, review)". An open question you answer this way moves into the decisions the same way.
- If code from an earlier task is broken or lacks something this task needs, fix or add it here, minimally, and say so.
- If something can'"'"'t be done in this environment (a live demo, a manual step), do the closest useful thing (a script,
  written steps) and say what is left for a person.
- If the external dev Restate isn'"'"'t reachable, use a throwaway Testcontainers Restate instead.
- If a check can'"'"'t pass without breaking a rule, choose the closest solution within the rules, record the trade-off as
  above, and finish. Never weaken the check or the tests.
- Keep each D row short (see "Implement" below): every later session reads docs/decisions.md in full.'

build_prompt() { # <id> <note>
  local decide='When you need a decision or an approval, return status "blocked" (see "Stop and ask" below).'
  if ((AUTONOMOUS)); then decide='When you need a decision, make it yourself (see AUTONOMOUS RUN below).'; fi
  cat <<EOF
RUNNER MODE: scripts/task-runner.sh started you in a fresh, non-interactive session. Nobody can answer questions, and
nothing from earlier sessions is in your context: the plan files and the code are your context. $decide
The runner snapshotted the tree before you started. When you finish, it runs its own guards and scripts/check.sh. A
failure comes back to you to fix, and a result that still fails is rolled back. It ticks the checkbox in tasks.md itself.
EOF
  if ((AUTONOMOUS)); then printf '\n%s\n' "$AUTONOMOUS_RULES"; fi
  cat <<EOF

## The task

Task $1, marked ◀ in its section of tasks.md:

$(section_text "$1")

$(scope_note "$1")
EOF
  if [[ -n $2 ]]; then printf '\n## Note for this task\n\n%s\n' "$2"; fi
  printf '\n'
  skill_body | sed "s/\\\$ARGUMENTS/$1/g"
  printf '\n'
  runner_context
}

# ---------------------------------------------------------------- tree: manifest, snapshot, restore

prune_args() {
  local p first=1
  for p in "${PRUNE[@]}"; do
    if ((first)); then first=0; else printf '%s\0' -o; fi
    printf '%s\0' -path "$p"
  done
}

# "sha256  ./path" for every file of the tree, sorted
manifest() {
  local args
  mapfile -d '' -t args < <(prune_args)
  find . \( "${args[@]}" \) -prune -o -type f -print0 | LC_ALL=C sort -z | xargs -0 -r sha256sum
}
tree_hash() { manifest | sha256sum | cut -c1-64; }

# "A|M|D ./path" for every file that differs from the manifest in $1
changes_since() {
  manifest | awk '
    NR == FNR { h[substr($0, 67)] = substr($0, 1, 64); next }
    { p = substr($0, 67); seen[p] = 1; if (!(p in h)) print "A " p; else if (h[p] != substr($0, 1, 64)) print "M " p }
    END { for (p in h) if (!(p in seen)) print "D " p }
  ' "$1" - | LC_ALL=C sort -k2
}

snapshot() { # <tar>
  local ex=() p
  for p in "${PRUNE[@]}"; do ex+=("--exclude=$p"); done
  tar -cf "$1" "${ex[@]}" .
}

# Put the tree back exactly as it was in <tar>, whose manifest is <manifest>; fails if the result differs.
restore() {
  local tar=$1 snap_manifest=$2 nots=() p
  manifest | awk 'NR == FNR { keep[substr($0, 67)] = 1; next } !(substr($0, 67) in keep) { print substr($0, 67) }' \
    "$snap_manifest" - | while IFS= read -r f; do rm -f -- "$f"; done
  for p in "${PRUNE[@]}"; do nots+=(-not -path "$p" -not -path "$p/*"); done
  find . -mindepth 1 -depth -type d -empty "${nots[@]}" -exec rmdir -- {} \; 2>/dev/null || true
  tar -xf "$tar"
  [[ $(tree_hash) == "$(sha256sum < "$snap_manifest" | cut -c1-64)" ]]
}

matching() { # <changes-file> <prefix…>: the change lines under one of the prefixes (a prefix ending in / is a directory)
  local file=$1 line path p
  shift
  while IFS= read -r line; do
    path=${line:2}
    for p in "$@"; do
      if [[ $path == "$p" || ($p == */ && $path == "$p"*) ]]; then printf '%s\n' "$line"; break; fi
    done
  done < "$file"
  return 0
}

# groupId:artifactId of every <dependency> and <annotationProcessorPath> in the poms (exclusions and comments skipped)
pom_deps() {
  awk 'BEGIN { RS = "<" }
    incomment { if (index($0, "-->")) incomment = 0; next }
    substr($0, 1, 3) == "!--" { if (!index($0, "-->")) incomment = 1; next }
    {
      split($0, part, ">"); tag = part[1]; text = part[2]; gsub(/[ \t\r\n]/, "", text)
      if (tag == "dependency" || tag == "annotationProcessorPath") { blk = 1; ex = 0; g = ""; a = ""; next }
      if (tag == "exclusions") { ex = 1; next }
      if (tag == "/exclusions") { ex = 0; next }
      if (blk && !ex && tag == "groupId") g = text
      if (blk && !ex && tag == "artifactId") a = text
      if ((tag == "/dependency" || tag == "/annotationProcessorPath") && blk) { print g ":" a; blk = 0 }
    }' "$@" | LC_ALL=C sort -u
}

# The same over the root pom and the module poms (D76): a dependency is declared in a module, not in the root. Empty
# while there is no pom yet.
all_pom_deps() {
  local poms=() p
  for p in pom.xml api/pom.xml service/pom.xml; do if [[ -f $p ]]; then poms+=("$p"); fi; done
  if ((${#poms[@]})); then pom_deps "${poms[@]}"; fi
}

# Leftovers of test JVMs that were killed (Ryuk is off in the dev container) would pile up over a long run. Only inside
# a container, where podman is the nested engine that holds nothing but test containers.
cleanup_test_containers() {
  local ids
  if [[ ! -f /run/.containerenv ]] || ! command -v podman > /dev/null; then return 0; fi
  ids=$(podman ps -aq --filter label=org.testcontainers=true 2> /dev/null || true)
  if [[ -n $ids ]]; then
    podman rm -f $ids > /dev/null 2>&1 || true
    say "  removed $(wc -w <<< "$ids") leftover test container(s)"
  fi
}

# ---------------------------------------------------------------- state

state_get() { # <key> <default>
  local v=
  if [[ -f $STATE ]]; then v=$(sed -n "s/^$1=//p" "$STATE" | tail -n 1); fi
  printf '%s' "${v:-${2-}}"
}
state_put() { # <hash> <unit> <it> <mode>: the last tree that passed the gate
  printf 'HASH=%s\nUNIT=%s\nIT=%s\nMODE=%s\n' "$@" > "$STATE.tmp"
  mv "$STATE.tmp" "$STATE"
}
gate_mode() { if ((UNIT_ONLY)); then echo unit; else echo full; fi; }

summary_line() { # <id> <outcome> <detail>
  if [[ ! -f $SUMMARY ]]; then printf '| time | task | outcome | cost | detail |\n|---|---|---|---|---|\n' > "$SUMMARY"; fi
  printf '| %s | %s | %s | $%s | %s |\n' "$(date '+%m-%d %H:%M')" "$1" "$2" "$TASK_COST" "$(tr '\n|' ' /' <<< "$3" | cut -c1-200)" >> "$SUMMARY"
}

# ---------------------------------------------------------------- gate and session

# gate <log>: scripts/check.sh; sets GATE_UNIT and GATE_IT
gate() {
  local args=() rc last
  if ((UNIT_ONLY)); then args=(--unit); fi
  cleanup_test_containers
  set +e
  timeout --foreground --kill-after=60s 60m scripts/check.sh "${args[@]}" > "$1" 2>&1
  rc=$?
  set -e
  last=$(tail -n 1 "$1")
  GATE_UNIT=$(sed -nE 's/.* unit=([0-9]+).*/\1/p' <<< "$last")
  GATE_IT=$(sed -nE 's/.* it=([0-9]+).*/\1/p' <<< "$last")
  GATE_UNIT=${GATE_UNIT:-0} GATE_IT=${GATE_IT:-0}
  ((rc == 0)) && [[ $last == "check: OK"* ]]
}

# What the agent is doing, one line per tool call.
show_progress() {
  jq -R --unbuffered -r '
    fromjson? |
    if .type == "assistant" then
      .message.content[]? | select(.type == "tool_use")
      | "    · \(.name) \(.input | (.command // .file_path // .pattern // .url // .query // .skill // .description // "")
          | tostring | gsub("\\s+"; " ") | .[0:110])"
    elif .type == "result" then
      "    = \(.subtype), \(.num_turns) turns, $\(.total_cost_usd // 0 | . * 100 | round / 100)"
    else empty end' 2>/dev/null || true
}

# session <session-id> <prompt-file> <log-prefix> <new|resume>: sets S_RC, S_JSON, S_STATUS, S_MODEL; adds to TASK_COST
session() {
  local sid=$1 prompt=$2 log=$3 how=$4 result cost
  local args=(-p --output-format stream-json --verbose --json-schema "$RESULT_SCHEMA"
    --permission-mode "${TASK_RUNNER_PERMISSION_MODE:-auto}" --permission-prompts none
    --strict-mcp-config --disallowedTools "$DENY" --name "task $CUR_ID")
  if [[ $how == new ]]; then args+=(--session-id "$sid"); else args+=(--resume "$sid"); fi
  if [[ -n $MODEL ]]; then args+=(--model "$MODEL"); fi
  if [[ -n $EFFORT ]]; then args+=(--effort "$EFFORT"); fi
  # A session of its own even when the runner was started from inside Claude Code: drop the parent session's variables.
  # --foreground: Ctrl-C reaches claude, so an interrupted run stops at once and gets rolled back.
  set +e
  env -u CLAUDECODE -u CLAUDE_CODE_ENTRYPOINT -u CLAUDE_CODE_SESSION_ID -u CLAUDE_CODE_CHILD_SESSION \
    -u CLAUDE_CODE_SESSION_ATTENDED -u CLAUDE_CODE_MESSAGING_SOCKET -u CLAUDE_CODE_MESSAGING_TOKEN -u CLAUDE_PID \
    -u CLAUDE_EFFORT -u CLAUDE_CODE_EXECPATH CLAUDE_CODE_DISABLE_AUTO_MEMORY=1 \
    timeout --foreground --kill-after=60s "${TIMEOUT}m" claude "${args[@]}" \
    < "$prompt" 2> "$log.stderr" | tee "$log.jsonl" | show_progress
  S_RC=${PIPESTATUS[0]}
  set -e
  result=$(jq -R -c 'fromjson? | select(.type == "result")' "$log.jsonl" | tail -n 1 || true)
  S_JSON=$(jq -c '.structured_output // empty' <<< "$result" 2>/dev/null || true)
  S_STATUS=$(jq -r '.status // empty' <<< "$S_JSON" 2>/dev/null || true)
  cost=$(jq -r '.total_cost_usd // 0' <<< "$result" 2>/dev/null || true)
  TASK_COST=$(awk -v a="$TASK_COST" -v b="${cost:-0}" 'BEGIN { printf "%.2f", a + b }')
  if [[ -z ${S_MODEL:-} ]]; then
    S_MODEL=$(jq -R -r 'fromjson? | select(.type == "system" and .subtype == "init") | .model' "$log.jsonl" | head -n 1 || true)
  fi
}

field() { jq -r --arg k "$1" '.[$k] // empty' <<< "$S_JSON" 2>/dev/null || true; }

# Why a session ended without a result: "limit <resetsAt>", "transient" (API or network trouble) or "fatal" (can't run)
failure_kind() { # <log-prefix>
  local text status= resets=0
  text=$({ jq -R -r 'fromjson? | select(.type == "result") | (.result // "") | tostring' "$1.jsonl"; tail -c 4000 "$1.stderr"; } 2>/dev/null || true)
  if grep -qiE 'not logged in|/login|invalid api key|invalid x-api-key|authentication_error|oauth token (has )?expired' <<< "$text"; then
    echo fatal
    return
  fi
  if grep -qiE 'prompt is too long|context (window|length)|maximum context' <<< "$text"; then
    echo context
    return
  fi
  read -r status resets < <(jq -R -r 'fromjson? | select(.type == "rate_limit_event") | "\(.rate_limit_info.status // "") \(.rate_limit_info.resetsAt // 0)"' "$1.jsonl" | tail -n 1) || true
  if [[ -n $status && $status != allowed* ]] || grep -qiE 'usage limit|rate limit|limit reached|hit your limit|429' <<< "$text"; then
    echo "limit ${resets:-0}"
  else
    echo transient
  fi
}

# Wait out a usage limit (until it resets) or an API problem (backoff). Fails when the waiting budget is used up.
infra_wait() { # <kind> [<resetsAt>]
  local now secs
  now=$(date +%s)
  if [[ $1 == limit ]] && ((${2:-0} > now)); then
    secs=$((${2} - now + ${TASK_RUNNER_LIMIT_MARGIN:-120}))
  else
    # a problem that persists through 8 waits (about 5 hours) is not passing trouble
    STREAK=$((STREAK + 1))
    if ((STREAK > 8)); then return 1; fi
    secs=$BACKOFF
    BACKOFF=$((BACKOFF * 2 > 3600 ? 3600 : BACKOFF * 2))
  fi
  if ((WAITED + secs > MAX_WAIT)); then return 1; fi
  say "  $1: waiting $((secs / 60)) min (until $(date -d "@$((now + secs))" '+%H:%M')), then the session continues"
  sleep "$secs"
  WAITED=$((WAITED + secs))
}

# ---------------------------------------------------------------- one task

# run_task <id> <note>: sets TASK_RESULT to 0 accepted, 1 rejected (rolled back), 2 blocked (rolled back),
# 3 accepted but halt for review, 4 Claude Code can't run (rolled back). Returns 0, so errexit stays in force inside;
# CUR_DIR marks a task in progress.
run_task() {
  local id=$1 note=$2 run dir sid attempt=0 how=new prompt prev_unit prev_it hits new_deps n=0 log kind resets_at have_session=0 msg
  run=$(date +%Y%m%d-%H%M%S)
  dir=$RUNS/$id/$run
  mkdir -p "$dir"
  ln -sfn "$run" "$RUNS/$id/latest"
  CUR_ID=$id CUR_DIR=$dir TASK_COST=0 S_MODEL= S_JSON=
  PROBLEMS=() WARNINGS=()
  prev_unit=$(state_get UNIT 0)
  prev_it=$(state_get IT 0)

  say ""
  say "▶ Task $id  $(task_title "$id")   [$(date '+%H:%M')]"
  cleanup_test_containers
  snapshot "$dir/before.tar"
  manifest > "$dir/before.manifest"
  all_pom_deps > "$dir/deps.before"
  build_prompt "$id" "$note" > "$dir/prompt.md"
  sid=$(cat /proc/sys/kernel/random/uuid)
  CUR_SID=$sid
  prompt=$dir/prompt.md
  say "  checkpoint $dir/before.tar, session $sid"

  while :; do
    log=$dir/session-$n
    n=$((n + 1))
    say "  session ($how), fix attempt $attempt of $FIX_ATTEMPTS"
    session "$sid" "$prompt" "$log" "$how"
    if grep -q '"type":"assistant"' "$log.jsonl"; then have_session=1; fi

    # Usage limits and API trouble are nobody's fault: wait, then continue the same session. No attempt is used up.
    if [[ -z $S_STATUS ]] && ((S_RC != 124)) && ! grep -q '"structured_output"' "$log.jsonl"; then
      read -r kind resets_at _ <<< "$(failure_kind "$log") 0"
      if [[ $kind == fatal ]]; then
        PROBLEMS=("Claude Code can't run here (not logged in?). See $log.stderr")
        finish_rejected "$id" "$dir" rejected
        TASK_RESULT=4
        return 0
      fi
      if [[ $kind == context ]]; then
        have_session=0 # resuming would hit the same wall: the next attempt starts a new session on the partial work
      elif ((AUTONOMOUS)) && infra_wait "$kind" "$resets_at"; then
        if ((have_session)); then
          if [[ $prompt == "$dir/prompt.md" ]]; then
            printf 'RUNNER: your session was interrupted (%s) and resumed after a wait. The tree is as you left it. Continue the task where you left off, run scripts/check.sh, and return the JSON result.\n' "$kind" > "$dir/continue.md"
            prompt=$dir/continue.md
          fi
          how=resume
        else
          sid=$(cat /proc/sys/kernel/random/uuid) CUR_SID=$sid how=new
        fi
        continue
      fi
    fi
    if [[ -n $S_STATUS ]]; then BACKOFF=$BACKOFF0 STREAK=0; fi
    PROBLEMS=() WARNINGS=() new_deps=

    if [[ $S_STATUS == blocked ]] && ((!AUTONOMOUS)); then
      finish_rejected "$id" "$dir" blocked
      return 0
    fi

    changes_since "$dir/before.manifest" > "$dir/changes.txt"
    # An agent following AGENTS.md may tick the box itself. Undo that: the runner ticks after its own gate.
    if grep -qx 'M ./tasks.md' "$dir/changes.txt" &&
      [[ $(tar -xOf "$dir/before.tar" ./tasks.md | sed -E "s/^- \[ \] ($(id_re "$id")) /- [x] \1 /" | sha256sum) == "$(sha256sum < tasks.md)" ]]; then
      untick "$id"
      changes_since "$dir/before.manifest" > "$dir/changes.txt"
    fi
    hits=$(matching "$dir/changes.txt" "${PROTECTED[@]}")
    if [[ -n $hits ]]; then
      PROBLEMS=("Changed protected files (the plan, the rules, the gate or the environment), so the result is rejected outright:" "$hits")
      finish_rejected "$id" "$dir" rejected
      return 0
    fi

    if [[ $S_STATUS == blocked ]]; then
      PROBLEMS+=("You returned status blocked, but this is an autonomous run and nobody will answer. Decide as the AUTONOMOUS RUN rules say, record the decision in docs/decisions.md, and finish the task. Your question was: $(field question)")
    elif [[ $S_STATUS != done ]]; then
      if ((S_RC == 124)); then
        PROBLEMS+=("The session hit the ${TIMEOUT}-minute limit before returning a result. Finish the task now.")
      else
        PROBLEMS+=("The session ended without a result (exit code $S_RC; stderr: $log.stderr). Finish the task and return the JSON result.")
      fi
    else
      if [[ -f pom.xml ]]; then
        all_pom_deps > "$dir/deps.after"
        new_deps=$(LC_ALL=C comm -13 "$dir/deps.before" "$dir/deps.after" | grep -vE "$STACK_DEPS" || true)
        if [[ -n $new_deps ]] && ((!ALLOW_NEW_DEPS)); then
          PROBLEMS+=("Dependencies outside the approved stack need the user's approval first (AGENTS.md hard rule 1). Remove them and solve it with what the stack offers:" "$new_deps")
        fi
      fi
      say "  gate: scripts/check.sh $( ((UNIT_ONLY)) && echo --unit) …"
      if gate "$dir/gate-$attempt.log"; then
        say "  gate passed: unit tests $prev_unit → $GATE_UNIT, integration tests $prev_it → $GATE_IT"
        if ((GATE_UNIT < prev_unit)); then
          PROBLEMS+=("Unit tests went down from $prev_unit to $GATE_UNIT. Don't delete or merge away tests; restore them.")
        fi
        if ((!UNIT_ONLY && GATE_IT < prev_it)); then
          PROBLEMS+=("Integration tests went down from $prev_it to $GATE_IT. Don't delete or merge away tests; restore them.")
        fi
        if grep -qE '^[AM] \./(api|service)/src/main/kotlin/' "$dir/changes.txt" && ((GATE_UNIT + GATE_IT <= prev_unit + prev_it)); then
          WARNINGS+=("Production code changed, but no new test ran (AGENTS.md: every production class gets a test).")
        fi
      else
        say "  gate FAILED: $(tail -n 1 "$dir/gate-$attempt.log")"
        PROBLEMS+=("scripts/check.sh failed: $(tail -n 1 "$dir/gate-$attempt.log"). Full log: $dir/gate-$attempt.log")
      fi
    fi

    if ((${#PROBLEMS[@]} == 0)); then
      finish_accepted "$id" "$dir" "$run"
      return 0
    fi

    attempt=$((attempt + 1))
    if ((attempt > FIX_ATTEMPTS)); then
      finish_rejected "$id" "$dir" rejected
      return 0
    fi
    say "  not accepted; sending the problems back to the agent (fix attempt $attempt of $FIX_ATTEMPTS)"
    msg=$dir/feedback-$attempt.md
    feedback "$id" "$dir/gate-$((attempt - 1)).log" "$attempt" > "$msg"
    if ((have_session)); then
      prompt=$msg how=resume
    else # the session never got going: start a new one with the task and the feedback
      cat "$dir/prompt.md" "$msg" > "$msg.full"
      prompt=$msg.full how=new sid=$(cat /proc/sys/kernel/random/uuid) CUR_SID=$sid
    fi
  done
}

feedback() { # <id> <gate log to quote> <attempt>
  local log=$2
  printf 'RUNNER: your result for task %s was not accepted (fix attempt %s of %s).\n\n' "$1" "$3" "$FIX_ATTEMPTS"
  printf '%s\n' "${PROBLEMS[@]}" | sed 's/^/- /'
  if [[ -f $log ]] && ! [[ $(tail -n 1 "$log") == "check: OK"* ]]; then
    printf '\nFrom %s:\n```\n' "$log"
    { grep -E '^(✗|    |\[ERROR\]| - |check: |== )' "$log" | grep -vE '^\[ERROR\] *$' | head -n 150; } || true
    printf '```\n'
  fi
  printf '\nFix the cause, not the check: don'"'"'t delete, disable or weaken tests, and don'"'"'t edit scripts/, tasks.md or the\n'
  printf 'other protected files. Run scripts/check.sh until it ends with "check: OK", then return the JSON result again.\n'
  if ((AUTONOMOUS)); then
    printf 'If it can'"'"'t be fixed without breaking a rule, take the closest solution within the rules, record the trade-off in\n'
    printf 'docs/decisions.md ("autonomous decision, review"), and finish. Don'"'"'t return status "blocked".\n'
  else
    printf 'If it can'"'"'t be fixed without breaking a rule or a decision in docs/, return status "blocked" and explain.\n'
  fi
}

finish_accepted() { # <id> <dir> <run>
  local id=$1 dir=$2 run=$3 hash unit=$GATE_UNIT it=$GATE_IT review subject w
  tick "$id"
  if ((UNIT_ONLY)); then it=$(state_get IT 0); fi # a unit-only gate didn't measure them
  hash=$(tree_hash)
  state_put "$hash" "$unit" "$it" "$(gate_mode)"
  printf '%s %s %s %s\n' "$(date -Is)" "$id" "$run" "$hash" >> "$HISTORY"
  CUR_DIR= # accepted: nothing to roll back from here on
  TASK_RESULT=0

  subject=$(field commit_subject | head -n 1 | cut -c1-72)
  {
    printf '%s\n\nTask %s: %s\n\n' "${subject:-Implement task $id}" "$id" "$(task_line "$id")"
    field summary
    printf '\nCo-Authored-By: Claude <noreply@anthropic.com>\n'
  } > "$RUNS/$id/commit-msg.txt"

  review=$(matching "$dir/changes.txt" "${REVIEW[@]}")
  if [[ ! -s $dir/deps.before && -f pom.xml ]]; then
    review+=$'\n'"A ./pom.xml (created; review its dependencies:"$'\n'"$(all_pom_deps | sed 's/^/    /'))"
  fi
  if [[ -n ${new_deps:-} ]]; then WARNINGS+=("New dependencies, approved with --allow-new-deps: $(tr '\n' ' ' <<< "$new_deps")"); fi
  review=$(sed '/^$/d' <<< "$review")
  write_report "$id" "$dir" accepted "$review"
  summary_line "$id" accepted "unit $GATE_UNIT, it $GATE_IT. $(field decisions | head -c 120)"
  say "✓ Task $id accepted (\$$TASK_COST). Report: $dir/report.md"
  say "  Commit message: $RUNS/$id/commit-msg.txt"
  for w in "${WARNINGS[@]}"; do say "  ! $w"; done
  if [[ -n $review ]] && ((REVIEW_STOP)); then
    say ""
    say "Halting for review: this task changed something a person should look at before the next task builds on it:"
    printf '%s\n' "$review" | sed 's/^/    /'
    say "Undo it with --undo, or continue with the same command."
    TASK_RESULT=3
  fi
}

finish_rejected() { # <id> <dir> <rejected|blocked>
  local id=$1 dir=$2 how=$3
  snapshot "$dir/attempt.tar"
  changes_since "$dir/before.manifest" > "$dir/changes.txt"
  if restore "$dir/before.tar" "$dir/before.manifest"; then
    say "  rolled back to the checkpoint; the rejected work is kept in $dir/attempt.tar"
  else
    say "  !! the rollback doesn't match the checkpoint: compare the tree with $dir/before.tar"
  fi
  CUR_DIR=
  write_report "$id" "$dir" "$how" ""
  if [[ $how == blocked ]]; then
    TASK_RESULT=2
    summary_line "$id" blocked "$(field question)"
    say "⏸ Task $id is blocked: the agent needs a decision (\$$TASK_COST)."
    say ""
    field question | sed 's/^/    /'
    say ""
    say "Record the decision in docs/decisions.md, or rerun with --note \"<your answer>\". Report: $dir/report.md"
  else
    TASK_RESULT=1
    summary_line "$id" "rejected, rolled back" "${PROBLEMS[0]:-}"
    say "✗ Task $id rejected and rolled back (\$$TASK_COST). Report: $dir/report.md"
    printf '%s\n' "${PROBLEMS[@]}" | sed 's/^/    /'
  fi
}

write_report() { # <id> <dir> <accepted|rejected|blocked> <review>
  local id=$1 dir=$2 how=$3 review=$4 text
  {
    printf '# Task %s: %s\n\n%s\n\n' "$id" "$how" "$(task_line "$id")"
    printf -- '- Run: %s, model: %s, cost: $%s\n' "$(basename "$dir")" "${S_MODEL:-?}" "$TASK_COST"
    printf -- '- Session: %s (inspect it: `claude --resume %s`)\n' "$CUR_SID" "$CUR_SID"
    if [[ $how == accepted ]]; then printf -- '- Tests: unit %s, integration %s\n' "$GATE_UNIT" "$GATE_IT"; fi
    if [[ $how != accepted ]]; then printf -- '- The tree was rolled back. The rejected work: %s/attempt.tar\n' "$dir"; fi
    printf '\n## Changed files\n\n```\n%s\n```\n' "$(cat "$dir/changes.txt" 2>/dev/null)"
    if [[ -n $review ]]; then printf '\n## Needs review\n\n```\n%s\n```\n' "$review"; fi
    if ((${#PROBLEMS[@]})); then printf '\n## Problems\n\n'; printf '%s\n' "${PROBLEMS[@]}"; fi
    if ((${#WARNINGS[@]})); then printf '\n## Warnings\n\n'; printf -- '- %s\n' "${WARNINGS[@]}"; fi
    for f in question summary decisions deferred; do
      text=$(field "$f")
      if [[ -n $text ]]; then printf '\n## Agent: %s\n\n%s\n' "$f" "$text"; fi
    done
  } > "$dir/report.md"
}

# ---------------------------------------------------------------- run

# The next task builds only on a tree that passed the gate. Skipped when the tree is unchanged since the last pass.
ensure_stable() {
  local hash log
  hash=$(tree_hash)
  if [[ $hash == "$(state_get HASH)" && ($(state_get MODE) == full || $UNIT_ONLY == 1) ]]; then return 0; fi
  log=$RUNS/baseline-$(date +%Y%m%d-%H%M%S).log
  say "Checking that the current tree is stable (scripts/check.sh, log: $log) …"
  if ! gate "$log"; then
    die 4 "the current tree fails scripts/check.sh: $(tail -n 1 "$log"). Tasks only build on a stable tree; fix it first."
  fi
  state_put "$hash" "$GATE_UNIT" "$GATE_IT" "$(gate_mode)"
  say "Stable: unit tests $GATE_UNIT, integration tests $GATE_IT."
}

engine_ok() {
  local host=${DOCKER_HOST:-unix:///var/run/docker.sock}
  if [[ $host != unix://* ]]; then return 0; fi
  curl -s --max-time 5 --unix-socket "${host#unix://}" http://localhost/_ping 2>/dev/null | grep -q OK
}

preflight() {
  local c
  for c in claude mvn jq tar sha256sum flock timeout awk sed find sort comm curl xargs paste; do
    command -v "$c" > /dev/null || die 4 "'$c' not found. Run this in the dev container (dev-setup/README.md)."
  done
  [[ -f $SKILL && -x scripts/check.sh ]] || die 4 "$SKILL or scripts/check.sh is missing"
  if ((!UNIT_ONLY)) && ! engine_ok; then
    die 4 "no container engine at ${DOCKER_HOST:-/var/run/docker.sock}: the gate needs it for the Testcontainers ITs (or use --unit-only)"
  fi
  if ((UNIT_ONLY)); then say "!! --unit-only: the gate skips the Testcontainers/Cucumber ITs. This is not a full gate."; fi
}

lock() {
  mkdir -p "$RUNS"
  exec 9> "$RUNS/runner.lock"
  flock -n 9 || die 4 "another task runner is active in this project"
  echo "$$" > "$RUNS/runner.pid"
}

# Whatever ends the runner in the middle of a task (Ctrl-C, a stop, an unexpected error) rolls that task back.
on_exit() {
  local code=$?
  set +e
  if [[ -n ${CUR_DIR:-} ]]; then
    say ""
    say "Stopped in the middle of task $CUR_ID (exit code $code)."
    snapshot "$CUR_DIR/attempt.tar"
    if restore "$CUR_DIR/before.tar" "$CUR_DIR/before.manifest"; then
      say "Rolled back to before task $CUR_ID. The unfinished work is kept in $CUR_DIR/attempt.tar"
    else
      say "!! The rollback doesn't match the checkpoint: compare the tree with $CUR_DIR/before.tar"
    fi
    summary_line "$CUR_ID" "stopped, rolled back" "exit code $code"
  fi
  rm -f "$RUNS/runner.pid"
}

list_tasks() {
  local next
  next=$(next_id)
  sed -nE 's/^- \[([ x])\] ([0-9]+\.[0-9]+) (.*)/\1\t\2\t\3/p' tasks.md | while IFS=$'\t' read -r mark id rest; do
    if [[ $mark == x ]]; then mark='[x]'; elif [[ $id == "$next" ]]; then mark=' ▶ '; else mark='[ ]'; fi
    printf '%s %-5s %s\n' "$mark" "$id" "${rest:0:100}"
  done
}

undo_last() {
  local when id run hash
  [[ -s $HISTORY ]] || die 4 "nothing to undo"
  read -r when id run hash < <(tail -n 1 "$HISTORY")
  [[ $(tree_hash) == "$hash" ]] ||
    die 4 "the tree changed after task $id was accepted ($when); undo it by hand, or with git on the host"
  restore "$RUNS/$id/$run/before.tar" "$RUNS/$id/$run/before.manifest" ||
    die 4 "the rollback doesn't match the checkpoint: compare the tree with $RUNS/$id/$run/before.tar"
  sed -i '$d' "$HISTORY"
  rm -f "$STATE" "$RUNS/$id/commit-msg.txt" # the next run re-gates and recounts the tests
  say "Task $id rolled back to its checkpoint and unticked."
}

[[ -f tasks.md ]] || die 4 "no tasks.md in $ROOT"
dups=$(duplicate_ids | paste -sd " " -)
[[ -z $dups ]] || die 4 "task ids used more than once in tasks.md: ${dups}. Ids must be unique."
if [[ -n $TASK ]]; then
  task_exists "$TASK" || die 4 "no task $TASK in tasks.md"
fi
if [[ -n $UNTIL ]]; then
  task_exists "$UNTIL" || die 4 "no task $UNTIL in tasks.md"
  is_unchecked "$UNTIL" || die 4 "task $UNTIL is already checked"
fi

case $ACTION in
  list) list_tasks; exit 0 ;;
  dry-run)
    next=${TASK:-$(next_id)}
    if [[ -z $next ]]; then say "Every task in tasks.md is checked."; exit 0; fi
    build_prompt "$next" "$NOTE"
    exit 0 ;;
  undo) lock; undo_last; rm -f "$RUNS/runner.pid"; exit 0 ;;
esac

if [[ -n $TASK ]]; then
  is_unchecked "$TASK" || die 4 "task $TASK is already checked"
  open=$(open_before "$TASK" | paste -sd " " -)
  if [[ -n $open ]] && ((!OUT_OF_ORDER)); then
    die 4 "task $TASK builds on tasks that aren't done yet: ${open} (tasks.md is in dependency order). Run them first, or pass --out-of-order."
  fi
  TODO=("$TASK")
else
  TODO=()
  for id in $(unchecked_ids); do
    TODO+=("$id")
    if [[ $id == "$UNTIL" ]]; then break; fi
  done
fi
if ((${#TODO[@]} == 0)); then say "Every task in tasks.md is checked."; exit 0; fi

preflight
lock
CUR_DIR=
trap on_exit EXIT
trap 'exit 130' INT
trap 'exit 143' TERM HUP
BACKOFF=$BACKOFF0 WAITED=0 STREAK=0
say "Task runner started $(date '+%Y-%m-%d %H:%M'): ${#TODO[@]} task(s) to go, $( ((AUTONOMOUS)) && echo autonomous || echo interactive)," \
  "model ${MODEL:-default}, effort ${EFFORT:-default}, fix attempts $FIX_ATTEMPTS, task retries $TASK_RETRIES."

done_count=0 total_cost=0 TASK_RESULT=0
for id in "${TODO[@]}"; do
  ensure_stable
  is_unchecked "$id" || continue
  note=$NOTE tries=0
  while :; do
    TASK_RESULT=1
    run_task "$id" "$note"
    total_cost=$(awk -v a="$total_cost" -v b="$TASK_COST" 'BEGIN { printf "%.2f", a + b }')
    if ((TASK_RESULT != 1 || tries >= TASK_RETRIES)); then break; fi
    tries=$((tries + 1))
    prev=$RUNS/$id/$(readlink "$RUNS/$id/latest")
    say "  starting task $id over from its checkpoint in a fresh session (retry $tries of $TASK_RETRIES)"
    note="${NOTE:+$NOTE

}An earlier attempt at this task was rejected and rolled back. Don't repeat its problems:
$(printf '%s\n' "${PROBLEMS[@]}" | head -n 40)
Its report and its work, for reference (read them, don't edit them): $prev/report.md, $prev/attempt.tar"
  done
  NOTE= # the note was for the first task of the run
  if ((TASK_RESULT == 0 || TASK_RESULT == 3)); then done_count=$((done_count + 1)); fi
  if ((TASK_RESULT != 0)); then break; fi
  if ((MAX_TASKS && done_count >= MAX_TASKS)); then break; fi
  if ((PAUSE)) && [[ $id != "${TODO[-1]}" ]]; then
    say "  Review it, and commit it on the host if you like:  git add -A && git commit -F $RUNS/$id/commit-msg.txt"
    if ! read -r -p "  Enter: next task, q: stop. " answer < /dev/tty || [[ $answer == q* ]]; then break; fi
  fi
done

say ""
say "Accepted $done_count task(s) in this run, \$$total_cost. Next task: $(next_id) [$(date '+%Y-%m-%d %H:%M')]"
exit "$TASK_RESULT"
