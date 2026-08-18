# Load Testing with k6 — TyLink's Harness

Read `13-load-testing-fundamentals.md` first — this doc assumes those concepts and only
shows how to *express* them in k6. `03-testing.md` specifies *what* to build (two
profiles, three script types, pre-authenticated JWTs, metrics correlated against
CloudWatch/X-Ray); this doc teaches the k6 mechanics to actually build it, from zero,
with labs against TyLink's real endpoints. Work through it in order — each part's lab
produces a script fragment the next part extends, converging on the harness
`03-testing.md` describes.

**Setup**:
- Install k6 at https://k6.io/docs/get-started/installation.
- Use `sam local start-api --port 3000` for labs — no AWS cost, fast iteration.

---

## Part 1 — k6's Execution Model

### 1.1 VUs, iterations, lifecycle

A **VU** (virtual user) is one simulated concurrent user — a loop that runs your script's
default function repeatedly. An **iteration** is one pass through that function. Ten VUs
each looping for 30 seconds is a *concurrency* model, not a request-rate model — this
distinction matters a lot and comes back in Part 3.

Every k6 script has up to four lifecycle stages, each running in a different place:

```js
// init context — runs once per VU, before any iterations. No HTTP calls here.
import http from 'k6/http';

export function setup() {
  // runs once, total, before any VU starts. Return value is passed to every VU.
  return { token: 'abc123' };
}

export default function (data) {
  // the VU code — runs once per iteration, once per VU, repeated per `options`.
  http.get('http://localhost:3000/v1/urls/abc123', {
    headers: { Authorization: `Bearer ${data.token}` },
  });
}

export function teardown(data) {
  // runs once, total, after all VUs finish.
}
```

`setup()`/`teardown()` running exactly once (not once per VU) is what makes them the
right place for one-time work like authenticating — Part 5 builds on this directly.

### Lab 1 — Hello, k6

Write a script that sends one `GET` request to `http://localhost:3000/v1/urls/{shortCode}`
(seed a real short code first via `sam local invoke ShortenUrlFunction --event
events/shortenPublicUrl.json`, or `curl -X POST` it) and run it with 1 VU for 5 seconds.
Run: `k6 run script.js`. Read the summary output — note `http_req_duration`,
`http_reqs`, `iterations`.

<details>
<summary>Solution sketch</summary>

```js
import http from 'k6/http';

export const options = {
  vus: 1,
  duration: '5s',
};

export default function () {
  http.get('http://localhost:3000/v1/urls/YOUR_CODE');
}
```

`options.vus` + `options.duration` is the simplest possible load shape: N constant VUs,
looping as fast as they can (no `sleep()` yet — every iteration fires immediately after
the last response), for a fixed wall-clock duration.
</details>

---

## Part 2 — Requests, Checks, and Thresholds

### 2.1 The response object

`http.get`/`http.post`/etc. return a response object: `.status`, `.body`, `.headers`,
`.json()` (parses the body), `.timings.duration` (this one request's latency). POST
bodies are strings — `JSON.stringify(...)` your payload yourself, k6 doesn't do it for
you:

```js
import http from 'k6/http';

const payload = JSON.stringify({ longUrl: 'https://example.com', visibility: 'PUBLIC' });
const params = {
  headers: { 'Content-Type': 'application/json', 'Idempotency-Key': crypto.randomUUID() },
};
const res = http.post('http://localhost:3000/v1/urls', payload, params);
```

(`crypto.randomUUID()` is available in k6's global scope — no import needed.)

### 2.2 Checks vs. thresholds — different jobs, don't confuse them

The concept (per-request debugging signal vs. whole-run pass/fail gate) is
`13-load-testing-fundamentals.md` §6 — here's how k6 expresses each half:

- **`check()`** — an inline, per-request assertion. A failed check does **not** stop the
  test or fail the run by default; it just increments a `checks` pass/fail counter you
  can inspect in the summary.
- **`thresholds`** (in `options`) — a pass/fail criterion for the *whole test run*. When
  a threshold is crossed, k6 exits non-zero — this is what CI would gate on.

```js
import http from 'k6/http';
import { check } from 'k6';

export const options = {
  thresholds: {
    http_req_duration: ['p(99)<300'],   // whole-test SLO
    checks: ['rate>0.99'],              // <1% of individual checks may fail
  },
};

export default function () {
  const res = http.get('http://localhost:3000/v1/urls/YOUR_CODE');
  check(res, {
    'status is 307': (r) => r.status === 307,          // per-request assertion
    'has Location header': (r) => r.headers['Location'] !== undefined,
  });
}
```

### Lab 2 — Checked redirect script

Extend Lab 1: add a `check()` verifying `status === 307` and the `Location` header
matches the long URL you seeded. Add a threshold requiring the check pass rate above
99%. Deliberately break it once (point at a short code that doesn't exist) and confirm
the run reports a threshold failure and a non-zero exit code (`echo $?` after `k6 run`).

<details>
<summary>Solution sketch</summary>

```js
import http from 'k6/http';
import { check } from 'k6';

export const options = {
  vus: 1,
  duration: '5s',
  thresholds: {
    checks: ['rate>0.99'],
  },
};

export default function () {
  const res = http.get('http://localhost:3000/v1/urls/YOUR_CODE');
  check(res, {
    'status is 307': (r) => r.status === 307,
    'redirects to seeded longUrl': (r) => r.headers['Location'] === 'https://example.com',
  });
}
```

Pointing at a nonexistent code makes `RedirectUrlHandler` return `REDIRECT_NOT_FOUND`
(404, per the error envelope `{"message": ..., "code": ...}` — see
`RequestUtils.errorResponse`), both checks fail, `checks` rate drops below 0.99, k6
reports `✗ THRESHOLDS FAILED` and exits 99.
</details>

---

## Part 3 — Controlling Load Shape: Executors

### 3.1 Executors: k6's closed-loop / open-loop knob

`13-load-testing-fundamentals.md` §3 covers the theory: a closed-loop generator's
throughput is an emergent *output*, an open-loop generator's rate is a declared
*input*, and only the latter supports a defensible "handles X RPS" claim. k6's
**executors** are how you pick which model a given scenario uses — they control *how*
iterations are scheduled:

| Executor | Models | Use for |
|---|---|---|
| `shared-iterations` | A fixed total iteration count, shared across VUs | Smoke tests, "run this exactly 100 times" |
| `per-vu-iterations` | Each VU runs N iterations | Same, but per-VU instead of shared |
| `constant-vus` | Fixed VU count for a duration | Simple concurrency tests (Lab 1/2's implicit executor) |
| `ramping-vus` | VU count changes over `stages` | Modeling a realistic daily traffic *shape* (ramp up, hold, ramp down) — still concurrency-based |
| `constant-arrival-rate` | Fixed **iterations/sec**, k6 adds VUs as needed (up to `maxVUs`) to sustain it | A true, defensible "test at X RPS" claim |
| `ramping-arrival-rate` | Iterations/sec changes over `stages`, VUs auto-scale to sustain each rate | The stress-to-failure "find the wall" profile |

`constant-arrival-rate`/`ramping-arrival-rate` need `preAllocatedVUs` (VUs started
upfront) and `maxVUs` (ceiling k6 may grow to if responses slow down and more concurrent
VUs are needed to sustain the target rate). Set `maxVUs` generously — per Little's Law
(`13-load-testing-fundamentals.md` §4), the concurrency needed to sustain a fixed rate
rises as latency rises, which is exactly what happens during a stress test's climb; too
low a `maxVUs` makes *the test itself*, not your API, the bottleneck.

```js
export const options = {
  scenarios: {
    steady_redirect_rate: {
      executor: 'constant-arrival-rate',
      rate: 50,              // 50 iterations per timeUnit
      timeUnit: '1s',        // → 50 req/s target
      duration: '30s',
      preAllocatedVUs: 20,
      maxVUs: 100,
    },
  },
};
```

### Lab 3 — VUs to arrival-rate

Take Lab 2's script. First run it with `vus: 10, duration: '30s'` (no `sleep()`) and note
the achieved `http_reqs` rate in the summary (`http_reqs`'s rate, requests/s). Then
rewrite it using `constant-arrival-rate` targeting *exactly* that same rate you just
measured. Compare: does achieved RPS match the target now? Why is the arrival-rate
version's number trustworthy in a way the VU version's number wasn't?

<details>
<summary>Solution sketch</summary>

```js
export const options = {
  scenarios: {
    redirect_at_target_rate: {
      executor: 'constant-arrival-rate',
      rate: 40,               // whatever the vus:10 run measured, e.g. 40 req/s
      timeUnit: '1s',
      duration: '30s',
      preAllocatedVUs: 10,
      maxVUs: 50,
    },
  },
};
```

This is closed-loop vs. open-loop (`13-load-testing-fundamentals.md` §3) made concrete:
the VU version's RPS was an emergent result of "how fast do 10 concurrent loops happen
to go against this API's current latency" — change the API's speed and the RPS changes
too, silently. The arrival-rate version's RPS is a declared input; if the API can't
sustain it, k6 grows VUs (up to `maxVUs`) to try, and if it still can't keep up, that
shows up explicitly as rising latency/dropped iterations, not as a silently lower,
unnoticed RPS number.
</details>

---

## Part 4 — Thresholds as SLOs, Split by Endpoint via Tags

### 4.1 One script, tag-scoped thresholds

TyLink's N1 is one threshold — p99 < 1000ms — applied uniformly, but
`http_req_duration` by default aggregates *every* request in the script into one metric.
Splitting by endpoint still matters, so each traffic type's pass/fail is visible on its
own rather than blended into one number. That needs **tags**: attach a tag per request,
then write a threshold scoped to that tag.

```js
import http from 'k6/http';

export const options = {
  thresholds: {
    'http_req_duration{endpoint:redirect}': ['p(99)<1000'],
    'http_req_duration{endpoint:crud}': ['p(99)<1000'],
  },
};

export default function () {
  http.get('http://localhost:3000/v1/urls/YOUR_CODE', { tags: { endpoint: 'redirect' } });
  http.get('http://localhost:3000/v1/urls', { tags: { endpoint: 'crud' } }); // needs auth, see Part 5
}
```

`abortOnFail: true` on a threshold stops the *entire test run* the moment it's breached
— useful for the stress-to-failure profile (Part 8), where you want to stop climbing
load the instant the SLO breaks rather than burning the full test duration past the
point that already answered the question.

### Lab 4 — Tagged thresholds

Extend Lab 2/3's redirect script with a second request type (any CRUD call — `GET
/v1/urls` needs auth, so use `POST /v1/urls` for now, unauthenticated create is allowed).
Tag each request type distinctly and write both N1 thresholds. Confirm the k6 summary
output breaks out `http_req_duration` per tag group.

<details>
<summary>Solution sketch</summary>

```js
import http from 'k6/http';
import { check } from 'k6';

export const options = {
  vus: 5,
  duration: '15s',
  thresholds: {
    'http_req_duration{endpoint:redirect}': ['p(99)<1000'],
    'http_req_duration{endpoint:crud}': ['p(99)<1000'],
  },
};

export default function () {
  const createRes = http.post(
    'http://localhost:3000/v1/urls',
    JSON.stringify({ longUrl: 'https://example.com', visibility: 'PUBLIC' }),
    {
      headers: { 'Content-Type': 'application/json', 'Idempotency-Key': crypto.randomUUID() },
      tags: { endpoint: 'crud' },
    }
  );
  check(createRes, { 'create succeeded': (r) => r.status === 201 });

  http.get('http://localhost:3000/v1/urls/YOUR_CODE', { tags: { endpoint: 'redirect' } });
}
```
</details>

---

## Part 5 — Setup, Data, and the JWT Pre-Auth Pattern

### 5.1 Why per-iteration login is wrong here

`03-testing.md` step 3 flags this explicitly: Cognito's sign-in quota
(`UserAuthentication`, 120 RPS) and sign-up quota (`UserCreation`, 50 RPS) are shared
account+region-wide, far below what the Lambda/DynamoDB layer can sustain — a script
that calls `POST /v1/auth/login` inside the VU loop will hit *Cognito's* ceiling long
before it finds *TyLink's*, and misattribute Cognito's throttling as your own system's
bottleneck. The fix: authenticate once, in `setup()`, and hand every VU the same token.

```js
import http from 'k6/http';

export function setup() {
  const res = http.post(
    'http://localhost:3000/v1/auth/login',
    JSON.stringify({ username: __ENV.TEST_USER_EMAIL, password: __ENV.TEST_USER_PASSWORD }),
    { headers: { 'Content-Type': 'application/json' } }
  );
  return { idToken: res.json('idToken') };
}

export default function (data) {
  http.get('http://localhost:3000/v1/urls', {
    headers: { Authorization: `Bearer ${data.idToken}` },
  });
}
```

`__ENV` reads environment variables passed at invocation
(`k6 run -e TEST_USER_EMAIL=... -e TEST_USER_PASSWORD=... script.js`) — never hardcode
credentials into the script file. Register a test user first with
`scripts/auth/register-test-user.sh` if you don't have one.

**Gotcha, specific to this stack**: use `idToken`, not `accessToken`. TyLink's native JWT
authorizer is configured with an `audience` (the Cognito app client ID) — API Gateway
checks that against the token's `aud` claim. Cognito's `idToken` carries `aud`; its
`accessToken` does not (it carries `client_id` instead, a different claim). Sending
`accessToken` here fails authorization in a way that's easy to misdiagnose as a k6 or
API bug rather than the right-looking-wrong-token mistake it actually is.

### 5.2 SharedArray — parameterizing without per-VU memory blowup

Redirect load needs real short codes to hit. A naive `const codes = [...]` array gets
**copied into every VU's memory** — fine for 10 codes, bad for 10,000 across 200 VUs.
`SharedArray` loads the data once and shares read-only access across all VUs:

```js
import { SharedArray } from 'k6/data';

const shortCodes = new SharedArray('short codes', function () {
  return JSON.parse(open('./short-codes.json')); // runs once, in init context only
});

export default function () {
  const code = shortCodes[Math.floor(Math.random() * shortCodes.length)];
  http.get(`http://localhost:3000/v1/urls/${code}`);
}
```

The generator function only runs during k6's init phase, once — not once per VU — which
is what makes the sharing possible.

### Lab 5 — Authenticated setup + shared short codes

1. Write a small script (or reuse `scripts/auth/register-test-user.sh`) that seeds 20 short
   codes via `POST /v1/urls`, saving them to `short-codes.json`.
2. Write a k6 script with `setup()` logging in once (per 5.1) and a `SharedArray` loading
   `short-codes.json` (per 5.2).
3. Have VUs alternate: 80% of iterations `GET` a random short code from the array
   (unauthenticated), 20% call authenticated `GET /v1/urls` using the shared token.

<details>
<summary>Solution sketch</summary>

```js
import http from 'k6/http';
import { SharedArray } from 'k6/data';
import { check } from 'k6';

const shortCodes = new SharedArray('short codes', function () {
  return JSON.parse(open('./short-codes.json'));
});

export function setup() {
  const res = http.post(
    'http://localhost:3000/v1/auth/login',
    JSON.stringify({ username: __ENV.TEST_USER_EMAIL, password: __ENV.TEST_USER_PASSWORD }),
    { headers: { 'Content-Type': 'application/json' } }
  );
  return { idToken: res.json('idToken') };
}

export default function (data) {
  if (Math.random() < 0.8) {
    const code = shortCodes[Math.floor(Math.random() * shortCodes.length)];
    const res = http.get(`http://localhost:3000/v1/urls/${code}`, { tags: { endpoint: 'redirect' } });
    check(res, { 'redirected': (r) => r.status === 307 });
  } else {
    const res = http.get('http://localhost:3000/v1/urls', {
      headers: { Authorization: `Bearer ${data.idToken}` },
      tags: { endpoint: 'crud' },
    });
    check(res, { 'listed': (r) => r.status === 200 });
  }
}
```
</details>

---

## Part 6 — Multiple Named Scenarios in One Script

`03-testing.md` step 2 asks for three distinct traffic shapes running as part of the same
overall test: **hot-key redirect** (same handful of codes, exercises the cache),
**cold-key redirect** (long-tail codes, defeats caching, exercises Lambda/DynamoDB
directly), and **auth+CRUD**. k6's `scenarios` object runs several independent load
profiles concurrently in one script run, each with its own executor, its own function,
and (via tags) its own thresholds:

```js
export const options = {
  scenarios: {
    hot_key_redirect: {
      executor: 'constant-arrival-rate',
      exec: 'hotKeyRedirect',
      rate: 30, timeUnit: '1s', duration: '1m',
      preAllocatedVUs: 10, maxVUs: 30,
      tags: { scenario: 'hot' },
    },
    cold_key_redirect: {
      executor: 'constant-arrival-rate',
      exec: 'coldKeyRedirect',
      rate: 10, timeUnit: '1s', duration: '1m',
      preAllocatedVUs: 10, maxVUs: 30,
      tags: { scenario: 'cold' },
    },
    auth_crud: {
      executor: 'ramping-vus',
      exec: 'authCrud',
      stages: [{ duration: '1m', target: 5 }],
      tags: { scenario: 'crud' },
    },
  },
  thresholds: {
    'http_req_duration{scenario:hot}': ['p(99)<1000'],
    'http_req_duration{scenario:cold}': ['p(99)<1000'],
    'http_req_duration{scenario:crud}': ['p(99)<1000'],
  },
};

export function hotKeyRedirect() { /* Part 5's redirect call, fixed small code set */ }
export function coldKeyRedirect() { /* same, but codes drawn from the full SharedArray */ }
export function authCrud() { /* list/update/delete using the shared token */ }
```

`exec` points each scenario at a *named* function instead of the implicit `default` —
this is what lets one file run three unrelated flows side by side, each independently
schedulable and independently measurable, matching `03-testing.md`'s "why the split
matters" note: a cache-friendly-only test would hide all the DynamoDB/Lambda scaling
behavior you're actually trying to observe, so hot and cold traffic must be visible as
*separate* series, not blended into one average.

### Lab 6 — The three-scenario script

Combine Labs 3–5 into one file matching the shape above: hot-key redirect against 3
fixed codes, cold-key redirect against the full `SharedArray`, auth+CRUD using the
shared token from `setup()`. Run it and confirm the summary reports three separate
`http_req_duration{scenario:...}` breakdowns.

<details>
<summary>Solution sketch</summary>

Structurally identical to the snippet above, with:
- `hotKeyRedirect()` picking from a 3-element array (`shortCodes.slice(0, 3)`) instead of
  the full set — small enough that CloudFront/DAX-style caching, once added in Phase 2,
  would show a measurable effect here specifically.
- `coldKeyRedirect()` picking uniformly from the entire `SharedArray` — long-tail access,
  defeats any small/LRU cache.
- `authCrud()` reusing `data.idToken` from `setup()`, cycling through list → update →
  delete-then-recreate (or similar), each request tagged `{ scenario: 'crud' }`.

The key thing this lab actually tests is whether the *tag propagation* is right — every
`http.get`/`http.post` inside each named function must carry its scenario tag, or the
per-scenario thresholds silently measure the wrong (or an empty) set of requests.
</details>

---

## Part 7 — Custom Metrics, Groups, and Think-Time

### 7.1 Custom metrics: Trend, Counter, Rate, Gauge

Built-in metrics (`http_req_duration`, `http_reqs`, `checks`) cover HTTP-shaped facts.
Business-specific facts need custom metrics:

```js
import { Trend, Counter, Rate } from 'k6/metrics';

const createLatency = new Trend('create_latency', true); // true = report as time (ms)
const idempotencyReuseHits = new Counter('idempotency_reuse_hits');
const notFoundRate = new Rate('short_code_not_found_rate');

export default function () {
  const res = http.get(`http://localhost:3000/v1/urls/${randomCode()}`);
  notFoundRate.add(res.status === 404);
  if (res.status === 404) {
    // ...
  }
}
```

Worth cross-checking client-observed custom metrics against the *server's* own EMF
metrics (`IdempotencyMiss`, `ShortCodeCollisionRetries` — see
`../technical_decisions/11-emf-metrics.md`) for the same time window in CloudWatch — if
k6's client-side count and CloudWatch's server-side count disagree, that's a signal
something between the two (network retry, a client-side bug, a load balancer) is doing
something you don't expect.

### 7.2 Groups — organizing multi-step flows for readable output

A realistic CRUD flow is several requests in sequence; `group()` labels them together in
the summary instead of flattening everything into one undifferentiated request list:

```js
import { group } from 'k6';

export default function (data) {
  group('crud lifecycle', function () {
    group('create', function () { /* POST /v1/urls */ });
    group('list', function () { /* GET /v1/urls */ });
    group('update', function () { /* PATCH /v1/urls/{code} */ });
    group('delete', function () { /* DELETE /v1/urls/{code} */ });
  });
}
```

### 7.3 `sleep()` — think-time

`13-load-testing-fundamentals.md` §7 covers why the realistic profile needs think-time
and the stress profile deliberately omits it. In k6, `sleep(1)` (seconds) between steps
is how you add it — and per Little's Law (§4), more think-time per iteration means more
concurrent VUs are needed to reach the same target RPS.

### Lab 7 — Full lifecycle, grouped, with a custom metric

Wrap a create → list → update → delete flow in nested groups (7.2), add 1–2s `sleep()`
between each step, and add a `Trend` metric timing just the `create` step specifically
(separate from the overall `http_req_duration`).

<details>
<summary>Solution sketch</summary>

```js
import http from 'k6/http';
import { group, sleep, check } from 'k6';
import { Trend } from 'k6/metrics';

const createLatency = new Trend('create_latency', true);

export default function (data) {
  let shortCode;

  group('crud lifecycle', function () {
    group('create', function () {
      const res = http.post(
        'http://localhost:3000/v1/urls',
        JSON.stringify({ longUrl: 'https://example.com', visibility: 'PRIVATE' }),
        {
          headers: {
            'Content-Type': 'application/json',
            'Idempotency-Key': crypto.randomUUID(),
            Authorization: `Bearer ${data.idToken}`,
          },
        }
      );
      createLatency.add(res.timings.duration);
      check(res, { 'created': (r) => r.status === 201 });
      shortCode = res.json('shortCode');
    });
    sleep(1);

    group('list', function () {
      http.get('http://localhost:3000/v1/urls', {
        headers: { Authorization: `Bearer ${data.idToken}` },
      });
    });
    sleep(1);

    group('update', function () {
      http.patch(
        `http://localhost:3000/v1/urls/${shortCode}`,
        JSON.stringify({ longUrl: 'https://example.com/updated' }),
        {
          headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${data.idToken}` },
        }
      );
    });
    sleep(1);

    group('delete', function () {
      http.del(`http://localhost:3000/v1/urls/${shortCode}`, null, {
        headers: { Authorization: `Bearer ${data.idToken}` },
      });
    });
  });

  sleep(2); // think-time before this VU's next full iteration
}
```
</details>

---

## Part 8 — Designing TyLink's Two Load Profiles

### 8.1 Realistic profile

Combine Parts 6 and 7: `ramping-vus` (models a daily traffic *shape* — ramp up, hold at
expected peak, ramp down) or `constant-arrival-rate` at your expected steady RPS, `sleep()`
between steps (7.3), thresholds set to N1 exactly (no slack — the point is pass/fail
against the real SLO, per `13-load-testing-fundamentals.md` §7). This is `03-testing.md`
step 1's "realistic" profile, and it's what the iterative scaling loop in
`02-scaling.md` step 5 re-runs after each technique.

### 8.2 Stress-to-failure profile

The opposite design goal (§7 in the fundamentals doc): no think-time, `ramping-arrival-rate`
climbing in stages until something breaks, `abortOnFail` so the run stops the instant it
does rather than continuing to burn load past the point that already answered the
question:

```js
export const options = {
  scenarios: {
    find_the_wall: {
      executor: 'ramping-arrival-rate',
      startRate: 10,
      timeUnit: '1s',
      preAllocatedVUs: 50,
      maxVUs: 500,
      stages: [
        { target: 50, duration: '1m' },
        { target: 100, duration: '1m' },
        { target: 200, duration: '1m' },
        { target: 400, duration: '1m' },
      ],
    },
  },
  thresholds: {
    http_req_duration: [{ threshold: 'p(99)<1000', abortOnFail: true }],
    http_req_failed: [{ threshold: 'rate<0.01', abortOnFail: true }],
  },
};
```

The RPS value where `abortOnFail` actually triggers **is** the measured capacity number
`03-testing.md` step 7 asks for ("handles X RPS at p99 < Yms") — the "knee" from
`13-load-testing-fundamentals.md` §7. Read it directly off which stage was executing
when k6 stopped the run, not off the final summary alone.

### Lab 8 — Build both profiles

Using Lab 6's combined scenario file as the base traffic mix, write a `find_the_wall`
variant: same three scenarios, but each converted to `ramping-arrival-rate` with
climbing stages and `abortOnFail` thresholds matching N1. Run it against
`sam local start-api` and note which scenario's threshold breaks first, and at what
stage/rate.

<details>
<summary>Solution sketch</summary>

Structurally: take each of Lab 6's three scenario blocks, swap `executor:
'constant-arrival-rate'` (or `ramping-vus`) for `ramping-arrival-rate` with an increasing
`stages` array per scenario, and add `abortOnFail: true` to each scenario-tagged
threshold from Part 4/6. Locally (via `sam local start-api`, single-container, no real
autoscaling) expect the wall to appear quickly and low — this lab's point is exercising
the *mechanics* of a stress profile, not producing a meaningful capacity number yet; that
comes once the same script runs against a real deployed stack in Lab 9.
</details>

---

## Part 9 — Running, Reading Output, and Correlating with AWS

### 9.1 CLI summary and structured output

`k6 run script.js` prints a text summary by default: per-metric min/avg/med/p90/p95/max,
threshold pass/fail, `checks` pass rate. For anything beyond eyeballing the terminal,
`--out json=results.json` (or `csv=results.csv`) writes every individual data point —
useful for later plotting, but overkill for this project's scale; a Grafana/InfluxDB
output pipeline exists (`--out influxdb=...`) but is out of scope for TyLink's budget
(N5) and isn't needed to satisfy `03-testing.md`'s "k6 output + correlated
CloudWatch/X-Ray screenshots" deliverable.

### 9.2 Correlating with CloudWatch/X-Ray

`03-testing.md` step 5 asks for CloudWatch dashboards + X-Ray traces **for the exact test
time window** — the metrics from Part 9 mean little to the actual investigation without
that. Practically: note the wall-clock start/end time before/after `k6 run`, then set
the CloudWatch dashboard's (`infrastructures/dashboards.yaml`) time range and the X-Ray
console's trace filter to that exact window before reading anything off them. Cross-check
k6's own `http_req_duration` (client-observed, includes network RTT to AWS) against
API Gateway's `Latency`/`IntegrationLatency` and Lambda's `Duration` (server-side,
per-component) for the same window — the general method for reading that gap is
`13-load-testing-fundamentals.md` §8; concretely here, X-Ray's service map is what
answers "where is the p99 spike actually coming from" (see `02-scaling.md` step 2's
framing).

### Lab 9 (capstone) — Run against the real stack

1. Run Lab 8's `find_the_wall` script against `sam local start-api` first (sanity check,
   free, fast).
2. Point the same script's base URL at the deployed stack's `HttpApiUrl` output and run
   it again.
3. Open the CloudWatch dashboards (`LambdaDurationDashboardUrl` stack output) and the
   X-Ray console, both filtered to the run's exact time window.
4. Write down: the measured capacity number (RPS at the point `abortOnFail` triggered, or
   the last passing stage if it never triggered), which scenario broke first, and what
   X-Ray's service map shows as the highest-latency node at that point — Lambda init,
   the DynamoDB call, or API Gateway overhead.

This is `03-testing.md` step 6–7's actual deliverable — do it once here as a dry run
before the real Phase 3 capstone report.

**Gotcha, if you poke at Lambda config by hand while debugging this against the real
stack**: `sam deploy` will **not** revert a manual console edit just because you
re-run it. CloudFormation change sets diff the *new template* against the
*last-deployed template* — not against the resource's live state — so if
`template.yaml` itself didn't change, the change set for that resource comes back
empty and CloudFormation leaves your manual edit alone. This is CloudFormation
**drift**: live state silently diverges from what the template says it should be, and
an unchanged-template redeploy can't detect or heal it.

Fix: revert the value directly (console, or `aws lambda update-function-configuration`
with the *full* desired `Variables` map — a partial update replaces the whole map, so
omitting a key deletes it). Confirm it's actually clean afterward, don't just assume:

```bash
DRIFT_ID=$(aws cloudformation detect-stack-drift --stack-name <stack> --query StackDriftDetectionId --output text)
aws cloudformation describe-stack-drift-detection-status --stack-drift-detection-id "$DRIFT_ID" \
  --query "[DetectionStatus,StackDriftStatus]" --output text   # want: DETECTION_COMPLETE  IN_SYNC
```

---

## Part 10 — Advanced: Beyond One Machine

A single k6 process is capped by one machine's CPU/network (the general concept is
`13-load-testing-fundamentals.md` §9) — fine for TyLink's expected scale, but worth
knowing what's past it:

- **k6 execution segments** (`--execution-segment`) split one test across multiple k6
  processes/machines, each running a slice of the same VUs — a way to scale k6 itself
  horizontally without changing the script.
- **AWS Distributed Load Testing** (Fargate-based, wraps k6 or other tools) — `03-testing.md`
  step 4 and `00-overview.md`'s stack table mark this as a one-time capstone only: real,
  bounded cost, not the default dev-loop tool. Deploy it once, reusing the exact script
  from Lab 9, to validate a claim at a scale a single local k6 process can't generate —
  then tear it down immediately. Local k6 stays the repeated iteration tool for
  `02-scaling.md`'s "apply one technique → re-run → compare" loop; this is only for the
  final, larger-scale confirmation run.

---

## Where This Connects

- `13-load-testing-fundamentals.md` — the theory each part above only briefly points
  back to; read it first if you haven't.
- `../plans/02-scaling.md` step 4 (baseline load test) and step 5 (the iterative
  scaling loop) both *run* the scripts this doc teaches you to build — this doc is the
  prerequisite skill, not a replacement for that process.
- `../plans/03-testing.md` is the spec these labs converge toward; its "Metrics to Watch
  and Correlate" table is exactly what Lab 9's step 3–4 reads.
- `../technical_decisions/11-emf-metrics.md` — the server-side custom metrics referenced
  in Part 7.1's cross-check.

## References

- k6 docs: https://k6.io/docs/ (also listed in `../plans/05-references.md`)
- k6 executors reference: https://k6.io/docs/using-k6/scenarios/executors/
- k6 thresholds reference: https://k6.io/docs/using-k6/thresholds/
- k6 `SharedArray`: https://k6.io/docs/javascript-api/k6-data/sharedarray/
