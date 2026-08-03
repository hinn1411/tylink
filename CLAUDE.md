# CLAUDE.md

Guidance for Claude Code when working in this repository.

## Project

A serverless URL shortener on AWS SAM, Java 21, and AWS Lambda Powertools. Single Maven
module (`functions/`) builds a shaded fat jar that every Lambda function in `template.yaml`
shares as `CodeUri`. `docs/plans/00-overview.md` has the full requirements/decisions context;
`docs/technical_decisions/*.md` are ADRs — check them before revisiting a decision that looks
odd (e.g. why the authorizer never rejects, why `longUrl` validation has no denylist, why
integration tests are a separate Maven profile).

## Commands

```bash
sam build                                    # build all functions; output in .aws-sam/build/
sam build --use-container                    # build inside a container matching Lambda's runtime exactly

sam local invoke ShortenUrlFunction --event events/shortenUrlPublic.authenticated.json
sam local start-api --port 3000              # full HTTP API locally, reads routes from template.yaml

cd functions
mvn test                                     # unit tests only (*Test.java), no AWS/Docker
mvn test -Dtest=ShortenUrlHandlerTest                       # single test class
mvn test -Dtest=ShortenUrlHandlerTest#methodName             # single test method
mvn verify -Pintegration-test                # unit + integration tests (*IT.java); needs Docker,
                                              # spins up DynamoDB Local via Testcontainers

sam deploy                                   # subsequent deploys (samconfig.toml has saved config)
sam logs -n ShortenUrlFunction --stack-name tylink --tail
```

If `mvn verify -Pintegration-test` fails with a Testcontainers/Docker-socket error, see
`docs/technical_decisions/03-testcontainers-ryuk.md`.

Commit messages must start with one of `feature`, `test`, `refactor`, `optimize`, `fix`,
`docs`, `chore` (optionally scoped: `fix(redirect): ...`), enforced by a `commit-msg` pre-commit
hook (`.pre-commit-config.yaml`).

## Architecture

### Package layout

```
com.tylink.auth              — ExtractTokenAuthorizerHandler, CognitoJwtVerifier, AuthUtils
com.tylink.features.<name>   — one package per Lambda-backed feature (shorten, redirect),
                                each with its own Handler and any feature-local model/util classes
com.tylink.model             — ShortUrl, Visibility, UrlStatus — shared across features
com.tylink.repository        — UrlRepository interface + DynamoDbUrlRepository impl + attribute names
com.tylink.util              — RequestUtils (JSON body parsing / API Gateway response helpers),
                                ShortCodeUtils (short-code generation + validation, shared by
                                the shorten and redirect features)
```

Handlers follow a fixed constructor pattern for testability: a public no-arg constructor
wires real AWS clients from environment variables (used by Lambda at runtime), and a
package-private constructor takes the dependency directly (used by tests to inject mocks).
See `ShortenUrlHandler` for the canonical example.

### Request flow and the authorizer's role

Every route sits behind `ExtractTokenAuthorizerFunction`, a single REQUEST-type Lambda
authorizer shared by the whole `HttpApi` (see the comment block in `template.yaml`). It
**never denies a request** — `isAuthorized` is hardcoded `true` — it only verifies an
`Authorization` header if present and passes the verified Cognito `sub` through as `ownerId`
in the authorizer context; an anonymous or invalid-token caller just gets no `ownerId`. This
is the only way to have some routes support both authenticated and anonymous callers on HTTP
API — a native JWT authorizer is all-or-nothing per route and would reject anonymous callers
outright (`docs/technical_decisions/05-custom-jwt-authorizer.md`).

Downstream handlers never see a raw JWT — they read the caller's identity via
`AuthUtils.extractOwnerId(input)`, which pulls `ownerId` out of
`requestContext.authorizer.lambda`. A null return means anonymous.

### Data model — single DynamoDB table, one item type

`UrlTable` has one item shape covering redirect, private-link ownership, and per-user listing;
see `docs/technical_decisions/04-dynamodb-access-patterns.md` for the full rationale.

- `PK=URL#<shortCode>`, `SK=METADATA` — `GetItem` for the hot redirect/decode path. Short code
  is a random Base62 string (`ShortCodeUtils.generate()`), not a counter, to avoid a hot partition.
- `GSI1_PK=USER#<ownerId>`, `GSI1_SK=URL#<createdAt>#<shortCode>` — `Query` for "list a user's
  URLs", chronological by construction. **Only written when the creator was authenticated** —
  anonymous `PUBLIC` creates omit `ownerId`/`GSI1_PK`/`GSI1_SK` entirely, so such a link is
  structurally invisible to the by-user listing (see `DynamoDbUrlRepository.toItem`).
- `visibility` (`PUBLIC`/`PRIVATE`) is a plain attribute, not a key — authorization is a
  runtime check (does caller's `ownerId` match the item's?), not something key design can
  express. A mismatch must return **404, never 403**, so a probing caller can't distinguish
  "not yours" from "doesn't exist".
- `PRIVATE` visibility requires an authenticated creator at write time (there must be a real
  `ownerId` to check against later); `PUBLIC` does not. `ShortenUrlHandler` enforces this
  before ever touching the repository.

### Implementation status

`ShortenUrlHandler` is fully wired (validates input, generates code, writes via
`UrlRepository`). `RedirectUrlHandler` is fully wired too (validates the short code shape,
looks up the item, enforces PRIVATE ownership, returns 404/410/307). Don't assume update,
delete, or listing are implemented; check the handler before relying on any of them.

### Validation and response conventions

- `LongUrlValidator.validate()` does strict `java.net.URI` parsing + an `{http, https}` scheme
  allowlist rather than a denylist — this incidentally blocks XSS/header-injection payloads as
  a side effect of RFC 3986 strictness, not via pattern matching. See
  `docs/technical_decisions/07-longurl-validation.md` before adding denylist-style checks.
- `RequestUtils.parseBody`/`jsonResponse` are the only place handlers touch Jackson or build an
  `APIGatewayV2HTTPResponse` — keep new handlers consistent with this rather than
  hand-rolling JSON.
- `ShortCodeUtils.generate()`/`isValid()` deliberately live in one class with the alphabet and
  length kept `private` — they used to be two classes (one per feature) sharing `public`
  constants, which leaked implementation detail across package boundaries for no reason since
  only these two methods ever needed them.

### Logging

`@Logging` (Powertools) annotations require the `aspectj-maven-plugin` compile-time weaving
configured in `functions/pom.xml` — a plain `javac` won't activate them. Runtime log level is
controlled by `POWERTOOLS_LOG_LEVEL` in `template.yaml`, not `log4j2.xml` (which is a fallback
only); see `docs/technical_decisions/02-app-log-configuration.md`.

### Testing

`*Test.java` = unit tests (JUnit 5 + Mockito, no AWS/Docker — mock `UrlRepository`, not the
DynamoDB SDK directly, per N4 in `docs/plans/00-overview.md`). `*IT.java` = integration tests
against real DynamoDB Local via Testcontainers; not run by plain `mvn test`/`sam build`, opt in
via `-Pintegration-test` (`docs/technical_decisions/06-integration-tests-as-profile.md`).

## Conventions

### Coding standards
- Use `Objects.isNull()` for null check instead of `==` operator
- Use `Optional` to check for nested null check

### Document generation
- Documents should precise, straight to the point
- No filler, no vague meaning, no redundant words. It is for engineers.
