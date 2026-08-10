# tylink

A serverless URL shortener built with AWS SAM, Java 21, and AWS Lambda Powertools.

- `functions/` — Maven module with all Lambda handler source (`src/main/java`) and unit tests (`src/test/java`).
- `events/` — sample invocation payloads for local testing (`shortenUrlPublic.authenticated.json`, `shortenPrivateUrl.json`, `shortenPublicUrl.json`, `shortenPrivateUrl.invalidToken.json`, `redirectUrl.json`).
- `template.yaml` — SAM template defining the application's AWS resources (Lambda functions, DynamoDB table, HTTP API).
- `samconfig.toml` — saved CLI defaults (stack name `tylink`, `CAPABILITY_IAM`, etc.) so most commands below need no extra flags.
- `docs/` — project plans (`plans/`), technical-decision records (`technical_decisions/`), and learning notes (`learning/`). Start at `docs/plans/00-overview.md` for full project context.

## Prerequisites

* [SAM CLI](https://docs.aws.amazon.com/serverless-application-model/latest/developerguide/serverless-sam-cli-install.html)
* [Java 21 (Corretto)](https://docs.aws.amazon.com/corretto/latest/corretto-21-ug/downloads-list.html)
* [Maven](https://maven.apache.org/install.html)
* [Docker](https://docs.docker.com/get-docker/) — required for `sam local ...` and integration tests
* AWS credentials configured (`aws configure`) — required for deploy/remote commands
* [pre-commit](https://pre-commit.com/) — required for the git hooks below (`pip install pre-commit`)

## Pre-commit hooks

Config lives in `.pre-commit-config.yaml`. After cloning, install both hook stages once:

```bash
pip install pre-commit         # if not already available
pre-commit install                        # file-hygiene checks, runs on every commit
pre-commit install --hook-type commit-msg  # commit message format check
```

File-hygiene checks (trailing whitespace, missing final newline, YAML/JSON/TOML
validity, merge-conflict markers, large files, private keys) run on every commit
and some auto-fix in place — if a commit fails because a file was modified, just
`git add` the fix and commit again.

Commit messages must start with one of these types, optionally with a scope,
followed by `: ` and a description:

```
feature: add login endpoint
fix(redirect): handle malformed short codes
docs: update README
```

Allowed types: `feature`, `test`, `refactor`, `optimize`, `fix`, `docs`, `chore`.

## Build

```bash
sam build
```

Output goes to `.aws-sam/build/`. Use `sam build --use-container` to build inside
a Docker container matching the Lambda execution environment exactly (e.g. if
"it builds locally but fails on Lambda").

## Run & invoke locally

```bash
# Invoke a single function with a sample event (no HTTP layer involved)
sam local invoke ShortenUrlFunction --event events/shortenUrlPublic.authenticated.json

# Start the full HTTP API locally on port 3000 (reads routes from
# each function's `Events` block in template.yaml)
sam local start-api --port 3000

# In another shell, call the locally emulated route:
curl -i -X POST http://localhost:3000/v1/urls \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: $(uuidgen)" \
  -d '{}'
```

## Tests

Tests live in `functions/src/test/java`: `*Test.java` classes are unit tests, `*IT.java`
classes are integration tests.

```bash
cd functions

mvn test                            # unit tests — pure Java, no AWS/Docker (JUnit5)
mvn verify -Pintegration-test       # unit + integration tests — needs Docker running
                                     # (spins up DynamoDB Local via Testcontainers)
```

If `mvn verify -Pintegration-test` fails with a Testcontainers/Docker socket error, see
`docs/technical_decisions/03-testcontainers-ryuk.md`. Integration tests are opt-in (see
`docs/technical_decisions/06-integration-tests-as-profile.md`) since `sam build`'s containerized
mode can't reach Docker for them.

## Deploy to AWS

```bash
sam deploy --guided   # first-ever deploy; saves answers to samconfig.toml
sam deploy            # subsequent deploys reuse samconfig.toml
```

Google login via Cognito needs one parameter `samconfig.toml` can't default (it's git-tracked): the
Google OAuth client secret. `scripts/deploy.sh` pulls it fresh from SSM on every deploy instead of
storing it anywhere, and passes any extra args straight through to `sam deploy`:

```bash
./scripts/deploy.sh
./scripts/deploy.sh --guided
```

`AWS::Cognito::UserPoolIdentityProvider` isn't on CloudFormation's `ssm-secure` dynamic-reference
allowlist, so it can't self-resolve the secret like `client_id` does — see the `GoogleClientSecret`
parameter's description in `template.yaml` for the full reason.

## Invoke the deployed function

```bash
sam remote invoke ShortenUrlFunction --stack-name tylink --event-file events/shortenUrlPublic.authenticated.json
```

## Logs

```bash
sam logs -n ShortenUrlFunction --stack-name tylink --tail
```

## Cleanup

```bash
sam delete --stack-name tylink --no-prompts
```

## Resources

* [AWS SAM developer guide](https://docs.aws.amazon.com/serverless-application-model/latest/developerguide/what-is-sam.html)
* [AWS Lambda Powertools for Java](https://docs.powertools.aws.dev/lambda/java/)
