# Phase 2 — Research, Scale, and Resolve Challenges

Goal: a documented before/after (latency percentiles, cost, failure modes) for every scaling optimization applied. This write-up *is* the learning artifact, not just working code.

## Sequence

1. **Baseline load test first**, deliberately small, to empirically find the actual first bottleneck before "fixing" anything — don't pre-optimize. (Use the Phase 3 k6 setup at small scale for this — see `03-phase3-load-testing.md`.)
2. Address Java cold starts (SnapStart), re-test.
3. Address read latency/hot keys: check CloudWatch Contributor Insights for hot partitions → rely on CloudFront edge caching (already in place from Phase 1) → only add DAX if a measured problem remains that caching can't solve.
4. Decide DynamoDB capacity mode (on-demand by default — absorbs bursts natively; provisioned+autoscaling only as a deliberate cost/behavior comparison exercise).
5. Throttling/backpressure: API Gateway per-route/stage throttling (rate & burst limits), exponential backoff+jitter on the client/SDK side, reserved concurrency to protect DynamoDB from a Lambda pile-up.
6. Security hardening: per-Lambda least-privilege IAM (N3); WAF as an optional add-on (see `00-overview.md` stack table).
7. Observability depth: X-Ray active tracing, Powertools EMF metrics, Lambda Insights, dashboards tied back to the Phase 1 SLOs (N1). Verify N6 (MTTD < 5 min) concretely: inject a synthetic failure (e.g. temporarily revoke a Lambda's DynamoDB permission, or point it at a nonexistent table) and time how long until a CloudWatch Alarm fires and notifies via SNS.
8. Resilience: DLQ on the TTL/Streams cleanup path, idempotency on create (already built in Phase 1), a one-paragraph note on DynamoDB Global Tables as "next level, out of scope here."

---

## System-Design Deep Dive

For each concern: **why it matters** → **theory/pattern** → **AWS implementation**.

| Concern | Why it matters (what breaks without it) | Theory / Pattern | AWS implementation |
|---|---|---|---|
| **Short-code generation & hot-partition avoidance** | An auto-increment counter becomes a write hot spot: every create hits one partition (capped ~1,000 WCU/partition). Sequential codes are also guessable. | Partition-key cardinality / load distribution | Random Base62 code as the partition key itself (see `01-phase1-build.md`); conditional `PutItem`; naturally load-balances writes across partitions |
| **DynamoDB access-pattern design** | "Redirect by code" and "list by user" are different shapes; designing around only one breaks the other | Access-pattern-driven NoSQL modeling / single-table design | Base table `PK=URL#code` for the hot redirect path; GSI1 `PK=USER#id` for paginated list-by-user |
| **Cold start mitigation (Java)** | Java has notably worse cold starts than Node/Python (JVM class-loading, framework init); a traffic spike causes a burst of cold starts that directly inflates p99 | Snapshot-restore / warm-pool pattern | **Lambda SnapStart** (Java 11+) restores from a pre-initialized Firecracker snapshot instead of re-running init. Mutually exclusive with provisioned concurrency. Gotcha directly relevant here: `SecureRandom` entropy for code generation must be drawn **inside the handler invocation**, not a static initializer — otherwise the snapshot bakes in reused entropy across restores |
| **Read scaling / caching** | A viral link concentrates reads on one item; per-partition read limits (3,000 RCU) and Lambda round-trips add up under load | Cache-aside / write-through caching | **CloudFront edge caching** of the redirect response (`Cache-Control` header) is the idiomatic first move for a URL shortener — removes Lambda from the hot path entirely for cached hits, and is already in the Phase 1 build. **DAX** (deferred) is the write-through, DynamoDB-API-compatible alternative if a real per-request-side-effect problem remains after measuring |
| **Cache freshness vs Update/Delete/Expire** | Once redirects are cached at the edge, an updated or deleted link can keep serving the stale destination until the cache entry expires | Cache invalidation trade-off | Short `max-age` + explicit CloudFront invalidation call from the update/delete Lambda, or accept bounded staleness as a documented trade-off |
| **Throttling & backpressure** | Without limits, a spike or abusive client can consume the account-level API Gateway ceiling (default steady-state/burst caps are a hard token bucket) and starve every other route, or slam DynamoDB into throttling | Token-bucket rate limiting / backpressure propagation | HTTP API's **per-route/stage rate & burst limits** (note: HTTP APIs don't support usage plans/API keys — that per-client-identity quota feature is REST-API-only; HTTP API throttling is IP/account/route-scoped, not per-client); DynamoDB **on-demand mode** absorbs bursts natively (autoscaling on provisioned mode reacts on a lag and does *not* fix hot-partition throttling — only redistributing the key space does); client/SDK **exponential backoff with jitter** |
| **Auth token validation scaling** | Validating a JWT with a custom Lambda authorizer on every request adds an extra Lambda invocation — latency + cost — to every API call | Edge-validated / stateless auth | API Gateway's **native JWT authorizer** validates the Cognito token itself, no custom Lambda involved (already the Phase 1 design) |
| **Auth *service* capacity (distinct from token validation)** | The login/sign-up endpoints are capped by Cognito's default account+region-wide quotas — `UserAuthentication` (sign-in ops: `InitiateAuth`, `AdminInitiateAuth`, `RespondToAuthChallenge`, `AdminRespondToAuthChallenge`) is 120 RPS, `UserCreation` (sign-up ops) is 50 RPS, aggregated across **all** user pools in the account+region, not per-pool. These are still far below what a well-scaled Lambda/DynamoDB layer can be pushed to in a stress test, and a naive load test that re-authenticates every iteration will hit this ceiling and misattribute it as an app bottleneck | Rate-limited upstream dependency in a load test | Pre-authenticate virtual users once, reuse the JWT for the test duration (see `03-phase3-load-testing.md`); request a Cognito quota increase (paid, per-RPS-increment) only if actually needed |
| **Idempotency for URL creation** | A retried create request (client timeout + retry) can otherwise produce two different short codes for one logical request | Idempotency key pattern | **Lambda Powertools Idempotency utility**, backed by DynamoDB conditional writes + TTL, keyed on the client's `Idempotency-Key` header |
| **Async cleanup resilience** | A TTL/Streams-triggered cleanup Lambda can fail transiently; without a safety net, that event is silently lost | Dead-letter queue / at-least-once processing | DynamoDB Streams → Lambda event source mapping with an `OnFailure` destination (SQS DLQ) + a CloudWatch alarm on DLQ depth; filter the stream to only `REMOVE` events from TTL to cut invocation volume |
| **Observability at scale** | Without distributed tracing you can't tell whether a slow p99 is Lambda init, the DynamoDB call, or API Gateway overhead | Distributed tracing / structured observability | X-Ray active tracing (service map with per-node p50/p90, highlights cold-start penalties); Powertools structured logs with correlation IDs + CloudWatch EMF custom metrics; **Lambda Insights** for CPU/memory |
| **Security at scale** | An unprotected public API/login page is a direct target for credential stuffing and scraping once reachable | Perimeter rate limiting | HTTP API's built-in route/stage throttling covers a free, IP/account-wide baseline; it can't distinguish *which* client is abusing you, though — **AWS WAF** rate-based rules (per-IP buckets, optional, real cost) is the production-grade next step for that |
| **Multi-region / DR** (concept only, not built) | Single-region deployment has a real, if accepted, region-outage risk | Multi-active replication | **DynamoDB Global Tables** — worth knowing exists; out of scope here since it drags in Route 53 latency routing and multi-region API Gateway/Cognito/Lambda considerations |

---

### Short-Code Generation — Alternatives Considered

| Approach | Pros | Cons |
|---|---|---|
| Auto-increment counter → Base62 | Never collides; shortest codes at any scale | Hot partition on the counter item; sequential → enumerable |
| Pre-generated Key Generation Service (KGS) | No collision retries; keys pre-vettable | Extra table/queue + replenishment job; overkill at this scale |
| Hash-based (MD5/SHA truncated) | Deterministic → free dedup of same URL | Truncated hash still collides → needs salt-and-retry anyway; no natural support for re-shortening same URL with different settings |
| UUID v4, truncated | Trivial, stdlib everywhere | Truncating throws away UUID's collision resistance → same problem as random+retry, less control over alphabet |
| Snowflake-style ID (timestamp+worker+seq) | No coordinator; sortable by time; zero retries | Longer codes; time-sortable → guessable, same as auto-increment; needs a stable worker/machine ID, awkward for stateless Lambda |
| Counter w/ per-node range allocation | Fixes single-hot-counter problem, shorter/ordered codes | Needs persistent per-worker state, fights Lambda's stateless model |
| **Random + conditional write (chosen)** | No coordinator/extra infra; spreads writes across partitions; non-enumerable; fits stateless Lambda + SnapStart | Non-deterministic (no free dedup, not needed here); rare collision-retry loop; fixed code length |

Chosen approach is the direct fix for the hot-partition/enumerable-ID anti-pattern above, with zero extra infrastructure — matching this project's learning goal (`00-overview.md`) of teaching DynamoDB scaling patterns without over-building.

---

## KMS — the Concrete, Justified Use Case

A naive URL shortener has no real secret to protect — DynamoDB's default encryption (AWS-owned key) already covers "encrypted at rest." Two genuinely defensible reasons to actually use KMS here, both cheap (~$1/mo flat for the CMK):

1. **Baseline**: switch DynamoDB table encryption to a customer-managed key (CMK) instead of the default. Nearly invisible functionally (DynamoDB caches the data key ~5 min per caller), but every key use now shows up in CloudTrail and you control the key policy — a real least-privilege/auditability exercise.
2. **"Private link" bonus feature**: let a user mark a link private; for those items, the create-Lambda calls `GenerateDataKey` and envelope-encrypts `longUrl` before storing it. Only the **redirect Lambda's** execution role gets `kms:Decrypt` on that key; the create-Lambda only gets `kms:GenerateDataKey`. Even someone with full DynamoDB read access (e.g. a table export) can't see the private destination without the specific KMS grant — a genuine, teachable envelope-encryption + least-privilege exercise.

## Verification

Phase 2 is done when: a documented before/after report exists (latency percentiles from X-Ray/CloudWatch, cost, what broke and what fixed it) for cold starts, caching, and throttling, and the N6 MTTD experiment above has a measured number against the < 5 min target.
