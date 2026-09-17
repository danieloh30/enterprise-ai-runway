import { defineConfig } from '@playwright/test';
export default defineConfig({
  testDir: './tests/access', timeout: 15000, workers: 1, reporter: 'list',
  use: { baseURL: 'http://localhost:8090', screenshot: 'only-on-failure' }
});
