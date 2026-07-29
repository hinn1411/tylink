# Decision: Log Level Configuration (`log4j2.xml` vs `POWERTOOLS_LOG_LEVEL`)

## Context

`functions/src/main/resources/log4j2.xml` defines the `Root` logger level
statically (`level="info"`). The project also sets `POWERTOOLS_LOG_LEVEL` as
a Lambda environment variable in `template.yaml` (`Globals.Function.Environment`),
via [Powertools for AWS Lambda (Java)](https://github.com/aws-powertools/powertools-lambda-java)
(`powertools-logging-log4j`). The question was whether `log4j2.xml` needs to
read that environment variable itself (e.g. via Log4j2's own
`${env:POWERTOOLS_LOG_LEVEL:-INFO}` lookup syntax) to make the level
configurable without a rebuild.

## What we tried first (and reverted)

We initially changed the `Root` level in `log4j2.xml` to
`${env:POWERTOOLS_LOG_LEVEL:-INFO}`, assuming Log4j2's own environment-lookup
mechanism was what wired the env var to the logger. This turned out to be
unnecessary.

## How Powertools actually handles this

Per the [Powertools Java logging docs](https://github.com/aws-powertools/powertools-lambda-java/blob/main/docs/core/logging.md),
Powertools **already reads `POWERTOOLS_LOG_LEVEL` itself at runtime** and
overrides the Log4j2 level programmatically — independent of anything
written in `log4j2.xml`. Priority order:

1. `AWS_LAMBDA_LOG_LEVEL` (Lambda Advanced Logging Controls / ALC), if set
2. `POWERTOOLS_LOG_LEVEL` env var
3. The static level in `log4j2.xml`/`logback.xml` (used only as the fallback
   if neither env var is set)

Consequences:

- The `log4j2.xml` level is **only a fallback default** (e.g. for local unit
  tests run outside Lambda, or if the env var is unset). It has no effect
  in deployed/local-invoked functions as long as `POWERTOOLS_LOG_LEVEL` is set.
- Making `log4j2.xml` itself read the env var (via `${env:...}`) is
  redundant — Powertools' own override already achieves the "change level
  without rebuilding code" goal. We reverted `log4j2.xml` back to a plain
  static `level="info"`.
- **Only `TRACE`, `DEBUG`, `INFO`, `WARN`, `ERROR` are recognized values**
  for `POWERTOOLS_LOG_LEVEL`. Unsupported values (we tried `OFF`, expecting
  it to suppress all logging) are silently mapped to `ERROR` or `INFO` by
  Powertools rather than raising an error — this caused a real bug where
  setting `POWERTOOLS_LOG_LEVEL: OFF` in `template.yaml` kept producing
  `INFO`-level logs with no warning. Use `ERROR` (least verbose supported
  level) instead of `OFF` to minimize log output.

## Two build-artifact gotcha (unrelated to Powertools, but hit during the same debugging session)

`sam local invoke` (no `-t` flag) uses `.aws-sam/build/template.yaml`, not
the root `template.yaml`, once a build exists. Editing `template.yaml` and
re-invoking without running `sam build` first re-tests the **stale** build
artifact's env vars. Always `sam build` after editing `template.yaml` before
`sam local invoke`.

## Decision

- Keep `log4j2.xml`'s `Root level` as a static value (`info`) — it is only
  a fallback, not the live control point.
- Control the actual runtime log level via `POWERTOOLS_LOG_LEVEL` in
  `template.yaml` (`Globals.Function.Environment.Variables`), using only
  `TRACE`/`DEBUG`/`INFO`/`WARN`/`ERROR`. Use `ERROR` where minimal output is
  desired (Powertools has no `OFF`/silent option).
- Changing `POWERTOOLS_LOG_LEVEL` requires no code rebuild — just a
  `template.yaml`/Lambda config update — but a Lambda cold start (or, for
  `sam local invoke`, a fresh `sam build`) is needed to pick up the new
  value.
