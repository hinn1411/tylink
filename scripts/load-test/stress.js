import {
  setup as sharedSetup,
  teardown as sharedTeardown,
  hotRedirect as sharedHotRedirect,
  coldRedirect as sharedColdRedirect,
  crudLifecycle,
} from './common/lib.js';

// Stress-to-failure profile (docs/plans/03-testing.md step 1): climbs load until the SLO
// breaks, then stops — the stage it was on when it aborts is the measured capacity.
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
  crudLifecycle(data, 0);
}
