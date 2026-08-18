import {
  setup as sharedSetup,
  teardown as sharedTeardown,
  hotRedirect as sharedHotRedirect,
  coldRedirect as sharedColdRedirect,
  crudLifecycle,
} from './common/lib.js';

// Stress-to-failure / "find the wall" profile (docs/plans/03-testing.md step 1, k6 doc
// Part 8.2): no think-time, climbing arrival rate on all three traffic types, N1
// thresholds with abortOnFail so the run stops the instant the SLO breaks instead of
// burning the full climb past the point that already answered the question. Read the
// measured capacity off which stage was executing when k6 aborts.
export const options = {
  scenarios: {
    hot_key_redirect: {
      executor: 'ramping-arrival-rate',
      exec: 'hotRedirect',
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
      tags: { scenario: 'hot' },
    },
    cold_key_redirect: {
      executor: 'ramping-arrival-rate',
      exec: 'coldRedirect',
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
      tags: { scenario: 'cold' },
    },
    auth_crud: {
      executor: 'ramping-arrival-rate',
      exec: 'authCrud',
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
      tags: { scenario: 'crud' },
    },
  },
  thresholds: {
    'http_req_duration{scenario:hot}': [{ threshold: 'p(99)<1000', abortOnFail: true }],
    'http_req_duration{scenario:cold}': [{ threshold: 'p(99)<1000', abortOnFail: true }],
    'http_req_duration{scenario:crud}': [{ threshold: 'p(99)<1000', abortOnFail: true }],
  },
};

export const setup = sharedSetup;
export const teardown = sharedTeardown;

export function hotRedirect() {
  sharedHotRedirect();
}

export function coldRedirect() {
  sharedColdRedirect();
}

export function authCrud(data) {
  crudLifecycle(data, 0);
}
