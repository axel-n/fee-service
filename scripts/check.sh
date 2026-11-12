#!/usr/bin/env bash
# The project's definition of "stable": the AGENTS.md rules that grep can check, then the build and every test.
# Agents run it before reporting a task done; scripts/task-runner.sh runs it as its gate after every task.
#
#   scripts/check.sh           gate: rules + mvn clean verify (unit tests + Testcontainers/Cucumber ITs). Needs a container engine
#   scripts/check.sh --unit    rules + mvn clean test, for machines without a container engine. The ITs do NOT run
#   scripts/check.sh --fast    rules + mvn test, incremental. For the edit loop, not a gate
#   scripts/check.sh --rules   rules only
#
# A green build is not enough: the tests must have run (*Test classes → Surefire reports, *IT classes → Failsafe reports)
# and none may be skipped. The last line is for scripts: "check: OK unit=<n> it=<n>" or "check: FAILED <reason>".
set -uo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")/.."

case ${1:-} in
  '') mode=full ;;
  --unit) mode=unit ;;
  --fast) mode=fast ;;
  --rules) mode=rules ;;
  -h | --help) sed -n '2,11p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
  *) echo "unknown option: $1 (see --help)" >&2; exit 2 ;;
esac

MAIN=src/main/kotlin
TEST=src/test/kotlin
PKG=$MAIN/com/example/fee
violations=0

existing() { local p; for p in "$@"; do if [[ -e $p ]]; then printf '%s\n' "$p"; fi; done; }

# g <regex> <paths...>: grep -nE over the Kotlin files under the paths that exist
g() {
  local pattern=$1 paths
  shift
  mapfile -t paths < <(existing "$@")
  if ((${#paths[@]} == 0)); then return 0; fi
  grep -rnE --include='*.kt' -- "$pattern" "${paths[@]}" || true
}

# rule <description> <hits>: a non-empty hit list is a violation
rule() {
  if [[ -z $2 ]]; then return 0; fi
  violations=$((violations + 1))
  echo "✗ $1"
  printf '%s\n' "$2" | head -n 20 | sed 's/^/    /'
}

echo "== rules (AGENTS.md)"
# Only the packages that handle money: a Double elsewhere (say, a backoff multiplier in restate/) is not money.
rule "Money is BigDecimal: no Double/Float in contract, domain, fee, validation, wallet or application (hard rule 3)" \
  "$(g '\b(Double|Float)\b|\.to(Double|Float)\(' "$PKG/contract" "$PKG/domain" "$PKG/fee" "$PKG/validation" "$PKG/wallet" "$PKG/application")"
rule "Create BigDecimal from a String, never from a floating-point literal (hard rule 3)" \
  "$(g 'BigDecimal\(\s*-?[0-9]+\.[0-9]' "$MAIN" "$TEST")"
rule "contract, domain, fee, validation and ClaimDecider are pure: no Restate imports" \
  "$(g '^import dev\.restate' "$PKG/contract" "$PKG/domain" "$PKG/fee" "$PKG/validation" "$PKG/restate/ClaimDecider.kt")"
rule "Nothing outside api imports api" \
  "$(g '^import com\.example\.fee\.api\.' "$MAIN" | grep -v "^$PKG/api/")"
rule "Inject Clock: no now() or currentTimeMillis() without a Clock in production code" \
  "$(g '\b(Instant|LocalDateTime|LocalDate|LocalTime|OffsetDateTime|ZonedDateTime)\.now\(\s*\)|System\.currentTimeMillis\(' "$MAIN")"
rule "Constructor injection only: no @Autowired in production code" "$(g '@Autowired' "$MAIN")"
rule "No !! and no lateinit in production code" "$(g '!!|\blateinit\b' "$MAIN")"
rule "No disabled tests: every test must run" "$(g '@(Disabled[A-Za-z]*|Ignore)\b' "$TEST")"
if [[ -f pom.xml ]]; then
  rule "Dependencies the project ruled out (AGENTS.md hard rule 1, decisions.md 'Not used')" \
    "$(grep -nE '<artifactId>(spring-boot-starter-(actuator|validation|data-jpa|jdbc)|lombok|mockito-kotlin|kotest[a-z-]*|sdk-testing|flyway[a-z-]*|postgresql|h2|spring-cloud-[a-z-]*)</artifactId>' pom.xml)"
fi
if ((violations == 0)); then echo "✓ no rule violations"; fi

if [[ $mode == rules ]]; then
  if ((violations)); then echo "check: FAILED $violations rule violation(s)"; exit 1; fi
  echo "check: OK (rules only)"
  exit 0
fi

if [[ ! -f pom.xml ]]; then
  echo "== maven: no pom.xml yet, nothing to build or test"
  if ((violations)); then echo "check: FAILED $violations rule violation(s)"; exit 1; fi
  echo "check: OK unit=0 it=0"
  exit 0
fi

engine_ok() {
  local host=${DOCKER_HOST:-unix:///var/run/docker.sock}
  if [[ $host != unix://* ]]; then return 0; fi # tcp://…: leave it to Testcontainers
  curl -s --max-time 5 --unix-socket "${host#unix://}" http://localhost/_ping 2>/dev/null | grep -q OK
}

case $mode in
  full)
    if ! engine_ok; then
      echo "check: FAILED no container engine at ${DOCKER_HOST:-/var/run/docker.sock}, so the Testcontainers ITs can't run." \
        "Use --unit and say that the ITs did not run."
      exit 1
    fi
    goals=(clean verify) ;;
  unit) goals=(clean test) ;;
  fast) goals=(test) ;;
esac

echo "== mvn ${goals[*]}"
mvn -B "${goals[@]}"
rc=$?

# sum_reports <dir>: "tests skipped" summed over the JUnit XML reports in <dir>
sum_reports() {
  local files
  mapfile -t files < <(find "$1" -maxdepth 1 -name 'TEST-*.xml' 2>/dev/null)
  if ((${#files[@]} == 0)); then echo "0 0"; return; fi
  awk 'function attr(line, name) {
         return match(line, " " name "=\"[0-9]+\"") ? substr(line, RSTART + length(name) + 3, RLENGTH - length(name) - 4) + 0 : 0
       }
       FNR == 1 { seen = 0 }
       !seen && /<testsuite[ >]/ { seen = 1; t += attr($0, "tests"); s += attr($0, "skipped") }
       END { print t + 0, s + 0 }' "${files[@]}"
}

skipped_tests() {
  find target/surefire-reports target/failsafe-reports -maxdepth 1 -name 'TEST-*.xml' -print0 2>/dev/null |
    xargs -0 -r awk '
      /<testcase / {
        c = match($0, /classname="[^"]*"/) ? substr($0, RSTART + 11, RLENGTH - 12) : ""
        n = match($0, / name="[^"]*"/) ? substr($0, RSTART + 7, RLENGTH - 8) : ""
      }
      /<skipped/ { print "    " c " > " n }' | head -n 20
}

read -r unit_tests unit_skipped < <(sum_reports target/surefire-reports)
read -r it_tests it_skipped < <(sum_reports target/failsafe-reports)

problems=()
if ((rc != 0)); then problems+=("mvn ${goals[*]} failed (exit $rc): see the [ERROR] lines above"); fi
if ((violations)); then problems+=("$violations rule violation(s): see the ✗ lines above"); fi

if [[ $mode == fast ]]; then
  echo "== tests: unit $unit_tests"
else
  echo "== tests: unit $unit_tests, integration $it_tests, skipped $((unit_skipped + it_skipped))"
  if ((rc == 0)); then
    if [[ -n $(find src/test -name '*Test.kt' 2>/dev/null) ]] && ((unit_tests == 0)); then
      problems+=("*Test classes exist, but Surefire ran no tests")
    fi
    if [[ $mode == full && -n $(find src/test -name '*IT.kt' 2>/dev/null) ]] && ((it_tests == 0)); then
      problems+=("*IT classes exist, but Failsafe ran none of them: maven-failsafe-plugin must be declared in pom.xml (task 0.1)")
    fi
  fi
  if ((unit_skipped + it_skipped > 0)); then
    problems+=("$((unit_skipped + it_skipped)) test(s) skipped; every test must run:")
    problems+=("$(skipped_tests)")
  fi
fi

if ((${#problems[@]})); then
  echo
  echo "== FAILED"
  printf ' - %s\n' "${problems[@]}"
  echo "check: FAILED ${problems[0]}"
  exit 1
fi
if [[ $mode == unit ]]; then echo "!! unit tests only: the Testcontainers/Cucumber ITs did NOT run"; fi
if [[ $mode == fast ]]; then echo "check: OK (fast, not a gate) unit=$unit_tests"; else echo "check: OK unit=$unit_tests it=$it_tests"; fi
