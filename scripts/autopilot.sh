#!/usr/bin/env bash
# Runs scripts/task-runner.sh unattended: --autonomous, Opus at xhigh effort, detached from the terminal so it keeps going
# when the shell closes. It stops when every task is done, or when a task still fails after its retries (that task is
# rolled back, so the tree is always the last stable state).
#
#   scripts/autopilot.sh [runner options]   start (e.g. --until 6.5); AUTOPILOT_MODEL / AUTOPILOT_EFFORT override the model
#   scripts/autopilot.sh status             running or not, progress, the latest outcomes
#   scripts/autopilot.sh log                follow the log
#   scripts/autopilot.sh stop               stop; the task in progress is rolled back
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/.."

RUNS=.task-runs
LOG=$RUNS/autopilot.log
PIDFILE=$RUNS/runner.pid # written by the runner itself

pid() { cat "$PIDFILE" 2> /dev/null || true; }
running() { local p; p=$(pid); [[ -n $p ]] && kill -0 "$p" 2> /dev/null; }

case ${1:-start} in
  status)
    if running; then echo "running (pid $(pid))"; else echo "not running"; fi
    echo "tasks done: $(grep -c '^- \[x\]' tasks.md) of $(grep -cE '^- \[[ x]\] [0-9]' tasks.md); next: $(scripts/task-runner.sh --list | grep -m1 '▶' || echo none)"
    if [[ -f $RUNS/summary.md ]]; then echo; tail -n 8 "$RUNS/summary.md"; fi
    if [[ -f $LOG ]]; then echo; tail -n 15 "$LOG"; fi
    ;;
  log)
    exec tail -n 40 -f "$LOG"
    ;;
  stop)
    if ! running; then echo "not running"; exit 0; fi
    kill -TERM -- "-$(pid)" 2> /dev/null || kill -TERM "$(pid)"
    echo "stopping: the task in progress is rolled back (see $LOG)"
    ;;
  *)
    if [[ ${1:-} == start ]]; then shift; fi
    if running; then echo "already running (pid $(pid)); see: scripts/autopilot.sh status" >&2; exit 1; fi
    mkdir -p "$RUNS"
    printf '\n===== autopilot start %s: %s\n' "$(date '+%Y-%m-%d %H:%M')" "$*" >> "$LOG"
    args=(--autonomous --model "${AUTOPILOT_MODEL:-claude-opus-5-5}")
    if [[ -n ${AUTOPILOT_EFFORT-xhigh} ]]; then args+=(--effort "${AUTOPILOT_EFFORT-xhigh}"); fi # empty: the model's default
    # setsid + nohup: its own session, immune to the terminal closing; `stop` signals the whole process group
    setsid nohup scripts/task-runner.sh "${args[@]}" "$@" >> "$LOG" 2>&1 < /dev/null &
    for _ in $(seq 20); do
      if running; then break; fi
      sleep 0.5
    done
    if running; then
      echo "autopilot started (pid $(pid)). Progress: scripts/autopilot.sh status | log | stop"
    else
      echo "autopilot didn't start; last lines of $LOG:" >&2
      tail -n 20 "$LOG" >&2
      exit 1
    fi
    ;;
esac
