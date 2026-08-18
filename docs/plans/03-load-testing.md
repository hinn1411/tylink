# Phase 3 — Load Test & Verify at Scale

Goal: a load-test report with graphs and a defensible, measured capacity number.

This phase and `02-scaling.md` are interleaved, not sequential: this file builds the load-test
harness and runs it; `02-scaling.md`'s iterative loop (SLO → technique → re-run → compare) is what
actually consumes each run between applying scaling techniques. The harness described below is
built once, early, and reused for every iteration of that loop, plus the larger capstone run at the
end of this phase.

## Steps

1. Define two load profiles: a "realistic" traffic profile and a separate "find the wall" stress-to-failure profile.
2. Write k6 scripts covering:
   - (a) auth+CRUD flow
   - (b) **hot-key redirect** (same handful of codes hammered — exercises the cache)
   - (c) **cold/random-key redirect** (long-tail codes — defeats caching, exercises Lambda/DynamoDB directly)

   This split matters: a cache-friendly-only test would hide all the DynamoDB/Lambda scaling behavior you're trying to observe.
3. **Pre-authenticate virtual users once and reuse the JWT** rather than hitting Cognito's sign-in endpoint per iteration — Cognito's default sign-in quota (`UserAuthentication` category, 120 RPS) and sign-up quota (`UserCreation`, 50 RPS) are shared across your whole AWS account+region, not per-pool, and are still far lower than your Lambda/DynamoDB layer can be pushed to in a stress test — hitting them will falsely look like "the bottleneck" otherwise (see `02-scaling.md`, "Auth service capacity").
4. Run k6 locally for iteration — this is the harness `02-scaling.md`'s loop re-runs after each scaling technique. Optionally, as a one-time capstone once that loop is done, deploy the AWS Distributed Load Testing solution reusing the same k6 script, then tear it down (real but bounded cost — see `00-overview.md` stack table).
5. Capture CloudWatch dashboards + X-Ray traces for the exact test time window.
6. Analyze against the Phase 1 latency SLOs (N1). This is the measurement half of `02-scaling.md`'s iterative loop — change one variable at a time and re-run to isolate cause and effect; don't apply two scaling techniques between runs, or you can't attribute the improvement.
7. Write up a verified capacity claim ("handles X RPS at p99 < Yms"), the actual bottleneck found, and what the next scaling step beyond this project would be (e.g. Global Tables, DAX, provisioned concurrency).

## Metrics to Watch and Correlate

- Lambda: `ConcurrentExecutions`, `Throttles`, `Duration` (p50/p90/p99)
- DynamoDB: `ConsumedReadCapacityUnits`/`ConsumedWriteCapacityUnits`, `ThrottledRequests`/`UserErrors`, `SystemErrors`
- API Gateway: `Count`, `4XXError`, `5XXError`, `Latency`, `IntegrationLatency`
- CloudFront: cache hit ratio
- Cognito: auth-endpoint throttling (if you forgot to pre-authenticate virtual users)

---

## Verification

Phase 3 is done when: a load-test report exists with k6 output + correlated CloudWatch/X-Ray screenshots, stating a measured capacity ("X RPS at p99 < Yms") and the actual bottleneck found.

**Report: see `../reports/scaling-load-test-results.md`** (in progress — the capacity claim and bottleneck analysis are filled in once the capstone stress run completes).
