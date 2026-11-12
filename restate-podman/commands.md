## create container

```bash
podman run --name restate_dev  \
-p 8080:8080 -p 9070:9070 -p 9071:9071 \
docker.restate.dev/restatedev/restate:latest
```

## check is running

```bash
curl -v http://127.0.0.1:9070/health
```

or goto UI http://localhost:9070/ui/overview

