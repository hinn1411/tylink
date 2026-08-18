# Load Testing Fundamentals

Tool-agnostic theory — no k6 syntax here. Read this before `14-load-testing-with-k6.md`;
that file assumes the concepts below and only shows how to *express* them in k6.

---

## 1. Four tests, four different questions

"Load testing" is often used loosely for any of these, each needing a different
traffic *shape*:

| Test | Question | Shape |
|---|---|---|
| **Load** | Does the system meet its SLO at *expected* traffic? | Flat or gently ramping to a target, hold |
| **Stress** | Where's the actual ceiling? | Ramp until something breaks (error rate spikes, latency SLO fails) |
| **Spike** | Does a *sudden* burst cause different failures than a slow ramp? (e.g. connection-pool exhaustion, cold-start pile-ups) | Sharp jump, hold briefly, drop |
| **Soak** | Does anything degrade only over long duration (memory leak, connection exhaustion, a slow resource leak)? | Flat, but long (hours) |

Using the wrong shape breaks the answer: a stress test run with a load test's flat
shape never climbs past the point where the system was already fine, so it never
finds the ceiling; a load test run with a stress test's aggressive ramp gets
contaminated by transient ramp-up effects that wouldn't occur at steady state.

TyLink's plan needs exactly two of these — load ("realistic" profile, checked against
the latency SLO) and stress ("find the wall"). Spike and soak are real tests, just not
in scope here.

---

## 2. Percentiles, not averages

An average hides the slow outliers real users actually feel. If 99 requests take 10ms
and 1 takes 5000ms, the average (~60ms) looks fine while 1% of users had a terrible
experience. **Percentiles** (p50, p90, p99) describe the *distribution's tail*, which is
the part that matters for an SLO — "p99 < 100ms" means 99% of requests were under
100ms, a claim an average can't make.

**Tail latency amplification**: if a single
user-facing request fans out to several backend calls, the *chance that at least one of
them lands in the slow tail* rises with the fan-out count — a system where each
individual call is "p99 < 100ms" can still have a user-facing p99 far worse than 100ms
once multiple calls are chained or parallelized. This is why an SLO belongs on the
outermost, user-facing measurement, not just on each internal component individually.

See `01-system-design-concepts.md` #1 for where this shows up in TyLink's own N1
requirement (p99 < 1000ms, applied uniformly across redirect and CRUD — a percentile
SLO, not an average, by design).

---

## 3. Closed-loop vs. open-loop load generation

This is the formal concept behind "concurrent users ≠ requests per second":

- **Closed-loop**: a fixed number of simulated users, each looping — send a request,
  *wait for the response*, then send the next. The request rate this produces is an
  **emergent result** of how fast the system responds: a faster system makes the same
  user count generate more requests/sec; a slower system makes it generate fewer. The
  user count is the input; the throughput is an output you can only observe after the
  fact — it describes "how fast N users could push it today," not "what happens at a
  declared, fixed rate."
- **Open-loop**: requests are generated at a fixed rate, independent of how long
  previous requests take to respond. If the system slows down, requests still arrive at
  the declared rate — they queue up instead of the generator waiting. The rate is the
  **input**; queueing/backlog is what shows the system falling behind.

Real-world traffic is almost always closer to open-loop: a viral link doesn't slow down
its own arrival rate just because your server got slower — new visitors keep clicking
at whatever rate the outside world is clicking, whether or not your system can keep up.
A closed-loop test structurally cannot reproduce that: as the system slows, a
closed-loop generator's *offered load drops too*, silently making the system look
better than it would under real, rate-independent demand — which is why a defensible
"handles X requests/sec" capacity claim needs an open-loop (or open-loop-emulating)
load generator.

---

## 4. Little's Law — why concurrency, arrival rate, and latency are locked together

A load generator's concurrency (how many simulated users are in flight at once), its
target arrival rate, and the system's response latency aren't three independent knobs —
one classic queueing-theory result, **Little's Law**, ties them together:

```
L = λ × W
```

- `L` — average number of requests in flight (concurrency)
- `λ` (lambda) — arrival rate (requests/sec)
- `W` — average time each request spends in the system (latency)

Concretely: sustaining 100 requests/sec against an endpoint with 200ms average latency
requires, on average, `100 × 0.2 = 20` requests in flight at any moment. If your load
generator can only keep 10 concurrent requests in flight, it *cannot* sustain 100
req/sec no matter how it's configured — it'll top out around 50 req/sec instead
(`10 / 0.2`).

This is the math behind giving an open-loop/arrival-rate test generous headroom for
concurrent in-flight requests: if latency rises during the test (exactly what a stress
test is trying to provoke), the concurrency needed to sustain the same declared rate
rises too — a generator without that headroom becomes the bottleneck itself, and the
test silently measures the generator's limit instead of the system's.

---

## 5. Coordinated omission — the hidden bias in closed-loop measurement

A subtler consequence of §3's closed-loop model: it doesn't just misrepresent
*throughput* under slowdown, it systematically **under-reports tail latency** too.

Picture a closed-loop VU: it sends a request, waits, sends the next. Now suppose the
system stalls for 2 seconds. During that stall, a closed-loop VU issues exactly *one*
slow request (the one it's waiting on) — it can't issue more, because it's blocked
waiting for that response before sending the next one. Meanwhile, an open-loop model
(or real users) would have kept arriving throughout that whole 2-second stall,
producing many requests that all experienced something close to that full delay. The
closed-loop test's sample of "slow requests" during the stall is tiny compared to what
actually would have queued up in reality — the measurement **omits** most of the
evidence of exactly the event you most wanted to measure. Hence "coordinated omission":
the measurement process is coordinated (paused) right along with the system's own
slowdown, hiding it. This is the deep reason an arrival-rate-based test's percentile
numbers are trustworthy for a capacity claim in a way a closed-loop test's aren't — not
just "the RPS number is more honest" (§3), but "the *latency distribution itself* is
measured without a built-in blind spot during exactly the failure mode you're trying to
find."

---

## 6. Checks vs. thresholds — two different jobs

Any load-testing tool needs two distinct kinds of assertion, and conflating them
produces either noisy failures or an untrustworthy pass/fail signal:

- **Per-request checks** — "was this individual response correct?" (right status code,
  right body shape, right header). A single failed check is a debugging signal, not a
  verdict on the whole test — a load test that aborts on the first single failed
  request is unusable, since some transient failures are expected background noise even
  in a healthy system.
- **Whole-run thresholds** — "did the *test as a whole* meet its bar?" (e.g. 99th
  percentile latency under some number, error rate under some fraction). This is the
  actual go/no-go signal a load test exists to produce, and the thing a CI pipeline or
  a written report would gate on.

A load-testing report needs both: a threshold verdict as the headline result, and
per-request check data to explain *why* if it failed.

---

## 7. Designing two profiles: realistic vs. stress-to-failure

These serve genuinely different purposes and should be built with different design
goals, not as variations of the same script with different numbers plugged in:

- **Realistic (load test)**: models actual expected usage — traffic shaped like real
  daily patterns (ramp up, hold at peak, ramp down), with **think-time** (a pause
  between a simulated user's actions, mimicking a real person reading a page before
  clicking again) between requests. Its thresholds are the real SLO, checked with no
  slack — this test answers "does the system meet its promise today, at expected
  load," nothing more aggressive.
- **Stress-to-failure**: deliberately *not* realistic — no think-time (don't waste test
  time simulating pauses), climbing load in
  stages until something gives. Its purpose is finding the **capacity** number: the
  point where latency stops being flat and starts climbing as load increases (the
  "knee" in a latency-vs-load curve), or where the error rate crosses an unacceptable
  threshold. A stress test that never finds this knee within its climb range hasn't
  actually tested anything — it just confirmed the system handles less than its
  configured ceiling, which a load test already answered more cheaply.

Stopping a stress test **the moment** its SLO threshold is first breached (rather than
running the full planned climb regardless) matters for a subtle reason: everything
after that point just re-confirms the system is broken, at increasing cost/risk, without
adding new information — the useful signal is the *transition point* itself.

---

## 8. Correlating client-observed and server-reported latency

A load generator only sees latency from *outside* the system — request sent to response
received, including network round-trip time to wherever the system lives. The system's
own components (a load balancer, an application server, a database) can each report
*their own* latency, measured from inside.

The gap between the client-observed number and the sum of server-reported numbers is
itself diagnostic:

- **Client ≈ sum of server components** — the system's own latency accounts for
  essentially all of what the client measured; look inside the system (which specific
  component) for where time is going.
- **Client noticeably exceeds the server-reported sum** — something outside the
  measured components is adding time: network latency to the system, a queue/buffer the
  server-side instrumentation doesn't cover, DNS resolution, TLS handshake overhead
  on a fresh connection, etc.

This is the general method behind "run the load test, then go look at the system's own
tracing/metrics for the same time window" — the load test alone tells you *that*
something is slow; correlating client-observed against server-reported, component by
component, is what tells you *where*.

---

## 9. A single load generator has a ceiling

One machine running a load-testing tool is itself bounded by its own CPU, memory, and
network bandwidth — past some point, adding more simulated load doesn't test the
target system harder, it just makes the generator itself the bottleneck, and the
results stop meaning anything about the system under test.

The general fix is **horizontal scaling of the load generator**: run the same test
script across multiple machines simultaneously, each generating a slice of the total
load, aggregating results afterward. This is a distinct concept from distributing the
*system under test* (the usual meaning of "scaling") — here it's the *measurement tool*
that needs to scale, once a single-machine test can no longer produce enough load to
find the real ceiling of the system being tested.

Before assuming a single-machine test wasn't enough, check whether the generator's own
CPU/network saturated during the stress test's climb.

---

## Where this connects

`14-load-testing-with-k6.md` shows how each concept above maps onto a specific k6
mechanism — closed-loop vs. open-loop → k6 executors; Little's Law's headroom
requirement → `maxVUs` sizing; checks vs. thresholds → `check()` vs. `options.thresholds`;
the two profiles → two `scenarios` configurations; correlating latency → reading
CloudWatch/X-Ray for the test's time window; a single generator's ceiling → k6
execution segments / AWS Distributed Load Testing. Read that file next, with the
concepts above as the reason each mechanism exists.

## References

- Neil Gunther, *Guerrilla Capacity Planning* — Little's Law and open/closed workload
  models in a queueing-theory context.
- Gil Tene, "How NOT to Measure Latency" (talk) — the original, detailed treatment of
  coordinated omission.
- `01-system-design-concepts.md` #1 — percentiles and tail latency amplification, in
  TyLink's own system-design context.
- `../plans/00-overview.md` — N1 (latency SLO), the concrete percentile targets these
  concepts are being applied to.
