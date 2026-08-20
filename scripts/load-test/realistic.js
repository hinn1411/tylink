import {
  setup as sharedSetup,
  teardown as sharedTeardown,
  hotRedirect as sharedHotRedirect,
  coldRedirect as sharedColdRedirect,
  crudLifecycle,
} from './common/lib.js';

// Realistic traffic profile (docs/plans/03-testing.md step 1): steady load against the
// SLO, reporting pass/fail without stopping early.
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
    // Non-failing — only here to force a per-scenario 429 breakdown into the summary.
    'throttled_responses{scenario:hot}': [{ threshold: 'count>=0', abortOnFail: false }],
    'throttled_responses{scenario:cold}': [{ threshold: 'count>=0', abortOnFail: false }],
    'throttled_responses{scenario:crud}': [{ threshold: 'count>=0', abortOnFail: false }],
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
