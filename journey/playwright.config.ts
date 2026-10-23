import { defineConfig, devices } from '@playwright/test'
import { CONSOLE_URL, ROOT } from './support/stack.js'
import { join } from 'node:path'

/*
 * The full-system journey against the stack ./e2e.sh brought up (docker-compose.yml). One
 * worker, in order: each step builds on the one before. Every step keeps its trace; a failing
 * one also keeps a screenshot and a video. The HTML report and the results land under
 * artifacts/ next to the containers' logs.
 */
const artifacts = process.env.E2E_ARTIFACTS ?? join(ROOT, 'artifacts')

export default defineConfig({
  testDir: './tests',
  globalSetup: './global-setup.ts',
  fullyParallel: false,
  workers: 1,
  retries: 0,
  forbidOnly: !!process.env.CI,
  timeout: 180_000,
  expect: { timeout: 20_000 },
  outputDir: join(artifacts, 'test-results'),
  reporter: [
    ['list'],
    ['html', { outputFolder: join(artifacts, 'playwright-report'), open: 'never' }],
    ['junit', { outputFile: join(artifacts, 'junit.xml') }],
  ],
  use: {
    ...devices['Desktop Chrome'],
    baseURL: CONSOLE_URL,
    viewport: { width: 1440, height: 900 },
    trace: 'on',
    screenshot: 'only-on-failure',
    video: 'retain-on-failure',
    contextOptions: { reducedMotion: 'reduce' },
    actionTimeout: 15_000,
    navigationTimeout: 30_000,
    launchOptions: {
      // the browser reaches Dex at http://dex:5556, the issuer the BFF knows it by
      args: ['--host-resolver-rules=MAP dex 127.0.0.1'],
    },
  },
})
