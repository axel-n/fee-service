#!/usr/bin/env bash
# fee-service live demo (docs/demo.md): self-registration → a fee → the same payload again (one charge) → kill -9 while
# the wallet is slow → restart → Restate resumes the workflow (one charge) → the journal.
#
# It runs the packaged app (target/fee-service-*.jar) as a real process, so "kill" is a real kill -9. It checks every
# claim it makes and exits non-zero if one doesn't hold; DemoIT runs it in the build against a Testcontainers Restate.
#
# Usage: demo/demo.sh [--no-pause]
#   --no-pause   don't wait for Enter between the steps (rehearsal, tests)
#
# Settings (environment variables):
#   RESTATE_ADMIN_URL, RESTATE_INGRESS_URL, RESTATE_ADVERTISED_URL, RESTATE_ENDPOINT_PORT
#                     where Restate is, read by the app as usual (README, "Configuration"); the script uses the admin URL
#                     for its checks and the journal. Default: the app's defaults (localhost)
#   DEMO_API_PORT     the app's HTTP port (default 8081); must be free
#   DEMO_SLOW_WALLET  the wallet's wait in the crash part (default 20s)
#   DEMO_RUN          a suffix for the transaction ids (txn_001-<run>), for a Restate that already saw txn_001
#   DEMO_JAR          the app jar (default: target/fee-service-*.jar, built with mvn if missing)
#   DEMO_APP_ARGS     extra command-line arguments for the app, separated by spaces
#   DEMO_LOG_DIR      where the app logs go (default target/demo)
set -euo pipefail

cd "$(dirname "$0")/.."

PAUSE=true
case "${1:-}" in
    --no-pause) PAUSE=false ;;
    -h | --help) sed -n '2,/^set -euo/p' "$0" | sed '$d; s/^# \{0,1\}//'; exit 0 ;;
    "") ;;
    *) echo "unknown option: $1 (see --help)" >&2; exit 2 ;;
esac

ADMIN_URL="${RESTATE_ADMIN_URL:-http://localhost:9070}"
API_PORT="${DEMO_API_PORT:-8081}"
API="http://localhost:$API_PORT"
SLOW_WALLET="${DEMO_SLOW_WALLET:-20s}"
SUFFIX="${DEMO_RUN:+-$DEMO_RUN}"
TXN1="txn_001$SUFFIX"
TXN2="txn_002$SUFFIX"
LOG_DIR="${DEMO_LOG_DIR:-target/demo}"
read -r -a APP_ARGS <<<"${DEMO_APP_ARGS:-}"

APP_PID=""
APP_LOG=""
CALLER_PID=""

if [[ -t 1 ]]; then BOLD=$'\e[1m'; DIM=$'\e[2m'; GREEN=$'\e[32m'; RED=$'\e[31m'; OFF=$'\e[0m'
else BOLD=""; DIM=""; GREEN=""; RED=""; OFF=""; fi

# --- output -----------------------------------------------------------------------------------------------------------

step() { printf '\n%s━━ %s%s\n' "$BOLD" "$*" "$OFF"; }
say() { printf '%s\n' "$*"; }
ok() { printf '%s✔ %s%s\n' "$GREEN" "$*" "$OFF"; }
fail() {
    printf '%s✘ %s%s\n' "$RED" "$*" "$OFF" >&2
    if [[ -n "$APP_LOG" && -f "$APP_LOG" ]]; then
        printf '%sLast lines of %s:%s\n' "$DIM" "$APP_LOG" "$OFF" >&2
        tail -n 20 "$APP_LOG" >&2
    fi
    exit 1
}
pause() {
    if [[ "$PAUSE" == true && -t 0 ]]; then
        read -r -p "${DIM}[Enter] ${1:-next step}${OFF}" _
    fi
}

# --- the app ----------------------------------------------------------------------------------------------------------

find_jar() {
    if [[ -n "${DEMO_JAR:-}" ]]; then
        [[ -f "$DEMO_JAR" ]] || fail "DEMO_JAR=$DEMO_JAR does not exist"
        echo "$DEMO_JAR"
        return
    fi
    local jar
    jar=$(ls target/fee-service-*.jar 2>/dev/null | grep -v '\.original$' | head -n 1 || true)
    if [[ -z "$jar" ]]; then
        say "Building the app jar (mvn -DskipTests package)…" >&2
        mvn -q -DskipTests package >&2 || fail "mvn package failed"
        jar=$(ls target/fee-service-*.jar | grep -v '\.original$' | head -n 1)
    fi
    echo "$jar"
}

# Bounded polling: runs the command until it succeeds; fails naming the description after the timeout (seconds).
wait_for() {
    local description="$1" timeout="$2"
    shift 2
    local deadline=$((SECONDS + timeout))
    until "$@"; do
        if [[ -n "$APP_PID" ]] && ! kill -0 "$APP_PID" 2>/dev/null; then fail "the app exited while waiting until $description"; fi
        ((SECONDS < deadline)) || fail "timed out after ${timeout}s waiting until $description"
        sleep 0.5
    done
}

registered() { grep -Eq 'Restate endpoint .*: (registered as|already registered|services changed)' "$APP_LOG"; }

api_answers() { curl -s -o /dev/null --max-time 2 "$API/transaction/fee/ping"; }

# start_app <name> <wallet delay>
start_app() {
    APP_LOG="$LOG_DIR/app-$1.log"
    say "\$ WALLET_FAKE_DELAY=$2 java -jar $JAR    ${DIM}(log: $APP_LOG)${OFF}"
    WALLET_FAKE_DELAY="$2" SERVER_PORT="$API_PORT" java -jar "$JAR" ${APP_ARGS[@]+"${APP_ARGS[@]}"} >"$APP_LOG" 2>&1 &
    APP_PID=$!
    wait_for "the app registered its Restate endpoint" 120 registered
    wait_for "the API answers on $API" 60 api_answers
    grep -E 'Started FeeServiceApplication|Restate endpoint listening|RestateRegistrar' "$APP_LOG" | sed 's/^/  /' || true
    ok "app started (pid $APP_PID), Restate endpoint registered"
}

stop_app() {
    [[ -n "$APP_PID" ]] || return 0
    kill -TERM "$APP_PID" 2>/dev/null || true
    wait "$APP_PID" 2>/dev/null || true
    APP_PID=""
}

kill_app() {
    say "\$ kill -9 $APP_PID"
    kill -KILL "$APP_PID"
    wait "$APP_PID" 2>/dev/null || true
    APP_PID=""
}

cleanup() {
    [[ -n "$CALLER_PID" ]] && kill "$CALLER_PID" 2>/dev/null
    stop_app
}
trap cleanup EXIT

# --- requests ---------------------------------------------------------------------------------------------------------

payload() { # payload <transaction_id> <amount> <asset> <type>
    printf '{"transaction_id":"%s","amount":%s,"asset":"%s","asset_type":"FIAT","type":"%s","state":"SETTLED - PENDING FEE","created_at":"2023-08-30 15:42:17.610059"}' \
        "$1" "$2" "$3" "$4"
}

# post <payload> → sets STATUS and BODY
post() {
    say "\$ curl -X POST $API/transaction/fee -d '$1'"
    BODY=$(curl -s --max-time 60 -w '\n%{http_code}' -X POST "$API/transaction/fee" -H 'Content-Type: application/json' -d "$1") ||
        fail "POST /transaction/fee failed"
    STATUS="${BODY##*$'\n'}"
    BODY="${BODY%$'\n'*}"
    say "  → $STATUS $BODY"
}

# get <transaction_id> → sets STATUS and BODY
get() {
    BODY=$(curl -s --max-time 30 -w '\n%{http_code}' "$API/transaction/fee/$1") || fail "GET /transaction/fee/$1 failed"
    STATUS="${BODY##*$'\n'}"
    BODY="${BODY%$'\n'*}"
}

# The fake wallet logs every charge it records; the logs of all app processes together are the wallet's history.
charges() { cat "$LOG_DIR"/app-*.log | grep -c "Fake wallet: charged fee-$1 " || true; }

# Restate's SQL introspection on the admin API, the same data the Restate UI shows.
sql() {
    curl -s --max-time 10 "$ADMIN_URL/query" -H 'Content-Type: application/json' -H 'Accept: application/json' \
        -d "{\"query\":\"$1\"}"
}

rows() { # one row per line
    if command -v jq >/dev/null; then jq -c '.rows[]'; else sed 's/^{"rows":\[//; s/\]}$//; s/},{/}\n{/g'; fi
}

workflow_of() { # the FeeWorkflow invocation of a transaction id
    sql "SELECT id, status, retry_count, last_failure FROM sys_invocation WHERE target_service_name = 'FeeWorkflow' AND target_service_key LIKE '$1:%'"
}

# ======================================================================================================================

mkdir -p "$LOG_DIR"
rm -f "$LOG_DIR"/app-*.log
JAR=$(find_jar)

step "0. Before we start"
curl -sf --max-time 5 -o /dev/null "$ADMIN_URL/health" || fail "Restate's admin API doesn't answer at $ADMIN_URL (set RESTATE_ADMIN_URL)"
ok "Restate answers at $ADMIN_URL"
if curl -s -o /dev/null --max-time 2 "$API"; then fail "something already listens on $API: stop it or set DEMO_API_PORT"; fi
say "Transactions: $TXN1 and $TXN2. App logs: $LOG_DIR/"
pause "start the app"

step "1. Start the app: it starts its Restate endpoint and registers it with Restate itself"
start_app 1-normal 0s
get "$TXN1"
if [[ "$STATUS" != 404 ]]; then
    fail "Restate already has a record for $TXN1 ($STATUS $BODY): run the demo with DEMO_RUN=<something new> or a fresh Restate"
fi
ok "Restate has no record of $TXN1 yet (GET → 404)"
pause "submit $TXN1"

step "2. Submit $TXN1: Mobile Top Up, 1000 USD"
post "$(payload "$TXN1" 1000 USD "Mobile Top Up")"
[[ "$STATUS" == 200 ]] || fail "expected 200"
FIRST="$BODY"
grep "Fake wallet: charged fee-$TXN1 " "$APP_LOG" | sed 's/^/  /'
ok "fee charged: the workflow ran VALIDATE → MAP → CLAIM → CALCULATE → CHARGE → RECORD"
pause "resubmit $TXN1"

step "3. Resubmit the same payload"
post "$(payload "$TXN1" 1000 USD "Mobile Top Up")"
[[ "$STATUS" == 200 && "$BODY" == "$FIRST" ]] || fail "expected the same 200 response as the first time"
ok "same response: the same payload is the same workflow key, so Restate attached to the first run"
[[ "$(charges "$TXN1")" == 1 ]] || fail "expected exactly 1 charge of fee-$TXN1, the logs show $(charges "$TXN1")"
ok "the wallet log shows exactly one charge of fee-$TXN1"
pause "restart the app with a slow wallet"

step "4. Restart the app with a slow wallet (WALLET_FAKE_DELAY=$SLOW_WALLET), submit $TXN2 and kill the app during CHARGE"
stop_app
start_app 2-slow-wallet "$SLOW_WALLET"
pause "submit $TXN2 (the app is killed as soon as the wallet waits)"
REQUEST2="$(payload "$TXN2" 250 EUR "Merchant Payment")"
say "\$ curl -X POST $API/transaction/fee -d '$REQUEST2' &"
curl -s --max-time 120 -o /dev/null -X POST "$API/transaction/fee" -H 'Content-Type: application/json' -d "$REQUEST2" &
CALLER_PID=$!
wait_for "the wallet is waiting to charge fee-$TXN2" 60 grep -q "Fake wallet: waiting .* before charging fee-$TXN2 " "$APP_LOG"
grep "Fake wallet: waiting .* before charging fee-$TXN2 " "$APP_LOG" | sed 's/^/  /'
# No pause here: the kill must come while the wallet waits. Once the old process charged, the new process would charge
# again, because the fake wallet remembers its charges only in memory (a real wallet dedupes by the key).
say "The workflow is in CHARGE: VALIDATE, CLAIM and CALCULATE are already in Restate's journal. Kill the app now:"
kill_app
CALLER_EXIT=0
wait "$CALLER_PID" || CALLER_EXIT=$?
CALLER_PID=""
ok "app killed; the waiting caller lost its connection (curl exit $CALLER_EXIT). Resending the same payload is safe"
[[ "$(charges "$TXN2")" == 0 ]] || fail "the killed app must not have charged fee-$TXN2"
ok "nothing charged yet: the wallet was still waiting"
say "Restate keeps the invocation and retries it with backoff (status, retries, last failure):"
workflow_of "$TXN2" | rows | sed 's/^/  /'
pause "restart the app"

step "5. Restart the app: Restate resumes the workflow on the new process"
start_app 3-restarted 0s
completed() { get "$TXN2"; [[ "$STATUS" == 200 && "$BODY" == *'"status":"COMPLETED"'* ]]; }
wait_for "Restate resumed the workflow of $TXN2 and recorded the fee" 180 completed
say "\$ curl $API/transaction/fee/$TXN2"
say "  → $STATUS $BODY"
ok "Restate retried the invocation: the journaled steps were replayed, CHARGE and RECORD ran on the new process"
grep "Fake wallet: charged fee-$TXN2 " "$APP_LOG" | sed 's/^/  /'
post "$REQUEST2"
[[ "$STATUS" == 200 && "$BODY" == *'"fee":2.80'* ]] || fail "expected the same fee of 2.80 for the resubmission"
ok "resubmitting the payload returns the recorded fee"
[[ "$(charges "$TXN2")" == 1 ]] || fail "expected exactly 1 charge of fee-$TXN2 across all app processes, the logs show $(charges "$TXN2")"
ok "the wallet logs of all three processes show exactly one charge of fee-$TXN2"
pause "show the journal"

step "6. The journal of $TXN2's workflow"
WORKFLOW=$(workflow_of "$TXN2")
INVOCATION=$(grep -o '"id":"inv_[^"]*"' <<<"$WORKFLOW" | head -n 1 | cut -d'"' -f4)
[[ -n "$INVOCATION" ]] || fail "Restate has no FeeWorkflow invocation for $TXN2: $WORKFLOW"
rows <<<"$WORKFLOW" | sed 's/^/  /'
say "Journal of $INVOCATION (Restate SQL, the same data as the UI):"
JOURNAL=$(sql "SELECT index, entry_type, name FROM sys_journal WHERE id = '$INVOCATION' ORDER BY index")
rows <<<"$JOURNAL" | sed 's/^/  /'
for name in validate calculate charge; do
    count=$(grep -o "\"name\":\"$name\"" <<<"$JOURNAL" | wc -l | tr -d ' ')
    [[ "$count" == 1 ]] || fail "expected one '$name' run in the journal, found $count"
done
ok "one 'validate', one 'calculate' and one 'charge' entry: each step is journaled once, whatever the restarts"
say ""
say "${BOLD}Restate UI:${OFF} $ADMIN_URL/ui/ → Invocations → FeeWorkflow, key $TXN2:… → the journal timeline,"
say "the retry attempts and the failure from the kill."
say ""
ok "Demo finished: $TXN1 and $TXN2 were charged once each"
pause "stop the app"
