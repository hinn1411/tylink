# Phase 2 — Research, Scale, and Resolve Challenges

Goal: a documented before/after (latency percentiles, cost, failure modes) for every scaling optimization applied. This write-up *is* the learning artifact, not just working code.

## Sequence

1. **Define SLOs**: use N1 (latency), N2 (availability), N5 (cost guardrail), N6 (MTTD) from `00-overview.md` as the pass/fail bar for everything below
2. **Set up observability first**: to find bottlenecks, do the following:
- X-Ray activation & structured logs via Powertools
- EMF metrics and CloudWatch dashboards to monitor Cold start, percentiles and result codes
- Lambda Insights for CPU/Memory monitoring
3. **Verify the observability actually works**: inject a synthetic failure (revoke a Lambda's DynamoDB permission, or use nonexistent table) and time how long until a CloudWatch Alarm fires and notifies via SNS.
4. **Baseline load test**: Set up a small load test to find the actual first bottlenecks
5. **Iterative loop, one variable at a time**: only apply one optimization technique at a time → re-run the *same* load test → compare against the SLO → document the before/after → move to the next technique.
   1. **Cold starts**: Lambda SnapStart, re-test. Gotcha: `SecureRandom` entropy for short-code generation must be drawn inside the handler invocation, not a static initializer, or the snapshot bakes in reused entropy across restores.
   2. **Read latency / hot keys** *(done — CloudFront in `infrastructures/cloudfront.yaml`)*: CloudFront edge caching on the redirect response (default `*.cloudfront.net` domain, no Route 53 yet) → check CloudWatch Contributor Insights for hot partitions → only add DAX if a measured problem remains that caching can't solve. Cache-freshness/invalidation trade-off resolved as short `max-age` (5 min), no invalidation — see `docs/technical_decisions/15-cloudfront-edge-caching.md`.
   3. **DynamoDB capacity mode**: on-demand by default — absorbs bursts natively; provisioned+autoscaling only as a deliberate cost/behavior comparison exercise.
   4. **Throttling / backpressure**: API Gateway per-route/stage throttling (rate & burst limits), exponential backoff+jitter on the client/SDK side, reserved concurrency to protect DynamoDB from a Lambda pile-up.
6. **Hardening track, done deliberately** — a load test won't surface these on its own: per-Lambda least-privilege IAM (N3); AWS WAF as an optional add-on (see `00-overview.md` stack table) once per-route throttling alone isn't enough to distinguish *which* client is abusing you.

---

**System-design rationale for each concern above: see `06-system-design-deep-dive.md`.**

---

## Verification

- All APIs are accessed via the CloudFront URL
- A documented before/after report exists, including latency percentiles, what broke and what fixed it
- N6 MTTD experiment above has a measured number against the < 5 min target.

**N6 MTTD result (2026-08-13): 89s — pass.** Failure: `IDEMPOTENCY_TABLE_NAME` on `ShortenUrlFunction` pointed at a nonexistent table. t0 07:26:20 UTC (request sent) → t1 07:27:49 UTC (`tylink-shorten-url-errors` alarm ALARM state + SNS email delivered).

**Before/after report: see `../reports/scaling-load-test-results.md`** (in progress — filled in per technique as step 5 above is executed).
