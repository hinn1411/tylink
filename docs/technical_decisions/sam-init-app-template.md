# Decision: `sam init` App Template Choice (Java 21)

## Context

`sam init` scaffolds a starting project from one of several AWS Quick Start
application templates. For the Java 21 / Maven combination used in this
project (see `plans/01-phase1-build.md`), two candidates were considered:
**Hello World Example With Powertools** and **Serverless API**.

## What is the Powertools template?

"Hello World Example With Powertools for AWS Lambda" is a `sam init` Quick
Start template that scaffolds a single Lambda function with
[Powertools for AWS Lambda (Java)](https://github.com/aws-powertools/powertools-lambda-java)
already wired into the Maven build:

- `pom.xml` pre-populated with the `powertools-tracing`, `powertools-logging`
  (log4j2 backend), and `powertools-metrics` dependencies.
- The `aspectj-maven-plugin` + `aspectjrt`/`aspectjtools` configured for
  compile-time weaving — required because most Powertools utilities
  (`@Logging`, `@Tracing`, `@Metrics`, `@Idempotent`) work via AspectJ
  annotations, not plain method calls.
- A `log4j2.xml` for structured JSON logging.
- One handler class already annotated with `@Logging`/`@Tracing` as a
  working example to copy for additional functions.
- A single function behind one REST API (`Api` event) route.

Powertools itself is AWS's official toolkit implementing serverless best
practices as reusable utilities so they don't have to be hand-rolled per
Lambda: structured logging, X-Ray tracing subsegments, EMF-based custom
CloudWatch metrics, and an Idempotency utility (DynamoDB-backed conditional
writes, directly relevant to requirement F4 — idempotent create).

## What is the Serverless API template?

"Serverless API" is a `sam init` Quick Start template that scaffolds a
small CRUD-shaped API: multiple Lambda functions, each behind its own
route/method (typically get/post/put/delete against one resource), plus a
persistence layer (a DynamoDB table and an accompanying data-access class).
It has no Powertools dependencies or AspectJ wiring — logging/tracing/
metrics would need to be added manually.

## Comparison

| | Hello World Example **With Powertools** | Serverless API |
|---|---|---|
| Function count generated | 1 | Multiple (CRUD-shaped) |
| Structural fit to this project's 5-Lambda plan | Low — one function is copied 4 more times | Higher — already multiple functions behind multiple routes |
| Powertools pre-wired (logging/tracing/metrics deps + AspectJ plugin) | Yes | No |
| Persistence layer scaffolded | No | Yes — DynamoDB table + a repository-ish class |
| API Gateway type generated | REST API (`Api` event) | REST API (`Api` event) |
| Matches this project's F4 idempotency need out of the box | No (Idempotency is a separate Powertools module, added either way) | No |
| Net setup cost for this project | Low — add 4 more function blocks + swap `Api` → `HttpApi` events | Medium — its DynamoDB/handler conventions don't match the single-table PK/SK design or `UrlRepository` interface (N4), so they get un-picked on top of still adding Powertools manually |

## Decision

Use the **Hello World Example With Powertools** template.

Both templates generate a `template.yaml` that is mostly discarded — neither
includes Cognito, the single-table GSI design, HTTP API + JWT authorizer,
CloudFront, or KMS, all of which are hand-written regardless (see
`04-cicd-and-iac.md`). The real value of `sam init` here is the
`pom.xml`/AspectJ wiring and one working handler-to-jar build to clone for
the remaining functions. The Powertools template provides that directly;
the Serverless API template's multi-function/DynamoDB scaffolding
conventions don't align closely enough with this project's custom
single-table + repository-interface design (N4) to offset the missing
Powertools setup.
