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

# Two Maven modules (D76): api (the published contract) and service (the app)
MODULES=(api service)
MAIN=service/src/main/kotlin
TEST=service/src/test/kotlin
API=api/src/main/kotlin/com/example/fee
ALL_MAIN=(api/src/main/kotlin service/src/main/kotlin)
ALL_TEST=(api/src/test/kotlin service/src/test/kotlin)
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
# Only the code that handles money: a Double elsewhere (say, a backoff multiplier in restate/ or RestateProperties) is
# not money. Of config/, only the fee numbers are money.
rule "Money is BigDecimal: no Double/Float in the api module, domain, fee, validation, wallet, application, workflow, record or the fee properties (hard rule 3)" \
  "$(g '\b(Double|Float)\b|\.to(Double|Float)\(' "$API" "$PKG/domain" "$PKG/fee" "$PKG/validation" "$PKG/wallet" "$PKG/application" "$PKG/workflow" "$PKG/record" "$PKG/config/FeeProperties.kt")"
rule "Create BigDecimal from a String, never from a floating-point literal (hard rule 3)" \
  "$(g 'BigDecimal\(\s*-?[0-9]+\.[0-9]' "${ALL_MAIN[@]}" "${ALL_TEST[@]}")"
rule "The api module, domain, fee, validation and ClaimDecider are pure: no Restate imports" \
  "$(g '^import dev\.restate' "${ALL_MAIN[0]}" "$PKG/domain" "$PKG/fee" "$PKG/validation" "$PKG/record/ClaimDecider.kt")"
# Other services depend on it, so it carries no framework: only Jackson and Kotlin (D76)
rule "The api module is the published contract: no Spring imports, and nothing of the service" \
  "$(g '^import (org\.springframework|com\.example\.fee\.(api|application|config|domain|fee|record|restate|validation|wallet|workflow))\.' "${ALL_MAIN[0]}")"
rule "Nothing outside api imports api" \
  "$(g '^import com\.example\.fee\.api\.' "$MAIN" | grep -v "^$PKG/api/")"
rule "Inject Clock: no now() or currentTimeMillis() without a Clock in production code" \
  "$(g '\b(Instant|LocalDateTime|LocalDate|LocalTime|OffsetDateTime|ZonedDateTime)\.now\(\s*\)|System\.currentTimeMillis\(' "${ALL_MAIN[@]}")"
rule "Constructor injection only: no @Autowired in production code" "$(g '@Autowired' "${ALL_MAIN[@]}")"
rule "No !! and no lateinit in production code" "$(g '!!|\blateinit\b' "${ALL_MAIN[@]}")"
rule "No disabled tests: every test must run" "$(g '@(Disabled[A-Za-z]*|Ignore)\b' "${ALL_TEST[@]}")"
mapfile -t POMS < <(existing pom.xml "${MODULES[@]/%//pom.xml}")
if ((${#POMS[@]})); then
  rule "Dependencies the project ruled out (AGENTS.md hard rule 1, decisions.md 'Not used')" \
    "$(grep -nE '<artifactId>(spring-boot-starter-(actuator|validation|data-jpa|jdbc)|lombok|mockito-kotlin|kotest[a-z-]*|sdk-testing|flyway[a-z-]*|postgresql|h2|spring-cloud-[a-z-]*)</artifactId>' "${POMS[@]}")"
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

# reports <kind>: the report directories of every module, <kind> surefire (unit) or failsafe (integration)
reports() { local m; for m in "${MODULES[@]}"; do printf '%s\n' "$m/target/$1-reports"; done; }

# sum_reports <dir...>: "tests skipped" summed over the JUnit XML reports in the dirs
sum_reports() {
  local files
  mapfile -t files < <(find "$@" -maxdepth 1 -name 'TEST-*.xml' 2>/dev/null)
  if ((${#files[@]} == 0)); then echo "0 0"; return; fi
  awk 'function attr(line, name) {
         return match(line, " " name "=\"[0-9]+\"") ? substr(line, RSTART + length(name) + 3, RLENGTH - length(name) - 4) + 0 : 0
       }
       FNR == 1 { seen = 0 }
       !seen && /<testsuite[ >]/ { seen = 1; t += attr($0, "tests"); s += attr($0, "skipped") }
       END { print t + 0, s + 0 }' "${files[@]}"
}

skipped_tests() {
  find $(reports surefire) $(reports failsafe) -maxdepth 1 -name 'TEST-*.xml' -print0 2>/dev/null |
    xargs -0 -r awk '
      /<testcase / {
        c = match($0, /classname="[^"]*"/) ? substr($0, RSTART + 11, RLENGTH - 12) : ""
        n = match($0, / name="[^"]*"/) ? substr($0, RSTART + 7, RLENGTH - 8) : ""
      }
      /<skipped/ { print "    " c " > " n }' | head -n 20
}

mapfile -t UNIT_REPORTS < <(reports surefire)
mapfile -t IT_REPORTS < <(reports failsafe)
read -r unit_tests unit_skipped < <(sum_reports "${UNIT_REPORTS[@]}")
read -r it_tests it_skipped < <(sum_reports "${IT_REPORTS[@]}")

problems=()
if ((rc != 0)); then problems+=("mvn ${goals[*]} failed (exit $rc): see the [ERROR] lines above"); fi
if ((violations)); then problems+=("$violations rule violation(s): see the ✗ lines above"); fi

if [[ $mode == fast ]]; then
  echo "== tests: unit $unit_tests"
else
  echo "== tests: unit $unit_tests, integration $it_tests, skipped $((unit_skipped + it_skipped))"
  if ((rc == 0)); then
    # per module: one module's tests must not hide that the other's never ran
    for m in "${MODULES[@]}"; do
      read -r ran _ < <(sum_reports "$m/target/surefire-reports")
      if [[ -n $(find "$m/src/test" -name '*Test.kt' 2>/dev/null) ]] && ((ran == 0)); then
        problems+=("*Test classes exist in $m, but Surefire ran none of them: maven-surefire-plugin must be declared in $m/pom.xml")
      fi
      read -r ran _ < <(sum_reports "$m/target/failsafe-reports")
      if [[ $mode == full && -n $(find "$m/src/test" -name '*IT.kt' 2>/dev/null) ]] && ((ran == 0)); then
        problems+=("*IT classes exist in $m, but Failsafe ran none of them: maven-failsafe-plugin must be declared in $m/pom.xml")
      fi
    done
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
