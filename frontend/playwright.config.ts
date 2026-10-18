import { defineConfig, devices } from '@playwright/test'

// the container ships Chromium under /opt/pw-browsers; never run `playwright install`
process.env.PLAYWRIGHT_BROWSERS_PATH ??= '/opt/pw-browsers'

// E2E_PORT serves the app under test on another port, so two checkouts can run their suites at once
const port = Number(process.env.E2E_PORT ?? 3000)
const baseURL = `http://localhost:${port}`

export default defineConfig({
  testDir: './e2e',
  timeout: 30_000,
  fullyParallel: true,
  retries: process.env.CI ? 1 : 0,
  reporter: process.env.CI ? 'github' : 'list',
  use: {
    baseURL,
    trace: 'on-first-retry',
  },
  projects: [{ name: 'chromium', use: { ...devices['Desktop Chrome'] } }],
  webServer: {
    command: `npm run dev -- --port ${port} --strictPort`,
    url: baseURL,
    reuseExistingServer: !process.env.CI,
    timeout: 60_000,
  },
})
