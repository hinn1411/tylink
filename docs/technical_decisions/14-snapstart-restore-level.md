# Decision: Keep Connection-Only Priming, Not Full-Path Priming, for SnapStart Restores

## Context

AWS Lambda SnapStart removes most cold-start latency by freezing a snapshot of an
already-initialized execution environment and restoring new instances from that snapshot,
instead of re-running startup from scratch each time. SnapStart (`ApplyOn: PublishedVersions`
+ `AutoPublishAlias`) was applied to all seven Lambda functions in this project. Load testing
(`docs/reports/scaling-load-test-results.md` §1) showed SnapStart *was* restoring rather than
cold-starting, but p99 still failed the 1000ms SLO by a wide margin — CloudWatch Logs Insights
showed restore invocations (~1.8% of traffic at the time) averaging 4124ms `Duration` on the
redirect handler and 1144ms on the authorizer, versus 20-40ms warm. The question was how to
close that gap.

## What we tried

1. **Connection-priming, SDK-call-only (kept).** SnapStart's snapshot can't preserve open
   network connections — sockets don't survive being frozen and thawed — so the first real
   request after a restore used to pay full price to reopen them. CRaC (Coordinated Restore at
   Checkpoint) lets code register a hook that runs immediately after a snapshot restores, before
   any real request arrives. A shared `SnapStartWarmup` utility uses this hook to make one real,
   throwaway network call per AWS client right there — a DynamoDB read, a JWKS lookup, a Cognito
   auth attempt, each against a bogus target that's expected to fail — so the TCP/TLS connection
   is already open by the time a real request shows up. Confirmed effective via X-Ray timing: a
   restore invocation's actual DynamoDB call dropped to 26-43ms, matching the warm baseline,
   down from ~4100ms unwarmed. This cut p99 roughly in half (hot: 9.6s → 5.75s; see the report's
   Before → After table) and is the only priming technique that shipped.

   Gotcha worth knowing: CRaC only keeps a *weak* reference to a registered hook — with nothing
   else holding onto it, the hook can be garbage-collected before it ever fires, silently
   disabling it. `SnapStartWarmup` keeps its own list of every registered hook to prevent that.

2. **Full request-path priming (rejected, rolled back).** Hypothesis: connection-priming alone
   doesn't warm up the rest of the request-handling code (JSON parsing, framework overhead,
   business logic), so the first real invocation still pays a "first time running this code"
   tax. Instead of one bare SDK call, the redirect and authorizer handlers ran their *entire*
   request-handling logic against a fake request during the restore hook.

   Result: no meaningful improvement, and one variant made things measurably worse. The
   mechanism: SnapStart reports two timing phases — the restore itself, and the handler
   execution that follows — and both are real time the caller waits on. Making the priming hook
   do more work just shifted cost from one phase into the other; the combined total per function
   stayed flat no matter how much extra work the hook did.

3. **Removing the logging/metrics framework's overhead (informative, rolled back).** Manual
   timing around the handler body showed the unexplained latency sat almost entirely *outside*
   the actual business logic — inside the Powertools framework code that wraps every handler
   call for structured logging and metrics. Temporarily removing that instrumentation confirmed
   it as a real, non-trivial contributor (several hundred ms per restore).

   Not adopted: a real ~500-580ms gap remained even with that instrumentation removed entirely
   and no other application code running — most likely JVM/garbage-collection activity that's
   intrinsic to resuming from a snapshot, not something fixable in application code.
   Permanently losing structured logging and metrics wasn't worth a partial win that still
   didn't close the SLO gap.

## Root cause

No matter how much (or how little) priming ran, a restore invocation still cost **~2.2-2.9
seconds per function** — a platform/JVM cost of the restore itself, not application code. The
authorizer runs before every request and restores in the same bursts as the backend functions,
so a single request can pay both costs at once (~4.3-5.3s), which is why p99 stays high even
after priming.

## Decision

Keep connection-priming (technique 1) only; techniques 2 and 3 are fully reverted.

It's a real, worthwhile win — it removes the worst-case unwarmed-connection tax (~4s → tens of
ms) — but can't close the SLO gap alone. That needs fewer restores in the first place (e.g.
provisioned concurrency, which *replaces* SnapStart rather than combining with it — AWS doesn't
support both on the same function), not more application-code tuning. See the report's "Beyond
This Project" section.
