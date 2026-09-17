import { defineConfig } from '@playwright/test';
export default defineConfig({
  testDir: './tests/ui', timeout: 90000, fullyParallel: false, workers: 1,
  reporter: 'list', use: { baseURL: process.env.DEMO_URL || 'http://localhost:8090', viewport: { width: 1512, height: 1100 }, screenshot: 'only-on-failure', trace: 'retain-on-failure' }
});
