import { test, expect } from '@playwright/test';
import { readFileSync } from 'node:fs';

const resources = new URL('../../agent-runtime/src/main/resources/META-INF/resources/', import.meta.url);
const token = 'temporary-browser-session-with-32-characters';

async function mockApp(page, { enabled = true, unavailable = false } = {}) {
  const requests = [];
  let issued = 0;
  await page.route('http://localhost:8090/**', async route => {
    const request = route.request();
    const pathname = new URL(request.url()).pathname;
    requests.push({ pathname, headers: request.headers() });
    if (pathname === '/local-session') {
      issued++;
      return route.fulfill({ status: enabled ? 200 : 404,
        json: enabled ? { token: `${token}-${issued}` } : { error: 'Local connection is disabled' } });
    }
    if (pathname.startsWith('/api')) {
      if (unavailable) return route.fulfill({ status: 503, json: { error: 'Database unavailable' } });
      if (!request.headers().authorization) return route.fulfill({ status: 401, json: {} });
      if (pathname === '/api/status') return route.fulfill({ json: {
        model: 'test-model', modelReady: true, database: true, gateway: 'Local policy simulator'
      } });
      return route.fulfill({ json: [] });
    }
    const asset = pathname === '/' ? 'index.html' : pathname.slice(1);
    if (!['index.html', 'app.js', 'app.css'].includes(asset)) return route.fulfill({ status: 404 });
    return route.fulfill({ body: readFileSync(new URL(asset, resources)),
      contentType: asset.endsWith('.js') ? 'text/javascript' : asset.endsWith('.css') ? 'text/css' : 'text/html' });
  });
  return requests;
}

test('local demo connects without a key prompt and reconnects after reload', async ({ page }) => {
  const requests = await mockApp(page);
  await page.goto('/');
  await expect(page.locator('#connection')).toContainText('Connected');
  await expect(page.locator('#access-dialog')).not.toBeVisible();
  expect(requests.find(r => r.pathname === '/local-session').headers['x-runway-local']).toBe('1');
  expect(requests.find(r => r.pathname === '/api/status').headers.authorization).toBe(`Bearer ${token}-1`);
  expect(await page.evaluate(() => [localStorage.length, sessionStorage.length])).toEqual([0, 0]);
  await page.reload();
  await expect(page.locator('#connection')).toContainText('Connected');
  await expect(page.locator('#access-dialog')).not.toBeVisible();
  expect(requests.filter(r => r.pathname === '/api/status').at(-1).headers.authorization).toBe(`Bearer ${token}-2`);
});

test('deployed mode retains manual presenter authentication', async ({ page }) => {
  const requests = await mockApp(page, { enabled: false });
  await page.goto('/');
  await expect(page.locator('#access-dialog')).toBeVisible();
  expect(requests.some(r => r.pathname.startsWith('/api'))).toBe(false);
  await page.getByLabel('PRESENTER ACCESS KEY', { exact: true }).fill('manual-presenter-key-with-32-characters');
  await page.getByRole('button', { name: 'Connect →', exact: true }).click();
  await expect(page.locator('#access-dialog')).not.toBeVisible();
  expect(requests.find(r => r.pathname === '/api/status').headers.authorization)
    .toBe('Bearer manual-presenter-key-with-32-characters');
});

test('backend failure stays visible instead of claiming a connection', async ({ page }) => {
  await mockApp(page, { unavailable: true });
  await page.goto('/');
  await expect(page.locator('#access-dialog')).toBeVisible();
  await expect(page.locator('#access-error')).toContainText('Database unavailable');
  await expect(page.locator('#connection')).not.toContainText('Connected');
});

test('an expired local session renews once before retrying the API', async ({ page }) => {
  const requests = await mockApp(page);
  await page.goto('/');
  await expect(page.locator('#connection')).toContainText('Connected');
  let auditCalls = 0;
  await page.route('**/api/audit', route => {
    auditCalls++;
    return route.fulfill({ status: auditCalls === 1 ? 401 : 200,
      json: auditCalls === 1 ? { error: 'Session expired' } : [] });
  });
  await page.locator('.sidebar [data-view="secure"]').click();
  await expect(page.locator('#audit-body')).toContainText('No gateway requests yet');
  expect(auditCalls).toBe(2);
  expect(requests.filter(r => r.pathname === '/local-session')).toHaveLength(2);
  await expect(page.locator('#access-dialog')).not.toBeVisible();
});
