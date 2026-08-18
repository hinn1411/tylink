import { check, sleep } from 'k6';
import http from 'k6/http';
import { SharedArray } from 'k6/data';

export const BASE_URL = (__ENV.BASE_URL || 'http://localhost:3000').replace(/\/$/, '');

export const coldCodes = new SharedArray('ShortCodes', () =>
  JSON.parse(open('./short-codes.json')));

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
  const result = http.get(`${BASE_URL}/v1/urls/${hotCode}`, {
    redirects: 0,
    tags: { scenario: 'hot' },
  });
  check(result, {
    'hotRedirect status is 307': r => r.status === 307,
  });
}

export function coldRedirect() {
  const coldCode = coldCodes[Math.floor(Math.random() * coldCodes.length)];
  const result = http.get(`${BASE_URL}/v1/urls/${coldCode}`, {
    redirects: 0,
    tags: { scenario: 'cold' },
  });
  check(result, {
    'coldRedirect status is 307': r => r.status === 307,
  });
}

function createUrl(data) {
  const body = JSON.stringify({
    longUrl: 'http://mock.com.vn',
    visibility: 'PRIVATE',
  });
  const result = http.post(`${BASE_URL}/v1/urls`, body, {
    headers: {
      'Content-Type': 'application/json',
      'Idempotency-Key': crypto.randomUUID(),
      Authorization: `Bearer ${data.idToken}`,
    },
    tags: { scenario: 'crud' },
  });
  check(result, {
    'crud create status is 201': r => r.status === 201,
  });
  return result.json('shortCode');
}

function listUrls(data) {
  const result = http.get(`${BASE_URL}/v1/urls`, {
    headers: { Authorization: `Bearer ${data.idToken}` },
    tags: { scenario: 'crud' },
  });
  check(result, {
    'crud list status is 200': r => r.status === 200,
  });
}

function updateUrl(data, shortCode) {
  const body = JSON.stringify({ longUrl: 'http://mock.com.vn/updated' });
  const result = http.patch(`${BASE_URL}/v1/urls/${shortCode}`, body, {
    headers: {
      'Content-Type': 'application/json',
      Authorization: `Bearer ${data.idToken}`,
    },
    tags: { scenario: 'crud' },
  });
  check(result, {
    'crud update status is 200': r => r.status === 200,
  });
}

function deleteUrl(data, shortCode) {
  const result = http.del(`${BASE_URL}/v1/urls/${shortCode}`, null, {
    headers: { Authorization: `Bearer ${data.idToken}` },
    tags: { scenario: 'crud' },
  });
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
