# Phase 3 — Load Test & Verify at Scale

Goal: a load-test report with graphs and a defensible, measured capacity number.

## Steps

1. Define two load profiles: a "realistic" traffic profile and a separate "find the wall" stress-to-failure profile.
2. Write k6 scripts covering:
   - (a) auth+CRUD flow
   - (b) **hot-key redirect** (same handful of codes hammered — exercises the cache)
   - (c) **cold/random-key redirect** (long-tail codes — defeats caching, exercises Lambda/DynamoDB directly)

   This split matters: a cache-friendly-only test would hide all the DynamoDB/Lambda scaling behavior you're trying to observe.
3. **Pre-authenticate virtual users once and reuse the JWT** rather than hitting Cognito's sign-in endpoint per iteration — Cognito's default sign-in quota (`UserAuthentication` category, 120 RPS) and sign-up quota (`UserCreation`, 50 RPS) are shared across your whole AWS account+region, not per-pool, and are still far lower than your Lambda/DynamoDB layer can be pushed to in a stress test — hitting them will falsely look like "the bottleneck" otherwise (see `02-phase2-scaling.md`, "Auth service capacity").
4. Run k6 locally for iteration. Optionally, as a one-time capstone, deploy the AWS Distributed Load Testing solution reusing the same k6 script, then tear it down (real but bounded cost — see `00-overview.md` stack table).
5. Capture CloudWatch dashboards + X-Ray traces for the exact test time window.
6. Analyze against the Phase 1 latency SLOs (N1); change one variable at a time and re-run to isolate cause and effect.
7. Write up a verified capacity claim ("handles X RPS at p99 < Yms"), the actual bottleneck found, and what the next scaling step beyond this project would be (e.g. Global Tables, DAX, provisioned concurrency).

## Metrics to Watch and Correlate

- Lambda: `ConcurrentExecutions`, `Throttles`, `Duration` (p50/p90/p99)
- DynamoDB: `ConsumedReadCapacityUnits`/`ConsumedWriteCapacityUnits`, `ThrottledRequests`/`UserErrors`, `SystemErrors`
- API Gateway: `Count`, `4XXError`, `5XXError`, `Latency`, `IntegrationLatency`
- CloudFront: cache hit ratio
- Cognito: auth-endpoint throttling (if you forgot to pre-authenticate virtual users)

---

## Testing Strategy (all three layers)

- **Unit test**: pure Java, no AWS. Base62 encode/decode, expiry validation, idempotency-key derivation — JUnit5. Handler logic tested with **Mockito** mocking the `UrlRepository` interface (N4) — this interface boundary is *why* unit testing a Lambda handler is possible at all; without it you'd be mocking the DynamoDB SDK client directly, which is brittle.
- **Integration test**: `sam local start-api` + **DynamoDB Local** (Docker) — fast, free, fully offline test of the real Lambda-to-DynamoDB interaction. A real ephemeral dev stack (`sam deploy` → test → `sam delete`) is the more honest way to integration-test the Cognito+API Gateway+Lambda chain end-to-end, as a CI stretch goal.
- **Performance/load test**: outermost layer, run against a real deployed stack — the subject of this phase.

## Verification

Phase 3 is done when: a load-test report exists with k6 output + correlated CloudWatch/X-Ray screenshots, stating a measured capacity ("X RPS at p99 < Yms") and the actual bottleneck found.
