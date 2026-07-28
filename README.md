# tylink

A serverless URL shortener built with AWS SAM, Java 21, and AWS Lambda Powertools.

- `functions/` — Maven module with all Lambda handler source (`src/main/java`) and unit tests (`src/test/java`).
- `events/` — sample invocation payloads for local testing (`createUrl.json`, `decodeUrl.json`).
- `template.yaml` — SAM template defining the application's AWS resources (Lambda functions, DynamoDB table, HTTP API).
- `samconfig.toml` — saved CLI defaults (stack name `tylink`, `CAPABILITY_IAM`, etc.) so most commands below need no extra flags.

## Prerequisites

* [SAM CLI](https://docs.aws.amazon.com/serverless-application-model/latest/developerguide/serverless-sam-cli-install.html)
* [Java 21 (Corretto)](https://docs.aws.amazon.com/corretto/latest/corretto-21-ug/downloads-list.html)
* [Maven](https://maven.apache.org/install.html)
* [Docker](https://docs.docker.com/get-docker/) — required for `sam build --use-container` and `sam local ...`
* AWS credentials configured (`aws configure`) — required for deploy/remote commands

## Build

```bash
# Build using the local Maven/JDK toolchain (fast, default)
sam build

# Build inside a Docker container that matches the Lambda execution
# environment exactly — use this if "it builds locally but fails on Lambda"
sam build --use-container

# Force a clean rebuild, ignoring the build cache
sam build --no-cached
```

Output goes to `.aws-sam/build/`.

## Run & invoke locally

```bash
# Invoke a single function with a sample event (no HTTP layer involved)
sam local invoke CreateUrlFunction --event events/createUrl.json
sam local invoke DecodeUrlFunction --event events/decodeUrl.json

# Same, but pause and wait for a debugger to attach on port 5858
sam local invoke CreateUrlFunction --event events/createUrl.json --debug-port 5858

# Start the full HTTP API locally on port 3000 (reads routes from
# each function's `Events` block in template.yaml)
sam local start-api --port 3000 --warm-containers EAGER

# In another shell, call the locally emulated route:
curl -i -X POST http://localhost:3000/v1/urls \
  -H "Content-Type: application/json" \
  -d '{}'
```

## Tests

Tests live in `functions/src/test/java`: `*Test.java` classes are unit tests, `*IT.java`
classes are integration tests.

```bash
cd functions

# Unit tests — pure Java, no AWS/Docker involved (JUnit5)
mvn test

# Integration tests — spins up a real DynamoDB Local container via
# Testcontainers and exercises the UrlTable PK/SK schema against it.
# Requires Docker running. Runs unit tests first, then integration tests.
mvn verify
```

`NOTE`: Testcontainers' cleanup sidecar ("Ryuk") is disabled by default via
`functions/pom.xml` (Failsafe plugin config). Ryuk needs a real `/var/run/docker.sock`
to bind-mount into itself, which Docker Desktop setups without that path (only the
proxy sockets under `~/.docker/desktop/`) don't provide — without disabling it,
`mvn verify` fails with a `Could not start container` / `404 No such container` error.
If your environment does have a real `/var/run/docker.sock` (e.g. Docker Desktop with
Settings → Advanced → "Allow the default Docker socket to be used" enabled, or native
Linux Docker Engine), you can safely remove that config block to get Ryuk's crash-safety
back.

## Deploy to AWS

```bash
# First-ever deploy: walks through stack name, region, capabilities, etc.
# and saves your answers to samconfig.toml
sam deploy --guided

# Subsequent deploys reuse samconfig.toml — no flags needed
sam deploy

# Non-interactive deploy (e.g. CI), skipping the changeset confirmation
sam deploy --no-confirm-changeset --no-fail-on-empty-changeset

# Fast dev loop: watches for local changes and pushes them automatically
sam sync --stack-name tylink --watch
```

## Invoke the deployed (remote) function

```bash
# 1. Get the live API endpoint from the stack outputs
aws cloudformation describe-stacks \
  --stack-name tylink \
  --query "Stacks[0].Outputs[?OutputKey=='CreateUrlApi'].OutputValue" \
  --output text

# 2. Call it over HTTP
curl -i -X POST "<url-from-above>" \
  -H "Content-Type: application/json" \
  -d '{}'

# Alternative: invoke the Lambda directly, bypassing API Gateway,
# using the same sample event used for local testing
aws lambda invoke \
  --function-name <CreateUrlFunction-physical-id> \
  --cli-binary-format raw-in-base64-out \
  --payload file://events/createUrl.json \
  response.json && cat response.json

# Alternative: let SAM resolve the function ARN from the stack for you
sam remote invoke CreateUrlFunction --stack-name tylink --event-file events/createUrl.json
```

## Logs

```bash
# Tail logs in real time
sam logs -n CreateUrlFunction --stack-name tylink --tail

# Fetch logs from a specific window
sam logs -n CreateUrlFunction --stack-name tylink --start-time '10min ago'
```

## Cleanup

```bash
# Delete the stack and all resources it created
sam delete --stack-name tylink --no-prompts
```

## Resources

* [AWS SAM developer guide](https://docs.aws.amazon.com/serverless-application-model/latest/developerguide/what-is-sam.html)
* [AWS Lambda Powertools for Java](https://docs.powertools.aws.dev/lambda/java/)
