import { fileURLToPath } from 'node:url'
import { expect, test, type Page } from '@playwright/test'
import { expectNoHorizontalOverflow, expectNoSeriousA11y, signInAs } from './support'

/*
 * Phase 7 and 9, in mock mode: moving a resource to another account (WP 7.7) and the phone view
 * (WP 9.18). Dana moves a VM to the Data account after a fresh sign-in, lands on it under its new
 * wfrn, and the old link says where it went; a move of a machine a request is changing is refused
 * in words; Gonzalo approves an access request on a 390 px phone, and reads drift and reverts a
 * finding from the phone view at 360 px. Every page and dialog is checked with axe, and the phone
 * pages at their width for sideways scrolling.
 */

const shots = fileURLToPath(new URL('./screenshots/', import.meta.url))
const shot = (name: string) => `${shots}${name}.png`

const COMPUTE = '123456789012'
const DATA = '210987654321'
const wfrnOf = (id: string, account = COMPUTE) => `wfrn:wf:compute:global:${account}:vm/${id}`
const vmPage = (id: string, account = COMPUTE) => `/console/compute/vm/${encodeURIComponent(wfrnOf(id, account))}`

/** A call to the mock BFF from inside the page, as another tab of the same person would make it. */
async function call(page: Page, method: string, url: string, body?: unknown): Promise<number> {
  return page.evaluate(
    async ({ method, url, body }) => {
      const session = (await (await fetch('/console/v1/session')).json()) as { csrfToken: string }
      const res = await fetch(url, { method, headers: { 'Content-Type': 'application/json', 'X-Csrf-Token': session.csrfToken, 'Idempotency-Key': crypto.randomUUID() }, body: body === undefined ? undefined : JSON.stringify(body) })
      return res.status
    },
    { method, url, body },
  )
}

async function signOut(page: Page) {
  await page.goto('/#/settings')
  await page.getByRole('button', { name: 'Sign out' }).click()
  await expect(page).toHaveURL(/#\/signin/)
}

/** Gonzalo, an admin of both accounts, adds Dana to Data's members, so she may create machines there. */
async function danaJoinsData(page: Page) {
  await signInAs(page, /Continue as Gonzalo Munoz/, '/')
  expect(await call(page, 'PUT', '/console/v1/session', { accountId: DATA })).toBe(200)
  expect(await call(page, 'POST', '/console/v1/iam/users', { userId: 'dana', groups: ['members'] })).toBeLessThan(300)
  expect(await call(page, 'PUT', '/console/v1/session', { accountId: COMPUTE })).toBe(200)
  await signOut(page)
}

async function openMove(page: Page, name: string) {
  await page.getByRole('button', { name: 'Actions', exact: true }).click()
  await page.getByRole('menu', { name: `Actions for ${name}` }).getByRole('menuitem', { name: /Move to another account/ }).click()
  const drawer = page.getByRole('dialog', { name: `Move to another account: ${name}` })
  await expect(drawer).toBeVisible()
  await expect(drawer.locator('[data-target]')).toHaveCount(1)
  return drawer
}

test.describe('moving a resource and the phone view in mock mode', () => {
  test('Dana moves a VM to the Data account after a fresh sign-in, and the old link says where it went', async ({ page }) => {
    test.setTimeout(90_000)
    await danaJoinsData(page)
    await signInAs(page, /Continue as Dana Reyes/, vmPage('vm-gen-005'))
    await expect(page.getByRole('heading', { name: 'etl-015', level: 1 })).toBeVisible()
    // her sign-in is twenty minutes old: older than a move allows
    await page.evaluate(() => {
      const s = JSON.parse(localStorage.getItem('console-mock-session') ?? '{}') as { authTime: string }
      s.authTime = new Date(Date.now() - 20 * 60_000).toISOString()
      localStorage.setItem('console-mock-session', JSON.stringify(s))
    })
    await page.reload()
    await expect(page.getByRole('heading', { name: 'etl-015', level: 1 })).toBeVisible()

    const drawer = await openMove(page, 'etl-015')
    await expect(drawer.getByRole('heading', { name: 'What moves' })).toBeVisible()
    await expect(drawer.getByRole('heading', { name: 'What does not' })).toBeVisible()
    // Move waits for an account and a reason, and says so
    await drawer.getByRole('button', { name: 'Move', exact: true }).click()
    await expect(drawer.getByText('Pick the account to move it to.')).toBeVisible()
    await drawer.getByRole('radio', { name: /Data/ }).check()
    await drawer.getByLabel(/Why it moves/).fill('The data team runs the nightly loads now')
    await page.screenshot({ path: shot('transfer-drawer-desktop'), fullPage: true })
    await expectNoSeriousA11y(page, 'move drawer')

    const confirmMove = async () => {
      await drawer.getByRole('button', { name: 'Move to Data' }).click()
      const confirm = page.getByRole('dialog', { name: /Move etl-015 to Data/ })
      await expect(confirm).toBeVisible()
      await expect(confirm.getByRole('button', { name: 'Move it' })).toBeDisabled()
      await confirm.getByLabel(/Type etl-015 to confirm/).fill('etl-015')
      return confirm
    }
    const first = await confirmMove()
    await expectNoSeriousA11y(page, 'move confirmation')
    await first.getByRole('button', { name: 'Move it' }).click()

    // the BFF asks for a fresh sign-in; what she filled in is kept
    const stepUp = drawer.locator('[data-step-up]')
    await expect(stepUp).toBeVisible()
    await expect(stepUp).toContainText('Sign in again to move it')
    await page.screenshot({ path: shot('transfer-step-up-desktop'), fullPage: true })
    await expectNoSeriousA11y(page, 'move asking for a fresh sign-in')
    await stepUp.getByRole('button', { name: 'Sign in again' }).click()
    await expect(stepUp).toHaveCount(0)
    await expect(drawer.getByRole('radio', { name: /Data/ })).toBeChecked()

    const again = await confirmMove()
    await again.getByRole('button', { name: 'Move it' }).click()

    // she lands on the machine under its new wfrn, in Data
    await expect.poll(() => decodeURIComponent(new URL(page.url()).hash)).toContain(wfrnOf('vm-gen-005', DATA))
    await expect(page.getByRole('heading', { name: 'etl-015', level: 1 })).toBeVisible()
    await expect(page.locator('header').getByText('Data').first()).toBeVisible()
    await page.screenshot({ path: shot('transfer-landed-desktop'), fullPage: true })

    // the old link says where it went, and links there
    await page.goto(`/#${vmPage('vm-gen-005')}`)
    const notice = page.locator('[data-moved-to]')
    await expect(notice).toBeVisible()
    await expect(notice.getByRole('heading', { name: 'This resource moved to Data' })).toBeVisible()
    const link = notice.getByRole('link', { name: 'Open it in Data' })
    await expect(link).toHaveAttribute('href', `#/account/${DATA}${vmPage('vm-gen-005', DATA)}`)
    await page.screenshot({ path: shot('transfer-old-link-desktop'), fullPage: true })
    await expectNoSeriousA11y(page, 'moved resource’s old link')
    await page.setViewportSize({ width: 390, height: 844 })
    await expectNoHorizontalOverflow(page)
    await page.screenshot({ path: shot('transfer-old-link-phone'), fullPage: true })
    await link.click()
    await expect(page.getByRole('heading', { name: 'etl-015', level: 1 })).toBeVisible()
  })

  test('a move is refused, in words, while a request is changing the machine', async ({ page }) => {
    await danaJoinsData(page)
    await signInAs(page, /Continue as Dana Reyes/, vmPage('vm-gen-003'))
    await expect(page.getByRole('heading', { name: 'cache-013', level: 1 })).toBeVisible()
    const drawer = await openMove(page, 'cache-013')
    await drawer.getByRole('radio', { name: /Data/ }).check()
    await drawer.getByLabel(/Why it moves/).fill('Moving the cache with its database')
    // meanwhile, a restart starts on the machine from another tab
    expect(await call(page, 'POST', `/console/v1/r/vm/${encodeURIComponent(wfrnOf('vm-gen-003'))}:restart`, { input: {} })).toBe(202)
    await drawer.getByRole('button', { name: 'Move to Data' }).click()
    const confirm = page.getByRole('dialog', { name: /Move cache-013 to Data/ })
    await confirm.getByLabel(/Type cache-013 to confirm/).fill('cache-013')
    await confirm.getByRole('button', { name: 'Move it' }).click()
    const failure = drawer.locator('[data-failure="resource.busy"]')
    await expect(failure).toBeVisible()
    await expect(failure).toContainText('A request is changing this resource')
    await expect(failure).toContainText('Wait for the request to finish, then move it.')
    await page.screenshot({ path: shot('transfer-busy-desktop'), fullPage: true })
    await expectNoSeriousA11y(page, 'move refused for a busy machine')
  })

  test('Gonzalo approves an access request on a 390 px phone, with a comment', async ({ page }) => {
    // Mia, a viewer, asks to become an operator
    await signInAs(page, /Continue as Mia Chen/, '/')
    expect(await call(page, 'POST', '/console/v1/access-requests', { action: 'compute:create', role: 'operator', reason: 'I run the payments machines from now on' })).toBeLessThan(300)
    await signOut(page)

    await page.setViewportSize({ width: 390, height: 844 })
    await signInAs(page, /Continue as Gonzalo Munoz/, '/m')
    await expect(page.getByRole('heading', { name: 'Waiting for you', level: 1 })).toBeVisible()
    // installable: the manifest opens the phone view on its own, standalone
    const manifest = await page.evaluate(async () => {
      const href = document.querySelector('link[rel="manifest"]')?.getAttribute('href') ?? ''
      return (await (await fetch(href)).json()) as { display: string; start_url: string; icons: { sizes: string }[] }
    })
    expect(manifest).toMatchObject({ display: 'standalone', start_url: '/#/m' })
    expect(manifest.icons.map((i) => i.sizes)).toEqual(expect.arrayContaining(['192x192', '512x512']))

    const card = page.locator('[data-access-request]').filter({ hasText: 'Mia Chen asks for Operator' })
    await expect(card).toBeVisible()
    await expect(card).toContainText('I run the payments machines from now on')
    const approve = card.getByRole('button', { name: /^Approve/ })
    expect((await approve.boundingBox())!.height).toBeGreaterThanOrEqual(44)
    await expectNoHorizontalOverflow(page)
    await page.screenshot({ path: shot('mobile-approvals-phone'), fullPage: true })
    await expectNoSeriousA11y(page, 'phone view, approvals')

    await card.getByLabel('Comment').fill('Welcome to the payments work')
    await approve.click()
    await expect(page.getByRole('status').filter({ hasText: 'Mia Chen now holds Operator' })).toBeAttached()
    await expect(card).toHaveCount(0)
    await expect(page.getByText('No access request names you.')).toBeVisible()
  })

  test('Gonzalo reads drift and reverts one finding from the phone view, then follows its request', async ({ page }) => {
    test.setTimeout(60_000)
    await page.setViewportSize({ width: 360, height: 780 })
    await signInAs(page, /Continue as Gonzalo Munoz/, '/')
    // the user menu leads to the phone view
    await page.getByRole('button', { name: /Account menu/ }).click()
    await page.getByRole('menuitem', { name: 'Phone view: approvals and drift' }).click()
    await expect(page).toHaveURL(/#\/m$/)
    await page.getByRole('navigation', { name: 'Sections' }).getByRole('link', { name: /Drift/ }).click()
    await expect(page.getByRole('heading', { name: 'Drift', level: 1 })).toBeVisible()
    const card = page.locator('[data-finding]').filter({ hasText: 'vm-demo02' })
    await expect(card).toBeVisible()
    await expect(card).toContainText('asked for')
    await expectNoHorizontalOverflow(page)
    await page.screenshot({ path: shot('mobile-drift-phone'), fullPage: true })
    await expectNoSeriousA11y(page, 'phone view, drift')

    const revert = card.getByRole('button', { name: 'Revert to what was asked for' })
    expect((await revert.boundingBox())!.height).toBeGreaterThanOrEqual(44)
    await revert.click()
    await expect(card.locator('[data-revert="running"]')).toBeVisible()
    await card.getByRole('link', { name: 'Follow the request' }).click()
    await expect(page).toHaveURL(/#\/m\/requests\/job_/)
    await expect(page.getByRole('heading', { name: 'Request', level: 1 })).toBeVisible()
    await expect(page.locator('[data-job-status]')).toBeVisible()
    await expectNoHorizontalOverflow(page)
    await page.screenshot({ path: shot('mobile-request-phone'), fullPage: true })
    await expectNoSeriousA11y(page, 'phone view, one request')
    // the request runs on the mock's clock and succeeds
    await expect(page.locator('[data-job-status="succeeded"]')).toBeVisible({ timeout: 30_000 })
  })
})
