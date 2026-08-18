# Load Testing

k6 harness for `docs/plans/03-load-testing.md`, testing redirect (hot/cold) and auth+CRUD traffic
against N1's SLO (p99 < 1000ms).

- `common/lib.js` — shared setup/login and traffic logic. Not run directly.
- `realistic.js` — steady load; reports pass/fail without stopping early.
- `stress.js` — climbing load; stops the instant the SLO breaks, revealing measured capacity.
- `data/seed-short-codes.sh` — seeds short codes into `data/short-codes.json` for both scripts.

## Prerequisites

A registered test user (`../auth/register-test-user.sh` if you don't have one) and seeded
short codes:

```bash
BASE_URL=http://localhost:3000 ./data/seed-short-codes.sh          # against sam local start-api
BASE_URL=https://<api-id>.execute-api.<region>.amazonaws.com ./data/seed-short-codes.sh  # against a deployed stack
```

`data/short-codes.json` is gitignored — re-seed after switching targets.

## Running

```bash
k6 run -e USERNAME=... -e PASSWORD=... -e BASE_URL=http://localhost:3000 realistic.js
k6 run -e USERNAME=... -e PASSWORD=... -e BASE_URL=http://localhost:3000 stress.js
```

Omit `-e BASE_URL` to default to `http://localhost:3000`, or point it at a deployed stack's
`HttpApiUrl`. Default to `realistic.js` — `stress.js` drives real load (up to ~400 req/s) and
will likely trip CloudWatch alarms if run against a deployed stack.

## Correlating with CloudWatch/X-Ray

Both scripts log a start/end timestamp to stdout — use that window as the CloudWatch/X-Ray
time range.
