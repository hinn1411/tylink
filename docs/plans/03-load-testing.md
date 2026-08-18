# Phase 3 — Load Test & Verify at Scale

Goal: a load-test report with graphs and a defensible, measured capacity number.

This phase and `02-scaling.md` are interleaved, not sequential: this file builds the load-test
harness and runs it; `02-scaling.md`'s iterative loop (SLO → technique → re-run → compare) is what
actually consumes each run between applying scaling techniques. The harness described below is
built once, early, and reused for every iteration of that loop, plus the larger capstone run at the
end of this phase.

## Steps

1. Define two load profiles: One for expected traffic pattern and One for find the peek capacity
2. Write k6 scripts covering:
   - (a) auth & CRUD flow
   - (b) **hot-key redirect** (Cache hit)
   - (c) **cold/random-key redirect** (Cache miss, hit Lambda/DynamoDB directly)

3. **Use a single JWT for multiple VUs** rather than sending multiple requests to Cognito. In default configuration, sign-in quota is 120 rps and sign-up quota is 50 rps for the whole AWS account & region, not per-pool
4. Run k6 locally for iteration. Optionally, after latency optimization loop is done, we can use AWS Distributed Load Testing service which utilizes k6 script to test at large scale
5. Use CloudWatch dashboards, logs and X-Ray traces in the test window to identify bottle necks
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

- A load-test report exists with k6 output + correlated CloudWatch/X-Ray screenshots, stating a measured capacity ("X RPS at p99 < Yms") and the actual bottleneck found.

**Report: see `../reports/scaling-load-test-results.md`** (in progress — the capacity claim and bottleneck analysis are filled in once the capstone stress run completes).
