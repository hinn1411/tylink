# CLAUDE.md

Guidance for Claude Code when working in this repository.

## Project

A serverless URL shortener on AWS SAM, Java 21, and AWS Lambda Powertools. Single Maven
module (`functions/`) builds a shaded fat jar that every Lambda function in `template.yaml`
shares as `CodeUri`. `docs/plans/00-overview.md` has the full requirements/decisions context;
`docs/technical_decisions/*.md` are ADRs — check them before revisiting a decision that looks
odd (e.g. why the authorizer never rejects, why `longUrl` validation has no denylist, why
CloudFront caching is gated on visibility). Prerequisites and full setup steps are in
`README.md`.

## Commands

```bash
sam build                                    # build all functions; output in .aws-sam/build/
sam build --use-container                    # build inside a container matching Lambda's runtime exactly

sam local invoke ShortenUrlFunction --event events/shorten/shortenUrlPublic.authenticated.json
sam local start-api --port 3000              # full HTTP API locally, reads routes from template.yaml

cd functions
mvn test                                     # unit tests only (*Test.java), no AWS/Docker
mvn test -Dtest=ShortenUrlHandlerTest                       # single test class
mvn test -Dtest=ShortenUrlHandlerTest#methodName             # single test method
mvn verify -Pintegration-test                # unit + integration tests (*IT.java); needs Docker,
                                              # spins up DynamoDB Local via Testcontainers

sam deploy                                   # subsequent deploys (samconfig.toml has saved config)
./scripts/deploy/deploy.sh                   # wraps `sam deploy`; pulls GoogleClientSecret fresh from SSM
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
com.tylink.features.<name>   — one package per Lambda-backed feature (shorten, redirect, list,
                                update, delete, login), each with its own Handler and any
                                feature-local models/utils classes
com.tylink.models            — ShortUrl, Visibility, UrlStatus — shared across features
com.tylink.repository        — UrlRepository interface + UrlRepositoryException (the contract);
                                com.tylink.repository.dynamodb has the DynamoDB impl (DynamoDbUrlRepository,
                                ShortUrlAttributes, package-private CursorCodec); com.tylink.repository.pagination
                                has UrlPage + InvalidCursorException
com.tylink.utils             — RequestUtils (JSON body parsing / API Gateway response helpers),
                                ShortCodeUtils (short-code generation + validation, shared by
                                the shorten and redirect features), TimestampUtils (fixed-width
                                nanosecond timestamps, sortable as plain strings)
```

Handlers follow a fixed constructor pattern for testability: a public no-arg constructor
wires real AWS clients from environment variables (used by Lambda at runtime), and a
package-private constructor takes the dependency directly (used by tests to inject mocks).
See `ShortenUrlHandler` for the canonical example.

### Request flow and the authorizer's role

Two `HttpApi` authorizers, chosen per route by whether anonymous callers must be supported:
`ExtractTokenAuthorizerFunction` (custom, never denies) for routes needing both anonymous and
authenticated callers (`create`, `redirect`), and `NativeJwtAuthorizer` (native JWT) for routes
that always require a caller (`list`, `update`, `delete`). Handlers never see a raw JWT — they
read identity via `AuthUtils.extractOwnerId(input)` (null means anonymous). Login
(`POST /v1/auth/login`) is public and separate from both — see
`docs/technical_decisions/06-custom-jwt-authorizer.md`.

### Data model — single DynamoDB table, one item type

`UrlTable` has one item shape covering redirect, ownership checks, and per-user listing:

- `PK=URL#<shortCode>`, `SK=METADATA` — primary key, `GetItem` for redirect/decode.
- `GSI1_PK=USER#<ownerId>`, `GSI1_SK=URL#<createdAt>#<shortCode>` — `Query` for listing a user's
  URLs; present only when the creator was authenticated.
- `visibility` (`PUBLIC`/`PRIVATE`) and `status` (`ACTIVE`/`DELETED`) are plain attributes, not
  keys.

See `docs/technical_decisions/05-dynamodb-access-patterns.md` and
`09-list-urls-pagination-tradeoffs.md`.

### Edge caching & throttling

CloudFront fronts `HttpApi`; only `/v1/urls/*` is cacheable, and `RedirectUrlHandler` sets the
`Cache-Control` header only for `visibility == PUBLIC` — never touch that condition without
reading `docs/technical_decisions/15-cloudfront-edge-caching.md` first (a `PRIVATE` URL cached
by mistake leaks through the shared edge cache). `HttpApi` also has per-route `RouteSettings`
throttling limits, sized off origin traffic — see
`docs/technical_decisions/16-throttling-backpressure.md`.

### Implementation status

`ShortenUrlHandler`, `RedirectUrlHandler`, `ListUrlsHandler`, `DeleteUrlHandler`,
`UpdateUrlHandler`, and `LoginHandler` are all fully wired.

### Validation and response conventions

- `LongUrlValidator.validate()` — strict URI validation; see
  `docs/technical_decisions/08-longurl-validation.md` before adding denylist-style checks.
- `RequestUtils.parseBody`/`jsonResponse` — the only place handlers touch Jackson or build an
  `APIGatewayV2HTTPResponse`; keep new handlers consistent with this rather than hand-rolling JSON.
- `ShortCodeUtils.generate()`/`isValid()` — code generation + validation, shared by shorten and
  redirect.

### Logging

`@Logging` (Powertools) requires `aspectj-maven-plugin` compile-time weaving
(`functions/pom.xml`) — plain `javac` won't activate it. Log level is set via
`POWERTOOLS_LOG_LEVEL` in `template.yaml`, not `log4j2.xml`. See
`docs/technical_decisions/02-app-log-configuration.md`.

### Testing

`*Test.java` = unit tests (JUnit 5 + Mockito, no AWS/Docker — mock `UrlRepository`, not the
DynamoDB SDK directly, per N4 in `docs/plans/00-overview.md`). `*IT.java` = integration tests
against real DynamoDB Local via Testcontainers; not run by plain `mvn test`/`sam build`, opt in
via `-Pintegration-test` (`docs/technical_decisions/07-integration-tests-as-profile.md`).

## Conventions

### Coding standards
- Use `Objects.isNull()` for null check instead of `==` operator
- Use `Optional` to check for nested null check

### Document generation
- Documents should precise, straight to the point
- No filler, no vague meaning, no redundant words. It is for engineers.

### Chat responses
- Keep replies short: 1-3 sentences stating what changed and what's next, nothing else.
- No preamble ("I'll now...", "Let me..."), no restating the request, no step-by-step narration
  of tool calls already visible, no trailing recap of work already shown in the output.
- Skip a section unless it's TBD or wrong; don't re-summarize sections that didn't change.
