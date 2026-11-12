#!/bin/bash
# Smoke test of the nested podman that Testcontainers uses inside the dev container. Run it in the dev container:
#   dev-setup/check-nested-podman.sh
# It drives a Restate container through the Docker-compatible API on $DOCKER_HOST, the way Testcontainers does, and
# checks what the integration tests need: pull, random published ports on the bridge network, container-to-container
# traffic through an extra host (how exposeHostPorts' host.testcontainers.internal works), freeze/thaw, stop/start
# and removal. It removes everything it starts. Exit code 0 = every required check passed.
set -uo pipefail

RESTATE_IMAGE=${RESTATE_IMAGE:-docker.io/restatedev/restate:latest}
CLIENT_IMAGE=docker.io/library/alpine:3.22
NAME=nested-podman-check-$$
SOCK=${DOCKER_HOST#unix://}
failures=0

pass() { echo "PASS  $*"; }
fail() { echo "FAIL  $*"; failures=$((failures + 1)); }
info() { echo "INFO  $*"; }
cleanup() { podman rm -f -t 0 "$NAME" >/dev/null 2>&1; }
trap cleanup EXIT

# call METHOD PATH [JSON]: Docker API request; sets $status and $body
call() {
  local args=(-s --max-time 300 --unix-socket "$SOCK" -X "$1" -w '\n%{http_code}') out
  [ $# -ge 3 ] && args+=(-H 'Content-Type: application/json' -d "$3")
  out=$(curl "${args[@]}" "http://d/v1.44$2")
  status=${out##*$'\n'}; body=${out%$'\n'*}
}
inspect() { call GET "/containers/$NAME/json"; jq -r "$1" <<<"$body"; }
admin_port() { inspect '.NetworkSettings.Ports["9070/tcp"][0].HostPort'; }
health() { curl -s -o /dev/null -w '%{http_code}' --max-time 3 "http://localhost:$(admin_port)/health"; }
wait_healthy() { for _ in $(seq 1 60); do [ "$(health)" = 200 ] && return 0; sleep 0.5; done; return 1; }
api_ok() { call POST "/containers/$NAME/$1"; [ "$status" = 204 ]; }

call GET /_ping
[ "$body" = OK ] && pass "Docker API at $DOCKER_HOST" \
  || { fail "no Docker API at $DOCKER_HOST (entrypoint.sh starts it, log: /tmp/podman-service.log)"; exit 1; }

podman pull -q "$RESTATE_IMAGE" >/dev/null && podman pull -q "$CLIENT_IMAGE" >/dev/null && pass "pull $RESTATE_IMAGE" \
  || { fail "pull (compose.yaml needs cap_add SYS_CHROOT)"; exit 1; }

# created like Testcontainers creates it: default network, every exposed port bound to a random host port
call POST "/containers/create?name=$NAME" \
  '{"Image":"'"$RESTATE_IMAGE"'","ExposedPorts":{"8080/tcp":{},"9070/tcp":{}},
    "HostConfig":{"PortBindings":{"8080/tcp":[{"HostPort":""}],"9070/tcp":[{"HostPort":""}]}}}'
[ "$status" = 201 ] || { fail "create: $body"; exit 1; }
api_ok start && wait_healthy \
  && pass "start, random ports (admin on localhost:$(admin_port)), network $(inspect '.HostConfig.NetworkMode')" \
  || { fail "start: $body"; podman logs "$NAME" 2>&1 | tail -5; exit 1; }

ip=$(inspect '.NetworkSettings.Networks | to_entries[0].value.IPAddress')
podman run --rm --network podman --add-host "restate.check:$ip" "$CLIENT_IMAGE" \
  wget -q -O /dev/null -T 5 http://restate.check:9070/health \
  && pass "container-to-container on the bridge through an extra host ($ip)" \
  || fail "container-to-container (compose.yaml needs security_opt unmask=/proc/sys)"

api_ok 'kill?signal=SIGSTOP' && [ "$(health)" = 000 ] && api_ok 'kill?signal=SIGCONT' && wait_healthy \
  && pass "freeze/thaw (SIGSTOP: requests hang; SIGCONT: healthy again)" || fail "freeze/thaw: $status $body"

if api_ok pause; then
  [ "$(health)" = 000 ] && api_ok unpause && wait_healthy && pass "pause/unpause" || fail "pause/unpause: $status $body"
else
  info "pause unsupported here: $(jq -r '.message // .' <<<"$body" 2>/dev/null)"
fi

api_ok 'stop?t=5' && api_ok start && wait_healthy \
  && pass "stop/start (admin now on localhost:$(admin_port))" || fail "stop/start: $status $body"

call DELETE "/containers/$NAME?force=true"
[ "$status" = 204 ] && pass "remove" || fail "remove: $status $body"

echo; [ "$failures" = 0 ] && echo "nested podman: OK" || echo "nested podman: $failures check(s) failed"
exit $((failures > 0))
