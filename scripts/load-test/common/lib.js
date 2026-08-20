import { check, sleep } from 'k6';
import http from 'k6/http';
import { Counter } from 'k6/metrics';
import { SharedArray } from 'k6/data';

const RETRY_BASE_DELAY_MS = 100;
const RETRY_MAX_DELAY_MS = 2000;
const RETRY_MAX_ATTEMPTS = 5; // 1 initial + 4 retries
const RETRY_DEADLINE_MS = 5000;

export const throttledResponses = new Counter('throttled_responses');

function requestWithRetry(makeRequest, tags) {
  const startTime = Date.now();
  let attempt = 0;
  let result;
  let canRetry;
  do {
    result = makeRequest();
    if (result.status === 429) {
      throttledResponses.add(1, tags);
    }
    canRetry = shouldRetry(result.status, attempt, startTime);
    if (canRetry) {
      const backoffDelay = RETRY_BASE_DELAY_MS * 2 ** attempt
      const cappedDelay = Math.min(RETRY_MAX_DELAY_MS, backoffDelay);
      const jitter = (Math.random() * cappedDelay) / 1000
      sleep(jitter);
      attempt++;
    }
  } while (canRetry);
  return result;
}

function shouldRetry(status, attempt, startTime) {
  return isRetryableStatus(status)
    && attempt < RETRY_MAX_ATTEMPTS - 1
    && Date.now() - startTime < RETRY_DEADLINE_MS;
}

function isRetryableStatus(status) {
  return status === 429 || status >= 500;
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
