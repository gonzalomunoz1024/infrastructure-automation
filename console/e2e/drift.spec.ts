import { fileURLToPath } from 'node:url'
import { expect, test, type Page } from '@playwright/test'
import { expectNoHorizontalOverflow, expectNoSeriousA11y, signInAs } from './support'

/*
 * Phase 5, in mock mode: decide on drift. Revert a finding in one click on the resource's Drift
 * tab and follow its request until the finding closes; accept the provider's values and ignore
 * fields for a while; the account's Drift page with its numbers, a revert from the list and a
 * revert of several rows at once; the unmanaged resources finder with a discovery run, an import
 * and Assign to team; and a viewer who sees every decision refused, with why. Each new page is
 * checked with axe and at phone width. The mock runs requests on a clock: 8 s to apply a change,
 * 4 s for a discovery run.
 */

const shots = fileURLToPath(new URL('./screenshots/', import.meta.url))
const shot = (name: string) => `${shots}${name}.png`

const ACCOUNT = '123456789012'
const wfrnOf = (id: string) => `wfrn:wf:compute:global:${ACCOUNT}:vm/${id}`
const vmPage = (id: string, query = '') => `/console/compute/vm/${encodeURIComponent(wfrnOf(id))}${query}`

/** An Atlas Select, named by its label and the text it shows: "Owner: Any owner". */
const selectNamed = (label: string) => new RegExp(`^${label}: `)

async function choose(page: Page, label: string, option: string | RegExp) {
  await page.getByRole('button', { name: selectNamed(label) }).click()
  await page.getByRole('option', { name: option }).click()
}

test.describe('drift and unmanaged resources in mock mode', () => {
  test('Dana reverts a drifted VM in one click: the card says Reverting with its request, and the finding closes when the request succeeded', async ({ page }) => {
    test.setTimeout(60_000)
    await signInAs(page, /Continue as Dana Reyes/, vmPage('vm-demo02', '?tab=drift'))
    const card = page.locator('[data-finding]')
    await expect(card).toHaveCount(1)
    await expect(card.getByRole('table', { name: 'Fields that drifted' })).toBeVisible()
    // no confirmation: one click starts the type's update workflow with what was asked for
    await card.getByRole('button', { name: 'Revert to what was asked for' }).click()
    await expect(card.locator('[data-revert="running"]')).toBeVisible()
    const follow = card.getByRole('link', { name: 'Follow the request' })
    await expect(follow).toHaveAttribute('href', /#\/requests\/job_/)
    await expect(card.getByRole('button', { name: 'Accept the provider’s values' })).toBeDisabled()
    await expect(card.getByText('A revert is running; the finding closes when its request succeeds.')).toBeVisible()
    await page.screenshot({ path: shot('drift-reverting-desktop'), fullPage: true })
    await expectNoSeriousA11y(page, 'drift tab with a revert running')

    // the tab follows the request; once it succeeded the finding is closed, first in the history
    await expect(page.locator('[data-finding]')).toHaveCount(0, { timeout: 20_000 })
    const history = page.getByRole('list', { name: 'Closed findings' })
    await expect(history.getByRole('listitem').first()).toContainText('Reverted')
    await expect(history.getByRole('listitem').first()).toContainText('by dana')
    await expect(page.getByRole('status').filter({ hasText: 'The revert finished' })).toBeAttached()
  })

  test('Dana accepts what the provider reports on one VM and ignores the fields of another for a week', async ({ page }) => {
    await signInAs(page, /Continue as Dana Reyes/, vmPage('vm-gen-017', '?tab=drift'))
    const card = page.locator('[data-finding]')
    await expect(card).toHaveCount(1)
    await card.getByRole('button', { name: 'Accept the provider’s values' }).click()
    const accept = page.getByRole('dialog', { name: 'Accept the provider’s values' })
    await expect(accept).toBeVisible()
    await expect(accept.getByText(/becomes 6/)).toBeVisible()
    await expect(accept.getByLabel('Reason')).toBeFocused()
    await accept.getByLabel('Reason').fill('Six CPUs were agreed for the month end')
    await page.screenshot({ path: shot('drift-accept-desktop'), fullPage: true })
    await expectNoSeriousA11y(page, 'accept dialog')
    await accept.getByRole('button', { name: 'Accept', exact: true }).click()
    await expect(accept).toBeHidden()
    await expect(page.locator('[data-finding]')).toHaveCount(0)
    const history = page.getByRole('list', { name: 'Closed findings' })
    await expect(history.getByRole('listitem').first()).toContainText('Accepted')
    await expect(history.getByRole('listitem').first()).toContainText('Six CPUs were agreed for the month end')
    // the provider's value is what is asked for now: the configuration marks nothing as different
    await page.getByRole('tab', { name: /^Configuration/ }).click()
    await expect(page.getByText(/^Provider reports/)).toHaveCount(0)

    await page.goto(`/#${vmPage('vm-gen-027', '?tab=drift')}`)
    await page.locator('[data-finding]').getByRole('button', { name: 'Ignore these fields' }).click()
    const ignore = page.getByRole('dialog', { name: 'Ignore these fields' })
    await expect(ignore.getByRole('radio', { name: 'Until what was asked for changes' })).toBeFocused()
    await ignore.getByRole('radio', { name: 'For 7 days' }).check()
    await ignore.getByLabel('Reason').fill('Load test this week')
    await expectNoSeriousA11y(page, 'ignore dialog')
    await ignore.getByRole('button', { name: 'Ignore', exact: true }).click()
    await expect(ignore).toBeHidden()
    await expect(page.locator('[data-finding]')).toHaveCount(0)
    await expect(page.getByRole('list', { name: 'Closed findings' }).getByRole('listitem').first()).toContainText(/Ignored by dana until/)
  })

  test('the account’s Drift page counts the findings, reverts one from the list and several at once', async ({ page }) => {
    test.setTimeout(60_000)
    // tall enough for every open finding of the account to be drawn by the virtual table
    await page.setViewportSize({ width: 1280, height: 1600 })
    await signInAs(page, /Continue as Dana Reyes/, '/operations/drift')
    await expect(page.getByRole('heading', { name: 'Drift', level: 1 })).toBeVisible()
    const tiles = page.getByRole('region', { name: 'Open findings' })
    await expect(tiles).toContainText(/critical/)
    await expect(page.getByRole('heading', { name: 'The last 30 days' })).toBeVisible()
    await expect(page.getByText('1 ignored, 1 settled by themselves')).toBeVisible()
    const table = page.getByRole('grid', { name: 'Drift findings' })
    await expect(table.getByRole('link', { name: 'vm-demo02' })).toBeVisible()
    await page.screenshot({ path: shot('drift-account-desktop'), fullPage: true })
    await expectNoSeriousA11y(page, 'account drift page')

    // one row's Revert, in one click
    const row = table.getByRole('row').filter({ has: page.getByRole('link', { name: 'vm-demo02' }) })
    await row.getByRole('button', { name: 'Revert', exact: true }).click()
    await expect(row.getByText('Reverting')).toBeVisible()

    // the filters narrow the list on the server
    await choose(page, 'Severity', 'Critical')
    await expect(table.getByRole('link', { name: 'vm-demo02' })).toHaveCount(0)
    await choose(page, 'Severity', 'Any severity')
    await expect(table.getByRole('link', { name: 'vm-demo02' })).toBeVisible()

    // two at once: one call each, counted as they go
    await table.getByRole('checkbox', { name: 'Select vm-gen-037' }).check()
    await table.getByRole('checkbox', { name: 'Select vm-gen-057' }).check()
    const bulk = page.locator('[data-bulk]')
    await expect(bulk).toContainText('2 findings selected')
    await bulk.getByRole('button', { name: 'Revert 2' }).click()
    await expect(bulk.locator('[data-bulk-status]')).toContainText('2 reverts started')
    // the provider refuses the first revert of vm-gen-037: its row says so once the request failed
    const refused = table.getByRole('row').filter({ has: page.getByRole('link', { name: 'vm-gen-037' }) })
    await expect(refused.getByText('Revert failed')).toBeVisible({ timeout: 20_000 })
    await expect(refused.getByRole('button', { name: 'Revert', exact: true })).toBeEnabled()

    await page.setViewportSize({ width: 390, height: 844 })
    await expect(page.getByRole('heading', { name: 'Drift', level: 1 })).toBeVisible()
    await expectNoHorizontalOverflow(page)
    await page.screenshot({ path: shot('drift-account-phone'), fullPage: true })
  })

  test('the unmanaged resources finder runs a discovery, imports one machine and assigns another to the team it belongs to', async ({ page }) => {
    test.setTimeout(60_000)
    await signInAs(page, /Continue as Dana Reyes/, '/operations/unmanaged')
    await expect(page.getByRole('heading', { name: 'Unmanaged resources', level: 1 })).toBeVisible()
    await expect(page.getByText('Nothing found yet')).toBeVisible()
    const vms = page.locator('[data-discover="vm"]')
    await vms.getByRole('button', { name: 'Find unmanaged' }).click()
    await expect(vms.locator('[data-run="running"]')).toBeVisible()
    await expect(vms.locator('[data-run="succeeded"]')).toContainText('8 unmanaged objects', { timeout: 15_000 })
    const objects = page.getByRole('list', { name: 'Unmanaged objects' })
    await expect(objects.locator(':scope > li')).toHaveCount(8)
    const pay = page.getByRole('article', { name: 'pay-gw-test' })
    await expect(pay).toContainText('High confidence')
    await expect(pay).toContainText('Tagged team=team-data, the team that owns Data')
    await expect(pay).toContainText(/a month/)
    await page.screenshot({ path: shot('unmanaged-desktop'), fullPage: true })
    await expectNoSeriousA11y(page, 'unmanaged resources')

    await choose(page, 'Owner', 'Owner unknown')
    await expect(objects.locator(':scope > li')).toHaveCount(2)
    await choose(page, 'Owner', 'Any owner')
    await expect(objects.locator(':scope > li')).toHaveCount(8)

    // Import: a name to be managed under, then a new wfrn and a link to its page
    await page.getByRole('article', { name: 'compute-scratch-01' }).getByRole('button', { name: 'Import' }).click()
    const dialog = page.getByRole('dialog', { name: 'Import compute-scratch-01' })
    await expect(dialog.getByLabel('Name')).toBeFocused()
    await expect(dialog.getByLabel('Name')).toHaveValue('compute-scratch-01')
    await dialog.getByRole('button', { name: 'Import', exact: true }).click()
    await expect(dialog).toBeHidden()
    const done = page.locator('[data-imported]')
    await expect(done).toContainText('compute-scratch-01 is managed now')
    await expect(objects.locator(':scope > li')).toHaveCount(7)

    // Assign to team: the guessed account first, with a note
    await pay.getByRole('button', { name: 'Assign to team' }).click()
    const assign = page.getByRole('dialog', { name: 'Assign pay-gw-test to a team' })
    await expect(assign.getByRole('radio', { name: /Data \(team-data\), the guessed owner/ })).toBeFocused()
    await assign.getByLabel('Note').fill('Tagged for your team; yours to import or delete')
    await expectNoSeriousA11y(page, 'assign dialog')
    await assign.getByRole('button', { name: 'Assign', exact: true }).click()
    await expect(assign).toBeHidden()
    await expect(pay.locator('[data-assignment]')).toContainText('Assigned to Data by dana')

    await page.setViewportSize({ width: 390, height: 844 })
    await expectNoHorizontalOverflow(page)
    await page.screenshot({ path: shot('unmanaged-phone'), fullPage: true })

    // the imported machine has its own page
    await page.setViewportSize({ width: 1280, height: 800 })
    await done.getByRole('link', { name: 'Open its page' }).click()
    await expect(page.getByRole('heading', { name: 'compute-scratch-01', level: 1 })).toBeVisible()
  })

  test('Mia, a viewer, reads the drift and sees every decision refused, with who decided', async ({ page }) => {
    await signInAs(page, /Continue as Mia Chen/, vmPage('vm-demo02', '?tab=drift'))
    const card = page.locator('[data-finding]')
    await expect(card).toHaveCount(1)
    for (const verb of ['revert', 'accept', 'ignore']) await expect(card.locator(`[data-verb="${verb}"]`)).toHaveAttribute('data-denied', 'console-iam')
    await card.locator('[data-verb="revert"]').click()
    const refusal = page.getByRole('dialog', { name: /You can.t revert to what was asked for here/ })
    await expect(refusal).toBeVisible()
    await expect(refusal.getByText(/compute:update/).first()).toBeVisible()
    await page.screenshot({ path: shot('drift-viewer-desktop'), fullPage: true })
    await expectNoSeriousA11y(page, 'refused revert')
    await page.keyboard.press('Escape')

    await page.goto('/#/operations/drift')
    await expect(page.getByRole('heading', { name: 'Drift', level: 1 })).toBeVisible()
    await expect(page.locator('[data-verb="revert"][data-denied="console-iam"]').first()).toBeVisible()
    await page.setViewportSize({ width: 390, height: 844 })
    await expectNoHorizontalOverflow(page)
    await expectNoSeriousA11y(page, 'account drift page for a viewer at phone width')
  })
})
