# Decision: Disable Testcontainers Ryuk for `mvn verify`

## Context

`mvn verify` runs the integration tests (`*IT.java`), which spin up a real
DynamoDB Local container via Testcontainers. On some Docker Desktop setups,
this fails with `Could not start container` / `404 No such container`.

## Root cause

Testcontainers' cleanup sidecar ("Ryuk") needs a real `/var/run/docker.sock`
to bind-mount into itself. Docker Desktop configurations that only expose the
proxy sockets under `~/.docker/desktop/` (not a real `/var/run/docker.sock`)
don't provide this, so Ryuk fails to start and takes the whole container
lifecycle down with it.

## Decision

- Ryuk is disabled by default via Failsafe plugin config in `functions/pom.xml`.
- If your environment does have a real `/var/run/docker.sock` — e.g. Docker
  Desktop with Settings → Advanced → "Allow the default Docker socket to be
  used" enabled, or native Linux Docker Engine — you can safely remove that
  config block to get Ryuk's crash-safety (auto-cleanup of orphaned
  containers) back.
