#!/bin/bash
# Starts the nested podman's Docker-compatible API socket (used by Testcontainers through DOCKER_HOST),
# then runs the container's command.
set -e

install -d -m 0700 "$XDG_RUNTIME_DIR" "$XDG_RUNTIME_DIR/podman"
podman system service --time=0 "$DOCKER_HOST" > /tmp/podman-service.log 2>&1 &

exec "$@"
