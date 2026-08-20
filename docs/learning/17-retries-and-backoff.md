# Retries and Backoff

Why a client retrying a failed request needs more than "try again" — the failure-type distinction
that makes a retry safe or wasteful, and the backoff/jitter math that keeps a retry from making an
outage worse. Read `01-system-design-concepts.md` §9 first (idempotency and delivery guarantees) —
this doc is the client-side mechanics behind that concept.

---

## 1. Why retry exists

Networks don't guarantee exactly-once delivery: a connection can time out, reset, or a response
can be lost after the server already succeeded. A client often can't tell "the request failed"
from "it succeeded, but I never heard back" — this is why systems build for **at-least-once**
delivery instead and rely on retries.

A retry is a bet, not a free action: repeating a request costs little if the failure was
*transient* (a momentary timeout, a load spike) — but is wasted, or actively harmful, if the
failure was *permanent* (the request itself is invalid, or the resource genuinely doesn't exist).

## 2. The precondition: idempotency

A retry is only safe if repeating the operation produces the same effect as doing it once —
**idempotency is not automatic; it has to be designed in.** Two examples, one on each side of that
line:

- A conditional write that either succeeds once or fails cleanly with no partial effect (e.g.
  retrying on a write-conflict error) is safe to retry by construction — there's no in-between
  state a repeated attempt could double up on.
- A plain "create a resource" call is *not* safe to retry as-is — calling it twice creates two
  resources. `../technical_decisions/10-idempotency-payload-validation.md` is exactly this fix: a
  client-supplied idempotency key makes a repeated create call replay the first result instead of
  creating a second one — the key is what turns an unsafe operation into a safe one to retry.

## 3. Backoff: don't retry at a fixed interval

Retrying immediately, or at a fixed interval, against a dependency that's already struggling
doesn't give it room to recover — every retrying client piles back in on the same short cycle.
**Exponential backoff** grows the delay geometrically with each attempt, capped at a maximum:

```
delay = min(cap, base * 2^attempt)
```

With `base = 100ms`, attempts 0/1/2/3 wait 100ms/200ms/400ms/800ms — each retry gives the
dependency more room than the last, until the cap takes over.

## 4. Jitter: why synchronized backoff still fails

If every client computes the *exact same* exponential delay, they don't spread out — they retry in
near lockstep, arriving as one synchronized spike instead of gradually. The fix is **jitter**:
randomize the delay instead of using it exactly. The standard form, **full jitter**:

```
delay = random(0, min(cap, base * 2^attempt))
```

This spreads retries across the whole window instead of one instant. AWS's own study of this
(cited below) found full jitter cuts total retry volume by more than half compared to un-jittered
exponential backoff under contention — it's the standard default. (Equal jitter and decorrelated
jitter are variants that trade some randomness for a floor under the delay; full jitter is the one
to reach for unless a specific problem calls for those.)

## 5. Bounding retries

A retry loop with no stop condition isn't resilience — it's amplification: a client that retries
forever just adds sustained load to a dependency exactly when it's least able to take it. A retry
policy needs **two independent limits**, usually both: a maximum attempt count, and a deadline
(total time budget) — whichever is hit first stops retrying and surfaces the failure to the
caller instead.

TyLink names this mitigation — "client/SDK exponential backoff with jitter" — in its scaling plan
as a response to API Gateway throttling, but it isn't implemented anywhere in the stack yet; this
doc is the concept prep for that gap.

## 6. What's actually retryable

Not every failure deserves a retry — only ones where trying again might get a different outcome:

- **Retryable**: timeouts, connection resets, `5xx`, `429` — signals of a transient or
  capacity problem (a `429` is exactly the throttling signal covered in
  `16-rate-limiting-and-api-gateway-throttling.md`).
- **Not retryable**: `400`/`422` — the request itself is wrong. Retrying it fails again,
  identically, every time, and just burns the attempt budget that should be reserved for failures
  that can actually resolve on a later try.

## Where this connects

`01-system-design-concepts.md` §9 covers idempotency and at-least-once delivery — the precondition
§2 depends on. `16-rate-limiting-and-api-gateway-throttling.md` is this doc's server-side
counterpart: a `429` from that doc's throttle is exactly the signal §6 says to back off on, not
hammer. `../technical_decisions/10-idempotency-payload-validation.md` is what makes retrying
TyLink's create endpoint actually safe. `../plans/02-scaling.md` and
`../plans/06-system-design-deep-dive.md` name backoff-with-jitter as a planned mitigation, not yet
built.

## References

- Marc Brooker, "Exponential Backoff and Jitter" (AWS Architecture Blog): https://aws.amazon.com/blogs/architecture/exponential-backoff-and-jitter/ — the full-jitter formula and contention study behind §4.
