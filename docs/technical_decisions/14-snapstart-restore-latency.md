# Decision: Keep Connection-Only Priming, Not Full-Path Priming, for SnapStart Restores

## Context

SnapStart (`ApplyOn: PublishedVersions` + `AutoPublishAlias`) was applied to all seven Lambda
functions to remove cold-start latency. Load testing (`docs/reports/scaling-load-test-results.md`
§1) showed SnapStart *was* restoring rather than cold-starting, but p99 still failed the 1000ms
SLO by a wide margin — CloudWatch Logs Insights showed restore invocations (~1.8% of traffic
at the time) averaging 4124ms `Duration` on Redirect and 1144ms on the Authorizer, versus
20-40ms warm. The question was how to close that gap.

## What we tried

1. **CRaC `afterRestore` connection-priming, SDK-call-only (kept).** A shared
   `SnapStartWarmup.registerAfterRestore(Runnable)` utility runs one real network call per
   client during the restore's `afterRestore` hook — `DynamoDbClient.getItem()` for a
   nonexistent key, `JwkProvider.get()` for a bogus key ID, `CognitoIdentityProviderClient
   .initiateAuth()` with bogus credentials — forcing TCP/TLS setup to happen during restore
   instead of on the first real request. Confirmed effective via X-Ray subsegment timing: a
   restore invocation's actual DynamoDB call dropped to 26-43ms, matching the warm baseline,
   down from ~4100ms unwarmed. This directly cut p99 roughly in half (hot: 9.6s → 5.75s; see
   the report's Before → After table) and is the only priming technique that shipped.

   Gotcha worth knowing: CRaC's `Context` holds only a `WeakReference` to registered
   `Resource`s — a `Resource` with no other strong reference gets garbage-collected before
   restore ever happens, silently disabling the hook. `SnapStartWarmup` keeps a static
   `CopyOnWriteArrayList` of every registered `Resource` specifically to prevent this.

2. **Full request-path priming (rejected, rolled back).** Hypothesis: connection-priming
   alone doesn't warm JIT compilation or class-loading for the rest of the request path, so
   the first real invocation still pays that cost. Changed `RedirectUrlHandler` and
   `ExtractTokenAuthorizerHandler` to call their own `handleRequest()` with a synthetic event
   during `afterRestore`, instead of a bare SDK call.

   Result: no meaningful improvement, and one variant (a synthetic event enriched to mirror a
   real API Gateway payload's full shape, to warm Jackson's serializers for
   `RequestContext`/`Http`/`Authorizer`) made Redirect's restore-invocation `Duration`
   measurably *worse* (1466ms → 1558ms avg). The mechanism: `Restore Duration` and `Duration`
   are both real wall-clock time the caller waits on. Making the warmup do more work shifted
   cost from `Duration` into `Restore Duration` — the total per-function cost stayed roughly
   flat (~3.1-3.5s) across every full-path variant tried.

3. **Isolating Powertools' `@Logging`/`@FlushMetrics` aspects (informative, rolled back).**
   Manual X-Ray subsegments bracketing the handler body showed the missing latency sat almost
   entirely *outside* traced business logic — in the gap immediately before the method body
   starts and after it returns, which is where Powertools' AspectJ-woven advice
   (`LambdaLoggingAspect` wrapping `LambdaMetricsAspect` wrapping the method) runs. Temporarily
   removing both annotations confirmed this as a real, non-trivial contributor: Redirect's
   pre-body gap dropped from ~640ms to ~370ms, and the Authorizer's dropped from ~1050ms to
   ~370-400ms (with its overall restore-invocation total falling from ~2.1-3.0s to ~2.2-2.4s
   for the typical case).

   Not adopted, because a real ~500-580ms gap remained even with **zero** annotations and
   **zero** application code running beyond a single traced SDK call — most likely JVM/GC
   activity intrinsic to resuming from a Firecracker snapshot, not anything reducible at the
   application layer. Permanently losing structured logging and custom metrics on two
   production functions wasn't worth a partial, non-SLO-closing win.

## Root cause (confirmed, not application code)

Across every variant, `Restore Duration` + `Duration` combined settled around **2.2-2.9
seconds per function** on a restore invocation, regardless of how much or how little
application code ran during the hook. Since the Authorizer runs before every backend function,
a request where both restore in the same scale-up burst (common — they scale together) pays
both costs: ~4.3-5.3s combined, which matches the load test's observed p99 (5.09-5.75s hot/cold)
almost exactly. This is a platform/JVM-level cost of the restore itself, not something any of
the priming strategies above could reach.

## Decision

Keep technique 1 (SDK-call connection-priming) only. Revert techniques 2 and 3 in full:
`RedirectUrlHandler` and `ExtractTokenAuthorizerHandler` are back to their pre-investigation
form (no handler-level `warmUp()`, `@Logging`/`@FlushMetrics` restored, no manual X-Ray
subsegments); `CognitoJwtVerifier` has its own narrow `warmUp()` back.

SnapStart + connection-priming is a genuine, real win — it eliminates the worst-case
unwarmed-connection tax (~4s → tens of ms) — but does **not**, by itself, meet a sub-1000ms p99
SLO under this project's burst load pattern. Closing that gap needs a different lever entirely,
not more application-code tuning: reducing how often a restore happens at all via provisioned
concurrency. That means *replacing* SnapStart on these functions, not combining with it — AWS
does not support SnapStart and provisioned concurrency together on the same version/alias. See
`docs/reports/scaling-load-test-results.md`'s "Beyond This Project" section.
