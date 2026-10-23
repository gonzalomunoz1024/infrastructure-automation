import { expect, type Page } from '@playwright/test'
import { CONSOLE_URL, DEV_PASSWORD, type Person } from './stack.js'

/*
 * Sign-in as a person does it: the console's sign-in page, "Continue with company SSO", the BFF's
 * /auth/login, Dex's login form (config/dex/config.yaml), the BFF's /auth/callback, and back on
 * the console with the session cookie set. The browser reaches Dex as http://dex:5556 like the
 * BFF does (playwright.config.ts maps the host name to 127.0.0.1).
 */
export async function signIn(page: Page, person: Person, next = '/'): Promise<void> {
  await page.goto(`${CONSOLE_URL}/#${next}`)
  await page.getByRole('button', { name: 'Continue with company SSO' }).click()
  await page.waitForURL(/^http:\/\/dex:5556\/dex\/auth\//)
  await page.locator('input[name="login"]').fill(person.email)
  await page.locator('input[name="password"]').fill(DEV_PASSWORD)
  await page.locator('button[type="submit"]').click()
  await page.waitForURL((url) => url.origin === new URL(CONSOLE_URL).origin && !url.pathname.startsWith('/auth/'))
  // the session is the BFF's: read it as the console does
  await expect
    .poll(async () => (await page.request.get(`${CONSOLE_URL}/console/v1/session`)).status(), { message: `a session for ${person.email}` })
    .toBe(200)
}
