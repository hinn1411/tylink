# Load Testing

k6 harness for `docs/plans/03-testing.md`: two load profiles exercising three separately
tagged traffic types — hot-key redirect, cold-key redirect, auth+CRUD — against N1's
latency SLO (p99 < 1000ms, per
`docs/learning/14-load-testing-with-k6.md` Part 4.1/8.1).

- `lib.js` — shared setup/login, redirect, and CRUD-lifecycle logic. Not run directly.
- `realistic.js` — steady declared load, N1 thresholds, no `abortOnFail`. Reports
  pass/fail against the SLO without stopping early.
- `stress.js` — climbing load with `abortOnFail`, stops the instant the SLO breaks. The
  stage k6 was executing when it aborts is the measured capacity number.
- `seed-short-codes.sh` — seeds short codes and writes `short-codes.json`, which both
  profiles load via `SharedArray`.

## Prerequisites

A registered test user (`../auth/register-test-user.sh` if you don't have one) and a
seeded `short-codes.json`:

```bash
BASE_URL=http://localhost:3000 ./seed-short-codes.sh          # against sam local start-api
BASE_URL=https://<api-id>.execute-api.<region>.amazonaws.com ./seed-short-codes.sh  # against a deployed stack
```

`short-codes.json` is gitignored — it's tied to whichever `BASE_URL` seeded it and is
meaningless against a different environment. Re-seed after switching targets.

## Running

```bash
k6 run -e USERNAME=... -e PASSWORD=... -e BASE_URL=http://localhost:3000 realistic.js
k6 run -e USERNAME=... -e PASSWORD=... -e BASE_URL=http://localhost:3000 stress.js
```

Omit `-e BASE_URL` to default to `http://localhost:3000`. Point it at the deployed
stack's `HttpApiUrl` output (`sam list stack-outputs --stack-name <stack>`) for the real
capstone run described in `docs/learning/14-load-testing-with-k6.md` Lab 9.

**`stress.js` drives real load** — up to ~400 req/s per scenario (hot/cold/crud) by its
final stage. Pointed at the deployed stack, that's real Lambda invocations, real DynamoDB
capacity, and will very likely trip the CloudWatch alarms added on this branch. Run it
against the deployed endpoint deliberately, not by habit — `realistic.js` is the one to
reach for by default.

## Correlating with CloudWatch/X-Ray

Both scripts log an ISO start timestamp (in `setup()`) and end timestamp (in
`teardown()`) to stdout — use that window verbatim as the CloudWatch dashboard time range
and X-Ray trace filter, per `docs/plans/03-testing.md` step 5.
