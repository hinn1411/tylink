import {
  setup as sharedSetup,
  teardown as sharedTeardown,
  hotRedirect as sharedHotRedirect,
  coldRedirect as sharedColdRedirect,
  crudLifecycle,
} from './lib.js';

// Realistic traffic profile (docs/plans/03-testing.md step 1, k6 doc Part 8.1): steady
// declared rate for redirects, ramping concurrency for the CRUD flow, N1 thresholds with
// no slack and no abortOnFail — this measures pass/fail against the real SLO, it doesn't
// stop early.
export const options = {
  scenarios: {
    hot_key_redirect: {
      executor: 'constant-arrival-rate',
      exec: 'hotRedirect',
      rate: 30,
      timeUnit: '1s',
      duration: '1m',
      preAllocatedVUs: 20,
      maxVUs: 60,
      tags: { scenario: 'hot' },
    },
    cold_key_redirect: {
      executor: 'constant-arrival-rate',
      exec: 'coldRedirect',
      rate: 30,
      timeUnit: '1s',
      duration: '1m',
      preAllocatedVUs: 20,
      maxVUs: 60,
      tags: { scenario: 'cold' },
    },
    auth_crud: {
      executor: 'ramping-vus',
      exec: 'authCrud',
      stages: [{ duration: '2m', target: 5 }],
      tags: { scenario: 'crud' },
    },
  },
  thresholds: {
    'http_req_duration{scenario:hot}': ['p(99)<1000'],
    'http_req_duration{scenario:cold}': ['p(99)<1000'],
    'http_req_duration{scenario:crud}': ['p(99)<1000'],
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
  crudLifecycle(data, 1);
}
