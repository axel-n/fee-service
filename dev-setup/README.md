# Dev environment

Dev Restate (UI: <http://localhost:9070>) plus a dev container with JDK 25, Maven and nested podman for Testcontainers.
Needs podman-compose.

```bash
podman compose up -d --build
podman compose exec dev bash
```
