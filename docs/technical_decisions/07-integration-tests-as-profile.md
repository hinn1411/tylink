# Decision: Gate Integration Tests Behind a Maven Profile

## Context

`sam build` (plain and `--use-container`) runs `mvn clean install`, which by
default executes through the `verify` phase — including `UrlTableIT`, a
Testcontainers-based integration test that needs a Docker daemon.

## Root cause

`sam build --use-container` runs Maven inside a nested build container
(`public.ecr.aws/sam/build-java21`) with no Docker socket mounted in. Testcontainers
can't reach a daemon from there, so `UrlTableIT` fails with
`Could not find a valid Docker environment`, and takes the whole build down.
Plain `sam build` doesn't hit this because the host's Docker socket is directly
reachable.

## Decision

- `maven-failsafe-plugin`'s execution moved out of the default `<build><plugins>`
  into an opt-in `integration-test` profile in `functions/pom.xml`.
- `mvn clean install` / `sam build` (either mode) now run only unit tests
  (`*Test.java`) — no Docker dependency.
- Run integration tests explicitly: `mvn verify -Pintegration-test` (needs
  Docker; see `03-testcontainers-ryuk.md` for the Ryuk gotcha on some Docker
  Desktop setups).
- CI must add `mvn verify -Pintegration-test` as its own step — it is no
  longer exercised by a plain build/install.
