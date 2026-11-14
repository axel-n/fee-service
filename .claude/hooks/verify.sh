#!/usr/bin/env bash
# Claude Code Stop hook (.claude/settings.json): the gate before Claude finishes a turn.
# When a source changed since the last run, it runs mvn clean verify. A red build or a skipped test exits 2, which keeps
# Claude working. With nothing changed since a red run Claude may stop, and the user is told the build is red.
set -uo pipefail
shopt -s nullglob
cd "${CLAUDE_PROJECT_DIR:?}" || exit 1

# One build at a time: two builds at once wipe each other's target/. A second session's hook waits here, then builds
# again only if a source changed after the first build started
exec 9<.
flock 9 2>/dev/null

result=target/verify-result # "green" or "red"; its mtime is when the last run started
log=target/verify.log

changed=$(find pom.xml .editorconfig ./*/pom.xml ./*/src -newer "$result" -print -quit 2>/dev/null)
if [[ -f $result && -z $changed ]]; then
  if [[ $(<"$result") != green ]]; then
    echo "{\"systemMessage\": \"The build is RED since the last mvn clean verify: see $log\"}"
  fi
  exit 0
fi

# mvn clean deletes target/, so the log and the result are written there once the build is done
start=$(date +%s.%N)
tmp=$(mktemp)
mvn -B clean verify >"$tmp" 2>&1 9<&-
rc=$?
mkdir -p target
mv "$tmp" "$log"
finish() { echo "$1" >"$result" && touch -d "@$start" "$result"; }

# /dev/null keeps grep off stdin when there is no report at all
reports=(/dev/null ./*/target/*-reports/TEST-*.xml)
skipped=()
if ((rc == 0)); then mapfile -t skipped < <(grep -l '<skipped' "${reports[@]}"); fi

if ((rc == 0 && ${#skipped[@]} == 0)); then
  finish green
  tests=$(grep -ho '<testcase ' "${reports[@]}" | wc -l)
  echo "{\"systemMessage\": \"mvn clean verify is green: $tests tests ran, none skipped\"}"
  exit 0
fi

finish red
{
  echo "mvn clean verify is red (full log: $log). Fix what your changes broke before you finish. If the failure is in"
  echo "code you didn't change in this session, leave it alone: stop and tell the user."
  if ((${#skipped[@]})); then
    echo "Skipped tests (every test must run):"
    printf '  %s\n' "${skipped[@]}"
  fi
  if ((rc != 0)); then
    errors=$(grep -E '^\[ERROR\]' "$log" |
      grep -vE '^\[ERROR\] *$|Help 1|full stack trace|Re-run Maven|For more information|After correcting|-rf :')
    if [[ -z $errors ]]; then errors=$(tail -n 20 "$log"); fi
    printf '%s\n' "$errors" | head -n 40
  fi
} >&2
exit 2
