import { test, expect } from '@playwright/test';
import { readFileSync } from 'node:fs';
const key = process.env.DEMO_API_KEY || readFileSync('.env', 'utf8').split('\n').find((line) => line.startsWith('DEMO_API_KEY=')).split('=')[1];
async function connect(page) {
  await page.goto('/');
  await expect.poll(async () =>
    (await page.locator('#connection').innerText()).startsWith('Connected') ||
    await page.locator('#access-dialog').isVisible()).toBe(true);
  if (await page.locator('#access-dialog').isVisible()) {
    await page.getByLabel('PRESENTER ACCESS KEY', { exact: true }).fill(key);
    await page.getByRole('button', { name: 'Connect →', exact: true }).click();
  }
  await expect(page.locator('#access-dialog')).not.toBeVisible();
}
test('blueprint, actual gateway probes, rehearsal approval and persisted history', async ({ page }) => {
  const errors = []; page.on('pageerror', (e) => errors.push(e.message));
  await connect(page);
  await page.getByRole('button', { name: 'Build blueprint' }).click();
  await expect(page.locator('#artifact-panel')).toBeVisible();
  await expect(page.locator('#artifact-code')).toContainText('human approval required');
  await expect(page.locator('#toast')).toBeHidden({ timeout: 8000 });
  await page.screenshot({ path: 'test-results/runway-desktop.png', fullPage: true });
  await page.getByRole('button', { name: 'Secure the path →', exact: true }).click();
  for (const [button, code] of [['Test 401 ↗', '401'], ['Test 403 ↗', '403'], ['Test 400 ↗', '400']]) {
    await page.getByRole('button', { name: button, exact: true }).click();
    await expect(page.locator('#probe-result')).toContainText(`Policy verified · HTTP ${code}`);
  }
  await page.getByRole('button', { name: 'Let agents work →', exact: true }).click();
  await page.getByRole('button', { name: 'Rehearsal', exact: true }).click();
  await page.getByRole('button', { name: 'Start investigation' }).click();
  await expect(page.locator('#run-status')).toHaveText('AWAITING APPROVAL', { timeout: 30000 });
  await expect(page.locator('#report')).toContainText('REHEARSAL');
  await expect(page.locator('#trace')).toContainText('get_service_metrics');
  await page.screenshot({ path: 'test-results/runway-execution.png', fullPage: true });
  await page.getByRole('button', { name: 'Approve follow-up →', exact: true }).click();
  await expect(page.locator('#approval-result')).toContainText('No infrastructure was changed.');
  await page.locator('.sidebar').getByRole('button', { name: 'Execution history' }).click();
  await expect(page.locator('#history-body')).toContainText('APPROVED');
  expect(errors).toEqual([]);
});
test('mobile layout and navigation remain usable', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await connect(page);
  await expect(page.locator('h1')).toContainText('Cleared for takeoff.');
  const overflowing = await page.evaluate(() => document.documentElement.scrollWidth > window.innerWidth);
  expect(overflowing).toBe(false);
  await page.locator('.mobile-nav').getByRole('button', { name: 'Execute' }).click();
  await expect(page.locator('#view-execute')).toBeVisible();
  await expect(page.locator('#toast')).toBeHidden({ timeout: 8000 });
  await page.screenshot({ path: 'test-results/runway-mobile.png', fullPage: true });
});
