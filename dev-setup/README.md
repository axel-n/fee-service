# Dev environment

Dev Restate (UI: <http://localhost:9070>) plus a dev container with JDK 25, Maven and nested podman for Testcontainers.
Needs podman-compose.

```bash
podman compose up -d --build
podman compose exec dev bash
```
## for run integration tests on host machine

### for podman users
```bash
systemctl --user enable --now podman.socket
curl -s --unix-socket $XDG_RUNTIME_DIR/podman/podman.sock http://localhost/_ping # should print OK

touch ~/.testcontainers.properties
echo "docker.host=unix:///run/user/1000/podman/podman.sock" >>  ~/.testcontainers.properties
echo "ryuk.container.privileged=true" >>  ~/.testcontainers.properties

echo "export DOCKER_HOST=unix://$XDG_RUNTIME_DIR/podman/podman.sock" >> ~/.bashrc
```