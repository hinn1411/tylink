import { check, sleep } from 'k6';
import http from 'k6/http';
import { Counter } from 'k6/metrics';
import { SharedArray } from 'k6/data';

const RETRY_BASE_DELAY_MS = 100;
const RETRY_MAX_DELAY_MS = 2000;
const RETRY_MAX_ATTEMPTS = 5; // 1 initial + 4 retries

// Surfaces a per-scenario 429 count in k6's own summary without post-processing raw JSON.
export const throttledResponses = new Counter('throttled_responses');

// Retries only on 429 (API-Gateway route throttle — no handler in this codebase ever returns
// 429 itself, so it's unambiguous). Full-jitter exponential backoff:
// sleep = random(0, min(cap, base*2^n)). Every other status — including expected app-level
// ones like 410/404 — returns immediately, untouched.
function requestWithRetry(makeRequest, tags) {
  let attempt = 0;
  let result;
  for (;;) {
    result = makeRequest();
    if (result.status === 429) {
      throttledResponses.add(1, tags);
    }
    if (result.status !== 429 || attempt >= RETRY_MAX_ATTEMPTS - 1) {
      return result;
    }
    const cappedDelayMs = Math.min(RETRY_MAX_DELAY_MS, RETRY_BASE_DELAY_MS * 2 ** attempt);
    sleep((Math.random() * cappedDelayMs) / 1000);
    attempt++;
  }
}

export const BASE_URL = (__ENV.BASE_URL || 'http://localhost:3000').replace(/\/$/, '');

export const coldCodes = new SharedArray('ShortCodes', () =>
  JSON.parse(open('../data/short-codes.json')));

export const hotCodes = coldCodes.slice(0, 3);

export function setup() {
  console.log(`load-test start: ${new Date().toISOString()}`);

  const body = JSON.stringify({
    username: __ENV.USERNAME,
    password: __ENV.PASSWORD,
  });
  const authResult = http.post(`${BASE_URL}/v1/auth/login`, body, {
    headers: { 'Content-Type': 'application/json' },
  });
  if (authResult.status !== 200) {
    throw new Error(`setup login failed: ${authResult.status} ${authResult.body}`);
  }
  return {
    idToken: authResult.json('idToken'),
  };
}

export function teardown() {
  console.log(`load-test end: ${new Date().toISOString()}`);
}

export function hotRedirect() {
  const hotCode = hotCodes[Math.floor(Math.random() * hotCodes.length)];
  const result = requestWithRetry(() => http.get(`${BASE_URL}/v1/urls/${hotCode}`, {
    redirects: 0,
    tags: { scenario: 'hot' },
  }), { scenario: 'hot' });
  check(result, {
    'hotRedirect status is 307': r => r.status === 307,
  });
}

export function coldRedirect() {
  const coldCode = coldCodes[Math.floor(Math.random() * coldCodes.length)];
  const result = requestWithRetry(() => http.get(`${BASE_URL}/v1/urls/${coldCode}`, {
    redirects: 0,
    tags: { scenario: 'cold' },
  }), { scenario: 'cold' });
  check(result, {
    'coldRedirect status is 307': r => r.status === 307,
  });
}

function createUrl(data) {
  const body = JSON.stringify({
    longUrl: 'http://mock.com.vn',
    visibility: 'PRIVATE',
  });
  // Generated once, outside the retry closure, so retries of one logical create reuse the same
  // key — see docs/technical_decisions/16-throttling-backpressure.md.
  const idempotencyKey = crypto.randomUUID();
  const result = requestWithRetry(() => http.post(`${BASE_URL}/v1/urls`, body, {
    headers: {
      'Content-Type': 'application/json',
      'Idempotency-Key': idempotencyKey,
      Authorization: `Bearer ${data.idToken}`,
    },
    tags: { scenario: 'crud' },
  }), { scenario: 'crud' });
  check(result, {
    'crud create status is 201': r => r.status === 201,
  });
  return result.json('shortCode');
}

function listUrls(data) {
  const result = requestWithRetry(() => http.get(`${BASE_URL}/v1/urls`, {
    headers: { Authorization: `Bearer ${data.idToken}` },
    tags: { scenario: 'crud' },
  }), { scenario: 'crud' });
  check(result, {
    'crud list status is 200': r => r.status === 200,
  });
}

function updateUrl(data, shortCode) {
  const body = JSON.stringify({ longUrl: 'http://mock.com.vn/updated' });
  const result = requestWithRetry(() => http.patch(`${BASE_URL}/v1/urls/${shortCode}`, body, {
    headers: {
      'Content-Type': 'application/json',
      Authorization: `Bearer ${data.idToken}`,
    },
    tags: { scenario: 'crud' },
  }), { scenario: 'crud' });
  check(result, {
    'crud update status is 200': r => r.status === 200,
  });
}

function deleteUrl(data, shortCode) {
  const result = requestWithRetry(() => http.del(`${BASE_URL}/v1/urls/${shortCode}`, null, {
    headers: { Authorization: `Bearer ${data.idToken}` },
    tags: { scenario: 'crud' },
  }), { scenario: 'crud' });
  check(result, {
    'crud delete status is 410': r => r.status === 410,
  });
}

export function crudLifecycle(data, thinkTimeSeconds) {
  const shortCode = createUrl(data);
  sleep(thinkTimeSeconds);

  listUrls(data);
  sleep(thinkTimeSeconds);

  updateUrl(data, shortCode);
  sleep(thinkTimeSeconds);

  deleteUrl(data, shortCode);
}
