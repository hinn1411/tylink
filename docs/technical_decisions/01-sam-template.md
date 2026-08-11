# Decision: `sam init` App Template Choice (Java 21)

## Context

Choosing a `sam init` Quick Start template for the Java 21 / Maven stack:
**Hello World Example With Powertools** vs. **Serverless API**.

## Options

**Hello World Example With Powertools** — one Lambda function, with
[Powertools for AWS Lambda (Java)](https://github.com/aws-powertools/powertools-lambda-java)
pre-wired: `pom.xml` deps (`powertools-tracing`/`logging`/`metrics`),
`aspectj-maven-plugin` compile-time weaving (required for `@Logging`/
`@Tracing`/`@Metrics`/`@Idempotent` annotations), a `log4j2.xml` for
structured JSON logs, and one annotated handler to copy for other functions.

**Serverless API** — multiple Lambda functions (CRUD-shaped, one per
route/method) plus a scaffolded DynamoDB table and data-access class. No
Powertools/AspectJ wiring.

| | Powertools template | Serverless API |
|---|---|---|
| Functions generated | 1 | Multiple |
| Powertools pre-wired | Yes | No |
| Persistence scaffolded | No | Yes (DynamoDB + repo class) |
| Fits this project's single-table + `UrlRepository` design (N4) | N/A | No — gets un-picked anyway |

## Decision

Use **Hello World Example With Powertools**.

Both templates' generated `template.yaml` gets mostly discarded — Cognito,
the single-table GSI design, HTTP API + JWT authorizer, and CloudFront are
all hand-written regardless (see `04-cicd.md`). What actually matters is
the `pom.xml`/AspectJ wiring and one working handler-to-jar build to clone
for the remaining functions — the Powertools template provides that
directly, while Serverless API's DynamoDB/handler conventions don't match
this project's design closely enough to be worth the tradeoff.
