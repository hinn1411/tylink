# Decision: Per-Route API Gateway Throttling, Sized Off Origin Traffic

## Context

`docs/plans/06-system-design-deep-dive.md`'s "Throttling & backpressure" row prescribes the
lever for this project: HTTP API per-route/stage rate & burst limits (HTTP API has no
usage-plan/API-key support — that's REST-API only) plus client/SDK exponential backoff with
jitter. DynamoDB is already on-demand and has shown zero throttling evidence in any run in
`docs/reports/scaling-load-test-results.md`, so it isn't part of this lever.

Before this change, no throttle config existed anywhere (`template.yaml` /
`infrastructures/*.yaml`) and the load-test scripts had no retry/backoff logic. Every route
shared one account-level API Gateway ceiling and one Lambda concurrency pool (raised 10→1000
manually, not IaC-managed) with zero isolation — a spike on any one route could starve every
other route sharing that concurrency.

## Root cause

Without per-route isolation, "a spike on `/v1/urls` starves `/v1/urls/{shortCode}`" isn't a
hypothetical: both draw from the same account-wide Lambda concurrency pool, and API Gateway's
only throttle before this change was the account-level default (10,000/s steady-state, 5,000
burst, region-wide) — far above anything this project's traffic would ever hit, so it provided
no real backpressure between routes.

## Decision

Add per-route `RouteSettings` (plus a `DefaultRouteSettings` safety net) to the `HttpApi`
resource in `template.yaml`, giving each route its own token bucket:

| Route | Rate | Burst |
|---|---|---|
| `GET /v1/urls/{shortCode}` (redirect) | 40 | 80 |
| `POST /v1/urls` (create) | 20 | 40 |
| `GET /v1/urls` (list) | 20 | 40 |
| `PATCH /v1/urls/{shortCode}` (update) | 15 | 30 |
| `DELETE /v1/urls/{shortCode}` (delete) | 15 | 30 |
| `POST /v1/auth/login` | 10 | 20 |
| Default (any future route) | 10 | 20 |

**Sizing reasoning:**

- **Redirect (40/80)**: this route serves both the `hot` and `cold` k6 scenarios, but
  CloudFront's `/v1/urls/*` cache behavior (`docs/technical_decisions/15-cloudfront-edge-caching.md`)
  absorbs most of that traffic at the edge — the throttle only needs to bound *origin* traffic,
  not viewer traffic. `hot` cycles 3 codes inside a 300s TTL, so origin contribution drops to
  ~0 after warm-up. `cold` cycles ~810 codes at the ~78-79% hit ratio measured in §2, so origin
  traffic ≈ 22% of applied rate. `realistic.js`'s `cold_key_redirect` runs at a flat 30 req/s
  → ~6.6 req/s origin, well under 40 (~6x headroom, 0% 429 expected in normal traffic). Against
  `stress.js`'s ramp (50→100→200→400 req/s target), origin traffic under the same flat-22%
  assumption tracks ~11→22→44→88 req/s, crossing 40 around the third stage — throttling should
  appear in the back half of a stress run, not immediately and not never. The flat-22% figure is
  a simplification (real hit ratio likely rises with sustained rate, since more draws per TTL
  window means more repeats), so treat 40/80 as a first attempt, correctable from a "before"
  run's CloudWatch `Count` on this route — the same empirical tuning loop §2 used for the cache
  TTL.
- **CRUD writes (create 20/40, update/delete 15/30, list 20/40)**: all sit behind CloudFront's
  `CachingDisabled` default behavior, so origin traffic is 1:1 with applied traffic.
  `realistic.js`'s `auth_crud` scenario ramps to 5 VUs with 1s think-time between steps —
  roughly 1-2 req/s per verb at steady state, ~10x headroom. `stress.js`'s `auth_crud` has no
  think-time, so its first stage alone (target 50 iters/s) already puts ~50 req/s on each verb —
  intentionally above these limits: a write-path spike should shed immediately at API Gateway
  rather than consume Lambda concurrency shared with `redirect`.
- **Login (10/20)**: not load-derived — k6's `setup()` calls it once per test run, not per VU —
  sized as baseline perimeter protection rather than from traffic data. Neither harness will
  produce a meaningful 429-rate data point for this route.
- Account-level API Gateway default throttle (10,000/s steady-state, 5,000 burst) sits nowhere
  near these numbers — no interaction to reason about.

**429 caching at CloudFront — verified non-issue, no `infrastructures/cloudfront.yaml` change
needed.** `RedirectCachePolicy`'s `DefaultTTL: 0` means any response without an explicit
`Cache-Control`/`Expires` header defaults to a 0-second TTL — the same backstop
`15-cloudfront-edge-caching.md` already documents for 404/410/500 responses. A 429 from
API-Gateway-level throttling never reaches `RedirectUrlHandler` (the only code path that ever
sets `Cache-Control`), so it always falls under that existing backstop; no separate mechanism
was needed to keep a throttle response from being cached and replayed to later callers.

**Client-side counterpart**: `scripts/load-test/common/lib.js` gained a `requestWithRetry`
helper wired into every HTTP call site, retrying only on 429 with full-jitter exponential
backoff (`sleep = random(0, min(2000ms, 100ms * 2^attempt))`, up to 5 attempts total). Every
other status — including this app's expected non-2xx responses like 410/404 — returns
immediately, untouched. Parameters chosen so a retried call adds ~1.5s expected / ~3s
worst-case latency: negligible when retries essentially never trigger (headroom keeps normal
traffic under every route's limit), and expected to push `stress.js`'s throttled stages over
the `p99<1000ms` SLO on purpose — that demonstrates shedding, not silent capacity. `createUrl`
generates its `Idempotency-Key` once, outside the retry closure, so retries of one logical
create reuse the same key; `ShortenUrlHandler.createUrl()` only rejects a reused key paired with
a *different* `longUrl`, and the retried body is identical, so this is a same-request retry, not
a collision risk — and moot in practice, since a 429 never reaches the handler at all.

## Explicitly out of scope for this iteration

- **Reserved/provisioned concurrency** — belongs to the SnapStart-restore-cost fix already
  tracked in the report's "Beyond This Project" section; mixing it in here would confound
  attribution between "backpressure via 429 shedding" and "restore-cost via warm capacity," and
  AWS forbids SnapStart and provisioned concurrency on the same alias.
- **AWS WAF rate-based rules** — real ongoing cost, and addresses per-IP abuse (a security
  concern) rather than general capacity shedding; `06-system-design-deep-dive.md` already marks
  it a deliberate future step, not this one.
- **Per-client usage plans / API keys** — not just deferred but not possible: HTTP API doesn't
  support them at all (that's a REST-API-only feature).
- **DynamoDB capacity-mode changes** — already on-demand, already confirmed zero throttling
  evidence across every prior run in the report.
- **Lambda reserved-concurrency-based per-function isolation** — a plausible alternative
  mechanism for the same "one route shouldn't starve another" goal, deliberately not used here
  to keep this iteration scoped to the API-Gateway-front-door + client layer; it would touch the
  same `Globals.Function` block SnapStart already configures.
