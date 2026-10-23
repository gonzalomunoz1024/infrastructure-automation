import { fileURLToPath } from 'node:url'
import { expect, test, type Locator, type Page } from '@playwright/test'
import { expectNoHorizontalOverflow, expectNoSeriousA11y, signInAs } from './support'

/*
 * Phase 7, the CLI's web side and the developer portal, in mock mode: Dana approves a CLI sign-in
 * after signing in again (her sign-in is older than the ten minutes approving allows), denies
 * another she does not know, and signs a CLI out; the console shell lists, reads and runs a
 * workflow with her own session and follows its job; a viewer is refused in the shell; the
 * developer portal lists the API, builds sample calls and keeps a tile as a shortcut; and every
 * page at phone width. The mock's grants: WDJB-MJHT waits from Dana's laptop, QXRT-VNPL from an
 * address she does not know.
 */

const shots = fileURLToPath(new URL('./screenshots/', import.meta.url))
const shot = (name: string) => `${shots}${name}.png`

const ACCOUNT = '123456789012'
const wfrnOf = (id: string) => `wfrn:wf:compute:global:${ACCOUNT}:vm/${id}`

/** The mock keeps the session behind its cookie in localStorage: age it, as if Dana signed in twenty minutes ago. */
async function ageSignIn(page: Page, minutes: number): Promise<void> {
  await page.evaluate((ms) => {
    const raw = localStorage.getItem('console-mock-session')
    if (!raw) throw new Error('no mock session')
    const s = JSON.parse(raw) as { authTime: string }
    s.authTime = new Date(Date.now() - ms).toISOString()
    localStorage.setItem('console-mock-session', JSON.stringify(s))
  }, minutes * 60_000)
  await page.reload()
}

async function onPhone(page: Page, ready: Locator): Promise<void> {
  await page.setViewportSize({ width: 390, height: 844 })
  await page.reload()
  await expect(ready).toBeVisible()
}

const shellInput = (page: Page) => page.getByRole('textbox', { name: 'Command' })

/** Types a line into the shell and waits for it to finish. */
async function shell(page: Page, line: string, timeout = 10_000): Promise<Locator> {
  const input = shellInput(page)
  await input.fill(line)
  await input.press('Enter')
  const entry = page.locator(`[data-shell-command="${line.replace(/"/g, '\\"')}"]`).last()
  await expect(entry).toHaveAttribute('data-shell-entry', /^(ok|failed)$/, { timeout })
  return entry
}

test.describe('the CLI’s web side and the developer portal in mock mode', () => {
  test('Dana approves a CLI sign-in after signing in again, and finds it among her CLI sign-ins', async ({ page }) => {
    await signInAs(page, /Continue as Dana Reyes/, '/device?code=WDJB-MJHT')
    await ageSignIn(page, 20)
    const card = page.locator('[data-grant="WDJB-MJHT"]')
    await expect(card).toBeVisible()
    await expect(page.getByRole('heading', { name: 'The console CLI asks to sign in as you' })).toBeVisible()
    await expect(card).toContainText('macOS on arm64, iaf 0.7.0')
    await expect(card).toContainText('10.20.4.17')
    await expect(card).toContainText('Everything you may do in the console')
    await expect(card).toHaveAttribute('data-grant-status', 'pending')
    await page.screenshot({ path: shot('cli-device-desktop'), fullPage: true })
    await expectNoSeriousA11y(page, 'device sign-in page')

    // approving wants a fresh sign-in; the page says why and signs in again in place
    await page.getByRole('button', { name: 'Approve' }).click()
    await expect(page.getByRole('heading', { name: 'Sign in again to approve' })).toBeVisible()
    await expect(page.getByRole('button', { name: 'Approve' })).toBeDisabled()
    await expectNoSeriousA11y(page, 'device sign-in, step-up')
    await page.getByRole('button', { name: 'Sign in again' }).click()
    await expect(page.locator('[data-device-status]')).toContainText('You signed in again. Approve to finish')
    const approve = page.getByRole('button', { name: 'Approve' })
    await expect(approve).toBeFocused()
    await page.keyboard.press('Enter')
    await expect(page.locator('[data-device-outcome="approved"]')).toContainText('This sign-in was approved')
    await expect(card).toHaveAttribute('data-grant-status', 'approved')
    await page.screenshot({ path: shot('cli-device-approved-desktop'), fullPage: true })

    // the CLI it signed in, newest first, from the user menu
    await page.getByRole('button', { name: /Account menu: Dana Reyes/ }).click()
    await page.getByRole('menuitem', { name: 'CLI sign-ins' }).click()
    await expect(page.getByRole('heading', { name: 'CLI sign-ins', level: 1 })).toBeVisible()
    await expect(page.locator('[data-api-session-count]')).toHaveAttribute('data-api-session-count', '3')
    const table = page.getByRole('table', { name: 'Your CLI and SDK sign-ins' })
    await expect(table.getByRole('row').nth(1)).toContainText('The console CLI')
    await expect(table.getByRole('row').nth(1)).toContainText('just now')
    await expect(page.getByRole('navigation').getByRole('link', { name: 'CLI sign-ins' })).toHaveAttribute('aria-current', 'page')
    await page.screenshot({ path: shot('cli-sessions-desktop'), fullPage: true })
    await expectNoSeriousA11y(page, 'CLI sign-ins')

    // a reload keeps the decision
    await page.goto('/#/device?code=wdjb-mjht')
    await expect(page.locator('[data-device-outcome="approved"]')).toBeVisible()
  })

  test('Dana types a code she does not know, denies it, and signs a CLI out', async ({ page }) => {
    await signInAs(page, /Continue as Dana Reyes/, '/device')
    const code = page.getByLabel(/^Code/)
    await expect(code).toBeFocused()
    await code.fill('wdjb')
    await page.keyboard.press('Enter')
    await expect(page.getByText('The code is eight letters, like WDJB-MJHT.')).toBeVisible()
    await code.fill('qxrt vnpl')
    await page.keyboard.press('Enter')
    await expect(page).toHaveURL(/#\/device\?code=QXRT-VNPL/)
    const card = page.locator('[data-grant="QXRT-VNPL"]')
    await expect(card).toContainText('203.0.113.42')
    await expect(card).toContainText('Linux on x64')
    // denying needs no fresh sign-in, even an old one
    await ageSignIn(page, 20)
    await page.getByRole('button', { name: 'Deny' }).click()
    await expect(page.locator('[data-device-outcome="denied"]')).toContainText('This sign-in was denied')
    await expect(page.locator('[data-device-outcome="denied"]')).toContainText('Nothing was signed in')
    await expect(page.getByRole('button', { name: 'Approve' })).toHaveCount(0)

    // an expired code says so and offers nothing
    await page.goto('/#/device?code=HZKD-FWBM')
    await expect(page.locator('[data-device-outcome="expired"]')).toContainText('This code expired')

    // sign the CI job's SDK out
    await page.goto('/#/iam/cli-sign-ins')
    await expect(page.locator('[data-api-session-count]')).toHaveAttribute('data-api-session-count', '2')
    await page.getByRole('button', { name: /^Sign out a console sdk on Linux on x64/ }).click()
    const dialog = page.getByRole('dialog', { name: 'Sign out a console sdk?' })
    await expect(dialog).toBeVisible()
    await expect(dialog).toContainText('10.20.9.3')
    await expectNoSeriousA11y(page, 'sign out a CLI dialog')
    await dialog.getByRole('button', { name: 'Sign out' }).click()
    await expect(dialog).toHaveCount(0)
    await expect(page.locator('[data-api-session-count]')).toHaveAttribute('data-api-session-count', '1')
    await expect(page.getByRole('table', { name: 'Your CLI and SDK sign-ins' })).not.toContainText('A console SDK')
    await page.reload()
    await expect(page.locator('[data-api-session-count]')).toHaveAttribute('data-api-session-count', '1')
  })

  test('the console shell lists, reads and runs a workflow with Dana’s session, and follows its job', async ({ page }) => {
    test.setTimeout(90_000)
    await signInAs(page, /Continue as Dana Reyes/, '/shell')
    await expect(page.getByRole('heading', { name: 'Console shell', level: 1 })).toBeVisible()
    await expect(shellInput(page)).toBeFocused()

    const who = await shell(page, 'whoami')
    await expect(who).toContainText('Dana Reyes')
    await expect(who).toContainText('Compute (123456789012)')

    const ls = await shell(page, 'ls vm --filter environment=prod')
    const table = ls.getByRole('table', { name: /where environment=prod/ })
    await expect(table).toBeVisible()
    await expect(table).toContainText('web-01')
    await expect(table).not.toContainText('api-01')
    await expect(table.getByRole('link', { name: wfrnOf('vm-demo01') })).toBeVisible()
    await page.screenshot({ path: shot('cli-shell-desktop'), fullPage: true })
    await expectNoSeriousA11y(page, 'console shell')

    const get = await shell(page, `get ${wfrnOf('vm-demo02')}`)
    await expect(get.getByRole('table')).toContainText('api-01')
    await expect(get.getByRole('table')).toContainText('Tag environment')

    const run = await shell(page, `run restart ${wfrnOf('vm-demo02')} --input reason="stuck after a kernel update"`)
    await expect(run).toContainText(/Started restart as job job_[a-z0-9]+\./)
    const jobId = /job_[a-z0-9]+/.exec((await run.textContent()) ?? '')![0]

    // Up brings the last line back, Down returns to the empty line
    const input = shellInput(page)
    await input.press('ArrowUp')
    await expect(input).toHaveValue(`run restart ${wfrnOf('vm-demo02')} --input reason="stuck after a kernel update"`)
    await input.press('ArrowUp')
    await expect(input).toHaveValue(`get ${wfrnOf('vm-demo02')}`)
    await input.press('ArrowDown')
    await input.press('ArrowDown')
    await expect(input).toHaveValue('')
    // Tab completes a command it can name alone
    await input.fill('wh')
    await input.press('Tab')
    await expect(input).toHaveValue('whoami ')
    await expect(input).toBeFocused()

    const followed = await shell(page, `job ${jobId} --follow`, 30_000)
    await expect(followed).toContainText(`${jobId} is succeeded.`)
    await expect(followed.getByRole('table', { name: `Steps of ${jobId}` })).toBeVisible()
    await page.screenshot({ path: shot('cli-shell-job-desktop'), fullPage: true })

    const jobs = await shell(page, 'jobs --limit 5')
    await expect(jobs.getByRole('link', { name: jobId })).toBeVisible()
    await jobs.getByRole('link', { name: jobId }).click()
    await expect(page).toHaveURL(new RegExp(`#/requests/${jobId}`))

    // mistakes are said in words with the usage
    await page.goBack()
    const wrong = await shell(page, 'ls')
    await expect(wrong).toHaveAttribute('data-shell-entry', 'failed')
    await expect(wrong).toContainText('Usage: ls <type>')
  })

  test('a viewer is refused in the shell with the reason, before anything runs', async ({ page }) => {
    await signInAs(page, /Continue as Mia Chen/, '/shell')
    const run = await shell(page, `run restart ${wfrnOf('vm-demo02')} --input reason=test`)
    await expect(run).toContainText('Not allowed:')
    await expect(run).toContainText('Ask for access')
    const ls = await shell(page, 'ls vm --filter environment=dev')
    await expect(ls.getByRole('table')).toContainText('api-01')
  })

  test('the developer portal lists the API, builds sample calls from a type, and keeps a tile as a shortcut', async ({ page }) => {
    await signInAs(page, /Continue as Dana Reyes/, '/')
    await page.getByRole('button', { name: /Services/ }).first().click()
    await page.getByRole('link', { name: 'Developer portal' }).click()
    await expect(page.getByRole('heading', { name: 'Developer portal', level: 1 })).toBeVisible()
    await expect(page.locator('[data-api-group="Device sign-in"]')).toContainText('/console/v1/device-grants/{userCode}:approve')
    await expect(page.locator('[data-api-count]')).toContainText(/\d+ operations in \d+ groups/)

    // sample calls for virtual machines, from the schemas
    await expect(page.getByRole('heading', { name: 'Sample calls' })).toBeVisible()
    await expect(page.getByText('ls vm --filter environment=prod').first()).toBeVisible()
    await page.getByRole('radio', { name: 'TypeScript SDK' }).click()
    await expect(page.getByText("console.resources.create('vm'")).toBeVisible()

    // the tiles are samples, and say so; one kept as a shortcut stays after a reload
    await expect(page.locator('[data-tiles-note]')).toContainText('These tiles are samples')
    const drift = page.locator('[data-tile="drift"]')
    await drift.getByRole('button', { name: /Keep as shortcut.*Drifting resources/ }).click()
    await expect(drift.getByRole('button', { name: /Keep as shortcut.*Drifting resources/ })).toHaveAttribute('aria-pressed', 'true')
    await expect(page.locator('[data-shortcut="drift"]')).toBeVisible()
    await page.screenshot({ path: shot('cli-portal-desktop'), fullPage: true })
    await expectNoSeriousA11y(page, 'developer portal')
    await page.reload()
    await expect(page.locator('[data-shortcut="drift"]')).toBeVisible()
    await page.locator('[data-shortcut="drift"]').getByRole('link').click()
    await expect(page).toHaveURL(/#\/operations\/drift/)
  })

  test('a viewer cannot keep a workflow they may not run as a shortcut', async ({ page }) => {
    await signInAs(page, /Continue as Mia Chen/, '/developer')
    const tile = page.locator('[data-tile^="create-compute-vm"]')
    await expect(tile).toContainText('Your role does not allow compute:create')
    await expect(tile.getByRole('button', { name: /Keep as shortcut/ })).toBeDisabled()
  })

  test('the device page, the shell, CLI sign-ins and the portal fit a phone', async ({ page }) => {
    await signInAs(page, /Continue as Dana Reyes/, '/device?code=WDJB-MJHT')
    await onPhone(page, page.locator('[data-grant="WDJB-MJHT"]'))
    await expectNoHorizontalOverflow(page)
    await expectNoSeriousA11y(page, 'device sign-in at phone width')
    await page.screenshot({ path: shot('cli-device-phone'), fullPage: true })

    await page.goto('/#/shell')
    await expect(shellInput(page)).toBeVisible()
    const ls = await shell(page, 'ls vm --filter environment=prod')
    await expect(ls.getByRole('table')).toBeVisible()
    await expectNoHorizontalOverflow(page)
    await expectNoSeriousA11y(page, 'console shell at phone width')
    await page.screenshot({ path: shot('cli-shell-phone'), fullPage: true })

    await page.goto('/#/iam/cli-sign-ins')
    await expect(page.locator('[data-api-session-count]')).toBeVisible()
    await expectNoHorizontalOverflow(page)
    await expectNoSeriousA11y(page, 'CLI sign-ins at phone width')
    await page.screenshot({ path: shot('cli-sessions-phone'), fullPage: true })

    await page.goto('/#/developer')
    await expect(page.locator('[data-api-count]')).toBeVisible()
    await expectNoHorizontalOverflow(page)
    await expectNoSeriousA11y(page, 'developer portal at phone width')
    await page.screenshot({ path: shot('cli-portal-phone'), fullPage: true })
  })

  test('a signed-out link to a code comes back to it after signing in', async ({ page }) => {
    await page.goto('/#/device?code=WDJB-MJHT')
    await expect(page).toHaveURL(/#\/signin/)
    await page.getByRole('button', { name: /Continue as Dana Reyes/ }).click()
    await expect(page.locator('[data-grant="WDJB-MJHT"]')).toBeVisible()
  })
})
