# Decision: Log Level Configuration (`log4j2.xml` vs `POWERTOOLS_LOG_LEVEL`)

## Context

`log4j2.xml` sets a static `Root` logger level (`info`). `template.yaml` also
sets `POWERTOOLS_LOG_LEVEL` as a Lambda env var, via
[Powertools for AWS Lambda (Java)](https://github.com/aws-powertools/powertools-lambda-java)
(`powertools-logging-log4j`).

## How it actually works

Powertools reads `POWERTOOLS_LOG_LEVEL` itself at runtime and overrides the
Log4j2 level programmatically — `log4j2.xml` is not involved. Priority order:

1. `AWS_LAMBDA_LOG_LEVEL` (Lambda Advanced Logging Controls), if set
2. `POWERTOOLS_LOG_LEVEL` env var
3. Static level in `log4j2.xml` — fallback only, used when neither env var is set

## Decision

- Keep `log4j2.xml`'s `Root level` static (`info`) as the fallback. Do **not**
  make it read the env var itself (e.g. `${env:POWERTOOLS_LOG_LEVEL}`) —
  redundant, since Powertools already overrides it.
- Control the real runtime level via `POWERTOOLS_LOG_LEVEL` in
  `template.yaml` (`Globals.Function.Environment.Variables`).
- Only `TRACE`/`DEBUG`/`INFO`/`WARN`/`ERROR` are valid. There is no `OFF`:
  unsupported values are silently remapped by Powertools instead of raising
  an error (e.g. `OFF` silently became `INFO`). Use `ERROR` for minimal output.
- Changing the env var needs no code rebuild, but does need a fresh Lambda
  cold start (or, for `sam local invoke`, a fresh `sam build` — it runs
  against `.aws-sam/build/template.yaml`, so edits to the root `template.yaml`
  are invisible until you rebuild).
