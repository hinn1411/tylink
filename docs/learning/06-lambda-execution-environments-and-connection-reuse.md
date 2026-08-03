# Lambda Execution Environments and Connection Reuse

Why `ShortenUrlHandler`'s `DynamoDbClient.create()` (in the constructor, not
`handleRequest`) only sets up one connection pool per environment — and what
changes under concurrent load.

---

## 1. Init vs. Invoke

Lambda splits a request into two phases ([AWS docs][lambda-lifecycle]):

- **Init** — runs once per execution environment: class loading, static
  initializers, the constructor. This is where `DynamoDbClient.create()` runs.
- **Invoke** — runs once per request, reusing the same environment (and the
  same handler instance) for every subsequent warm request. `handleRequest()`
  runs here.

A single execution environment handles **one invocation at a time** — it's
busy for the full duration of a request and can't take a second one
concurrently. Concurrency comes from Lambda provisioning *more* environments,
never from one environment serving two requests at once.

So the constructor — and `DynamoDbClient.create()` — reruns only on a cold
start (new environment), not on every request.

## 2. What `DynamoDbClient.create()` actually does

Building the client wires up an SDK object around a lazily-initialized HTTP
client. No socket opens at construction time. The first `putItem()` call
opens a TCP+TLS connection to the DynamoDB endpoint via Apache's connection
pool, which then **keeps it open** (HTTP keep-alive) instead of closing it.
Every later invocation on the same warm environment reuses that pooled
connection — no repeated handshake.

## 3. Why the field must be instance-level

```java
private final DynamoDbClient dynamoDb;      // built once, in the constructor

public ShortenUrlHandler() {
    this(DynamoDbClient.create(), System.getenv("TABLE_NAME"));
}
```

If `DynamoDbClient.create()` were called inside `handleRequest()` or
`createUrl()` instead, it would run — and rebuild a fresh connection pool —
on *every single invocation*, even warm ones. That's a well-known Lambda
anti-pattern; storing the client as a `final` field built in the constructor
is what avoids it.

## 4. Under concurrency: one pool *per environment*, not one shared pool

N concurrent requests → Lambda provisions up to N separate execution
environments (bounded by account concurrency limits, see below) → each gets
its **own** `DynamoDbClient` and its own connection pool. "One connection per
environment" is per-environment, not a single global connection shared
across all concurrent traffic.

## 5. Worked example: 1,000 concurrent requests to `ShortenUrlHandler`

Verified against current AWS docs, not assumed:

| Layer | Default limit | Headroom at 1,000 concurrent |
|---|---|---|
| API Gateway (HTTP API) | 10,000 RPS, 5,000 burst, per account/region ([docs][apigw-quotas]) | Comfortable |
| Lambda concurrency | **1,000 concurrent executions, per account/region, shared by every function in that account** ([docs][lambda-concurrency]) | **Exactly at the ceiling** |
| Lambda scaling rate | 1,000 new environments per 10s, per function ([docs][lambda-concurrency]) | Fits in one burst window |
| DynamoDB on-demand | New tables sustain 4,000 writes/sec baseline; auto-scales to 2× previous peak instantly ([docs][ddb-on-demand]) | Comfortable — well under baseline |

**The binding constraint is Lambda's account-wide concurrency limit, not
DynamoDB or API Gateway.** 1,000 concurrent requests to *one* function
consumes the *entire* account's default concurrency budget — any other
function in the same account/region gets throttled during that window, and
if the account already has any baseline usage elsewhere, some of the 1,000
requests get rejected outright (Lambda throttles with a `429
TooManyRequestsException`, which API Gateway surfaces to the caller as a
`429`). Each successful request still gets its own environment + connection
pool as described in §4 — the failure mode here is admission, not connection
exhaustion.

Fix, if this were a real capacity concern: request a Lambda concurrency
quota increase, and/or set [reserved concurrency][reserved-concurrency] on
`ShortenUrlFunction` so it can't be starved by (or starve) other functions.

## References

- [Understanding execution environment lifecycle][lambda-lifecycle]
- [Understanding Lambda function scaling (concurrency)][lambda-concurrency]
- [DynamoDB on-demand capacity mode][ddb-on-demand]
- [API Gateway quotas][apigw-quotas]
- [AWS SDK for Java v2 — HTTP clients][sdk-http-clients]

[lambda-lifecycle]: https://docs.aws.amazon.com/lambda/latest/dg/lambda-runtime-environment.html
[lambda-concurrency]: https://docs.aws.amazon.com/lambda/latest/dg/lambda-concurrency.html
[reserved-concurrency]: https://docs.aws.amazon.com/lambda/latest/dg/configuration-concurrency.html
[ddb-on-demand]: https://docs.aws.amazon.com/amazondynamodb/latest/developerguide/on-demand-capacity-mode.html
[apigw-quotas]: https://docs.aws.amazon.com/apigateway/latest/developerguide/limits.html
[sdk-http-clients]: https://docs.aws.amazon.com/sdk-for-java/latest/developer-guide/http-configuration.html

## Interview questions

<details>
<summary>Why doesn't calling <code>DynamoDbClient.create()</code> in a Lambda constructor create a new connection on every invocation?</summary>

The constructor runs once per execution environment (cold start), not once
per invocation — Lambda reuses the same handler instance, and its fields,
across every warm invocation on that environment. See §1.
</details>

<details>
<summary>What would happen if <code>DynamoDbClient.create()</code> were called inside <code>handleRequest()</code> instead of the constructor?</summary>

A new client — and new connection pool — would be built on every single
invocation, warm or cold, wasting the TCP/TLS handshake cost repeatedly. See §3.
</details>

<details>
<summary>Does one Lambda execution environment ever process two requests concurrently?</summary>

No. An environment is busy for the full duration of one invocation and
can't take a second one until it's free. Concurrency is achieved by
provisioning more environments, not by one environment multiplexing
requests. See §1.
</details>

<details>
<summary>If a function receives 1,000 truly concurrent requests, what's most likely to throttle first: API Gateway, Lambda, or DynamoDB — and why?</summary>

Lambda, in most default-quota accounts — its account-wide concurrency limit
(1,000, shared across every function in the account/region) is consumed
entirely by this one burst, leaving zero headroom for anything else and
risking throttling if the account has any other concurrent usage. API
Gateway's default throttle (10,000 RPS/5,000 burst) and a fresh DynamoDB
on-demand table's baseline (4,000 writes/sec) both have comfortable
headroom at this volume. See §5.
</details>

<details>
<summary>Two Lambda invocations are running concurrently for the same function. Do they share a <code>DynamoDbClient</code> instance?</summary>

No — concurrent invocations run in separate execution environments, each
with its own instantiated handler and therefore its own `DynamoDbClient`
and connection pool. See §4.
</details>
