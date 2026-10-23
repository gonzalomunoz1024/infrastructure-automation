import { defineConfig, devices } from '@playwright/test'

// the container ships Chromium under /opt/pw-browsers; never run `playwright install` here.
// CI sets PLAYWRIGHT_BROWSERS_PATH itself before installing Chromium.
process.env.PLAYWRIGHT_BROWSERS_PATH ??= '/opt/pw-browsers'

// E2E_PORT serves the app under test on another port, so two checkouts can run their suites at once
// (never two runs in one checkout: they share test-results/)
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
    // the shell fades each page in; axe and the screenshots read the settled page, as a person with
    // reduced motion on sees it (Atlas honours prefers-reduced-motion for every animation)
    contextOptions: { reducedMotion: 'reduce' },
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
  },
  projects: [{ name: 'chromium', use: { ...devices['Desktop Chrome'] } }],
  webServer: {
    // mock mode: the same endpoints served from captured samples in the browser, no platform needed.
    // The journeys run against the built app (what people get: no on-demand transforms, no HMR),
    // served by vite preview; E2E_DEV=1 drives the dev server instead, for debugging with HMR.
    command: process.env.E2E_DEV
      ? `npm run dev:mock -- --port ${port} --strictPort`
      : `npm run build:mock && npm run preview:mock -- --port ${port} --strictPort`,
    url: baseURL,
    reuseExistingServer: !process.env.CI,
    timeout: 180_000,
  },
})
