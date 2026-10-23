import { defineConfig, devices } from '@playwright/test'

// the container ships Chromium under /opt/pw-browsers; never run `playwright install` here.
// CI sets PLAYWRIGHT_BROWSERS_PATH itself before installing Chromium.
process.env.PLAYWRIGHT_BROWSERS_PATH ??= '/opt/pw-browsers'

// E2E_PORT serves the app under test on another port, so two checkouts can run their suites at once
const port = Number(process.env.E2E_PORT ?? 3000)
const baseURL = `http://localhost:${port}`

export default defineConfig({
  testDir: './e2e',
  timeout: 60_000,
  expect: { timeout: 10_000 },
  // one browser at a time: the machine is shared
  fullyParallel: false,
  workers: 1,
  retries: process.env.CI ? 1 : 0,
  reporter: process.env.CI ? [['github'], ['html', { open: 'never' }]] : 'list',
  use: {
    baseURL,
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
  },
  projects: [{ name: 'chromium', use: { ...devices['Desktop Chrome'] } }],
  webServer: {
    // mock mode: the same endpoints served from captured samples in the browser, no platform needed
    command: `npm run dev:mock -- --port ${port} --strictPort`,
    url: baseURL,
    reuseExistingServer: !process.env.CI,
    timeout: 90_000,
  },
})
