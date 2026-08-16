# Little's Law, Worked Through

`13-load-testing-fundamentals.md` §4 states Little's Law (`L = λ × W`) and uses it to justify
giving an arrival-rate load test generous concurrency headroom. The formula alone is easy to
accept and hard to *feel*. This doc derives it from a picture instead of asserting it, then
verifies it against a real load-test run, then applies it to a concrete capacity number.

---

## 1. The picture: occupancy is just a step function

Draw each request as a horizontal bar on a timeline, from its arrival time to its departure
time. "How many requests are in the system at time `t`" is just: how many bars cross the
vertical line at `t`. That's a step function — it goes up by one at every arrival and down by
one at every departure.

Four requests over a 10-second window:

- Request A: arrives at `t=0`, leaves at `t=3` (in the system for 3s)
- Request B: arrives at `t=2`, leaves at `t=4` (2s)
- Request C: arrives at `t=5`, leaves at `t=9` (4s)
- Request D: arrives at `t=7`, leaves at `t=8` (1s)

```
t:          0  1  2  3  4  5  6  7  8  9  10
Request A:  ███████
Request B:        ████
Request C:               ████████
Request D:                     ██
occupancy:  1  1  2  1  0  1  1  2  1  0
```

**Average occupancy**, read directly off the step function: sum the occupancy at each second and
divide by the window length — `(1+1+2+1+0+1+1+2+1+0) / 10 = 1.0`.

Now compute the same quantity a completely different way. Arrival rate: 4 requests in 10s, so
`λ = 0.4/s`. Average time in system: `(3+2+4+1) / 4 = 2.5s`, so `W = 2.5s`. Then
`λ × W = 0.4 × 2.5 = 1.0`.

Same answer, two unrelated-looking calculations.

## 2. Why they have to match

That's not a coincidence to confirm experimentally — it's the same number measured two ways.

The **total area under the occupancy curve** can be computed as:

- average height × window length (`L × T`), or
- the sum of each individual bar's own width, since each request contributes exactly its own
  duration's worth of area (one unit tall, for exactly as long as it's present) — this sum is
  `(number of requests) × (average duration)`, i.e. `(λ × T) × W`.

Both expressions equal the same area, so `L × T = λ × T × W`, and dividing both sides by `T`
gives `L = λ × W`.

Nothing about *how* the system processes requests entered that derivation — not the arrival
pattern, not whether there's one server or many, not queueing discipline or service order, not
any distributional assumption. The only fact used is that every request which arrives eventually
leaves. That's why it's called a law rather than a model: it's a bookkeeping identity, true by
construction, not a result that depends on the system behaving a particular way.

One caveat: this is a steady-state, long-run-average identity. Over a short or transient window,
a request that's already in flight at the window's start, or still in flight at its end,
introduces a small boundary error (its full duration isn't captured inside the window). That
error shrinks toward zero as the window grows large relative to individual request durations —
which is also why capacity claims from a load test should be read off a sustained hold period,
not off the ramp-up or ramp-down edges of the run.

## 3. Verified against a real k6 run

This isn't only a toy-example result — a real k6 run already obeys it. Running
`14-load-testing-with-k6.md` Lab 1's script (1 VU, no `sleep()`, looping as fast as it can) for 5
seconds reported:

```
iteration_duration.............: avg=2.54s
vus.............................: 1
iterations......................: 2      0.392995/s
```

With exactly one VU that's always either sending a request or waiting on its response, the
number of iterations in flight at any instant is essentially always 1 — `L ≈ 1`. Little's Law
then predicts the achieved rate: `λ = L / W = 1 / 2.54s ≈ 0.3937/s`.

The summary's actual reported rate: `0.392995/s`. That match isn't a property of this specific
script — it's Little's Law holding on live data, the same identity as the toy example in §1.

## 4. The payoff: sizing concurrency for an arrival-rate load test

Under a closed-loop, VU-based executor, you set `L` (VU count) directly and `λ` falls out as
whatever the system happens to produce — §3's example. Under an **open-loop, arrival-rate**
executor (the kind a defensible capacity claim needs — see `13-load-testing-fundamentals.md`
§3), it's inverted: you declare `λ` (the target rate), the system under test determines `W`
(its latency, which you don't control), and `L` — the concurrency the load generator must hold
in flight to sustain that declared rate — falls out as a *consequence*, not a knob you set.

Concretely, for a target of 50 requests/sec:

- System healthy, `W = 20ms` → `L = 50 × 0.02 = 1` request in flight. Trivial.
- Same target rate, but mid-climb in a stress test where cold starts or saturation push
  `W` to `2s` → `L = 50 × 2 = 100` requests in flight, just to keep arrivals landing at the rate
  you declared.

If the load generator's concurrency ceiling is set too low for that second case — say, capped at
20 — it physically cannot hold 100 requests in flight. The arrival rate silently drops below the
declared target, and the test stops measuring the system's ceiling and starts measuring the load
generator's own ceiling instead, which defeats the reason for using an arrival-rate executor in
the first place. This is the concrete mechanism behind `14-load-testing-with-k6.md` §3.1's advice
to set `maxVUs` generously: rising latency during a stress test's climb is exactly what the test
is trying to provoke, and Little's Law says that rise directly increases the concurrency needed
to sustain any fixed target rate.

---

## Where this connects

- `13-load-testing-fundamentals.md` §4 — states the law and the headroom point this doc derives
  and verifies in detail.
- `14-load-testing-with-k6.md` §3.1 — the practical `maxVUs`/`preAllocatedVUs` guidance this doc
  is the reasoning behind; §7.3 also leans on the law when reasoning about `sleep()`/think-time.

## References

- Neil Gunther, *Guerrilla Capacity Planning* — Little's Law and open/closed workload models in a
  queueing-theory context (also cited in `13-load-testing-fundamentals.md`).
