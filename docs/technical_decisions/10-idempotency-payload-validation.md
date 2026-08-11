## Context

F4 requires idempotent `create` via a client-supplied `Idempotency-Key` header, using
Powertools' Idempotency utility. Powertools keys purely on that header — it never inspects the
body. Decision: should `ShortenUrlHandler` also validate that a reused key carries the same
`longUrl`?

## Default behavior

Powertools stores one record per key in DynamoDB (`INPROGRESS` → `COMPLETED`, TTL 1h default). A
repeat key within the TTL replays the cached response without re-running the method; a repeat
key still `INPROGRESS` throws `IdempotencyAlreadyInProgressException`. The body is never
inspected unless `withPayloadValidationJMESPath` is configured.

This fits a client that mints one key per attempt and resends it verbatim on retry — same key =
same request, by construction. It does not catch a key reused across two *different* requests
(stale/hardcoded key, copy-paste bug) — Powertools can't tell the difference.

## Options considered

- **Replay only (default).** Repeat key always returns the original cached response, regardless
  of the new body. Correct when the key is derived from the payload itself (reuse with a
  different body is then impossible), or for trusted, low-consequence internal callers. No extra
  cost. Risk: a key-reuse bug silently returns the wrong answer, with no error.
- **Key only, reject any reuse (no replay).** A repeat key is always rejected, even with an
  identical body — no replay, no payload check, just an existence check on the key. Fits "block
  duplicate submissions" (e.g. double-click guards) rather than "survive a client retry." Risk:
  breaks F4's actual use case — a client retrying after losing its first, successful response
  gets rejected instead of getting back the `shortCode` it already created.
- **Throw on mismatch (payload validation).** Powertools hashes selected fields and raises
  `IdempotencyValidationException` on a mismatch. Correct when the key is caller-generated
  independently of the body (nothing prevents reuse), the API is public-facing, or a silent wrong
  response has real cost — what Stripe's `Idempotency-Key` does. Cost: one hash per request, on
  top of the DynamoDB round-trip idempotency already pays. No amplification risk unless the
  validated payload is unbounded.

## Decision

Enable payload validation on `longUrl` only (not `visibility`). A mismatch maps to `409
Conflict`.

TyLink's key is caller-generated, independent of the body — nothing but client discipline
prevents reuse across different `create` calls. `ShortenUrlHandler` returns a `shortCode` the
caller may publish, so a silent wrong `longUrl` under a reused key is a visible, human-facing
bug, not just an internal inconsistency.

Cost is negligible here: `LongUrlValidator.MAX_LENGTH = 2048`
(`functions/src/main/java/com/tylink/utils/LongUrlValidator.java:12`) already bounds `longUrl`
before idempotency runs, and API Gateway caps bodies at 10MB regardless — hashing ≤2048 chars is
sub-microsecond next to the millisecond-scale DynamoDB call idempotency already makes.

**Confirmed.** Throw on mismatch keeps both benefits at once: a genuine retry (same key, same
`longUrl`) still gets the fast replay, while a reused key with a different `longUrl` is caught
instead of silently served. `Idempotency-Key` is required (`400` if missing) rather than
optional, and the TTL is 5 minutes, not Powertools' 1-hour default — short enough that a client
retry after a real outage mints a fresh short code instead of hitting a stale window.
