import { readFile } from 'node:fs/promises'
import { fileURLToPath } from 'node:url'
import { expect, test, type Page } from '@playwright/test'
import { expectNoHorizontalOverflow, expectNoSeriousA11y, signInAs } from './support'

/*
 * The state service's views, in mock mode: the account's activity trail filtered by person and
 * type, its chain verified in the browser (holding over a week, breaking over the month at the
 * one event the mock alters after hashing) and its evidence pack downloaded; a VM replayed to
 * yesterday, compared with now and reverted through the edit review; its lineage followed to the
 * image it was built from; and the resource map moved through with the keyboard, with its list
 * view. The mock's chain is real sha-256 and its seals are signed with demo P-256 keys.
 */

test.use({ timezoneId: 'UTC' })

const shots = fileURLToPath(new URL('./screenshots/', import.meta.url))
const shot = (name: string) => `${shots}${name}.png`

const ACCOUNT = '123456789012'
const wfrnOf = (id: string) => `wfrn:wf:compute:global:${ACCOUNT}:vm/${id}`
const vmPage = (id: string, query = '') => `/console/compute/vm/${encodeURIComponent(wfrnOf(id))}${query}`
const selectNamed = (label: string) => new RegExp(`^${label.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')}: `)

async function choose(page: Page, label: string, option: string | RegExp) {
  await page.getByRole('button', { name: selectNamed(label) }).click()
  await page.getByRole('option', { name: option }).click()
  await expect(page.getByRole('button', { name: selectNamed(label) })).toContainText(option)
}

test.describe('the state service in mock mode', () => {
  test('Gonzalo filters the activity trail, verifies its chain over a week and a month, and downloads the evidence pack', async ({ page }) => {
    test.setTimeout(90_000)
    await signInAs(page, /Continue as Gonzalo Munoz/, '/history/activity')
    await expect(page.getByRole('heading', { name: 'Activity trail', level: 1 })).toBeVisible()
    const trail = page.getByRole('table', { name: /trail|activity/i })
    await expect(trail.locator('tbody tr').first()).toBeVisible()

    // filter by type and by what happened: each row a change request on a virtual machine, with who and the request
    await choose(page, 'Resource type', 'Virtual machines')
    await expect(page).toHaveURL(/rtype=vm/)
    await choose(page, 'What', 'Change requested')
    await expect(page).toHaveURL(/what=UpdateRequested/)
    await expect(trail.locator('tbody tr').first()).toContainText('Change requested')
    await expect(trail.locator('tbody tr').first().getByRole('link').first()).toHaveAttribute('href', /tab=activity/)
    await page.screenshot({ path: shot('trail-filtered-desktop'), fullPage: true })
    await expectNoSeriousA11y(page, 'activity trail, filtered')

    // the week holds, checked in the browser
    const status = page.locator('[data-verify]')
    await page.getByRole('button', { name: 'Verify the chain' }).click()
    await expect(status).toHaveAttribute('data-verify', 'holds', { timeout: 30_000 })
    await expect(page.getByTestId('verify-result')).toContainText(/^The chain holds from/)
    await page.screenshot({ path: shot('trail-verified-desktop'), fullPage: true })

    // the month breaks, at the one event changed after it was hashed, named
    await page.getByRole('radio', { name: '30 days' }).click()
    await page.getByRole('button', { name: 'Verify the chain' }).click()
    await expect(status).toHaveAttribute('data-verify', 'broken', { timeout: 30_000 })
    const result = page.getByTestId('verify-result')
    await expect(result).toContainText(/The chain breaks at cache-043, version \d+\./)
    await expect(result).toContainText('changed after it was recorded')
    await page.screenshot({ path: shot('trail-broken-desktop'), fullPage: true })
    await expectNoSeriousA11y(page, 'activity trail, chain broken')

    // the evidence pack, for the auditors
    const [download] = await Promise.all([page.waitForEvent('download'), page.getByRole('button', { name: 'Download evidence pack' }).click()])
    expect(download.suggestedFilename()).toMatch(new RegExp(`^evidence-${ACCOUNT}-\\d{4}-\\d{2}-\\d{2}-to-\\d{4}-\\d{2}-\\d{2}\\.json$`))
    const pack = JSON.parse(await readFile((await download.path())!, 'utf8')) as { events: unknown[]; digests: unknown[]; keys: unknown[] }
    expect(pack.events.length).toBeGreaterThan(10)
    expect(pack.digests.length).toBeGreaterThan(20)
    expect(pack.keys.length).toBeGreaterThanOrEqual(2)
  })

  test('Mia, a viewer, reads the trail but is refused the evidence pack, with why', async ({ page }) => {
    await signInAs(page, /Continue as Mia/, '/history/activity')
    await expect(page.getByRole('heading', { name: 'Activity trail', level: 1 })).toBeVisible()
    const verify = page.locator('[data-verb="verify"]')
    await expect(verify).toHaveAttribute('data-denied', /.+/)
    await expect(page.locator('[data-verb="evidence"]')).toHaveAttribute('data-denied', /.+/)
  })

  test('Dana replays a VM to yesterday, compares it with now and reverts it through the edit review', async ({ page }) => {
    test.setTimeout(90_000)
    await signInAs(page, /Continue as Dana Reyes/, vmPage('vm-demo04', '?tab=activity'))
    await expect(page.getByTestId('time-slider')).toBeVisible()
    await page.getByRole('button', { name: 'Yesterday', exact: true }).click()
    await expect(page).toHaveURL(/at=/)
    await expect(page.getByTestId('time-point')).toContainText('As it was at the end of')

    // asked for then: 8 CPUs; asked for now: 2
    const cpu = page.getByTestId('time-table').locator('tr[data-field="cpu"]')
    await expect(cpu).toHaveAttribute('data-changed', 'true')
    await expect(cpu.getByRole('cell').nth(0)).toContainText('8')
    await expect(cpu.getByRole('cell').nth(2)).toHaveText('2')
    await expect(page.getByRole('list', { name: 'What changed since then' })).toContainText('CPU: 8 → 2')
    await page.screenshot({ path: shot('time-travel-desktop'), fullPage: true })
    await expectNoSeriousA11y(page, 'time travel on the activity tab')

    // the chain of this one VM holds
    await page.getByRole('button', { name: 'Check its chain' }).click()
    await expect(page.locator('[data-chain]')).toHaveAttribute('data-chain', 'holds')

    // Revert to this: the edit drawer, filled with then, for review before anything runs
    await page.locator('[data-verb="revert"]').click()
    await expect(page).toHaveURL(/action=edit/)
    const drawer = page.getByRole('dialog', { name: /^Edit configuration: / })
    await expect(drawer).toBeVisible()
    await expect(drawer.getByTestId('revert-note')).toBeVisible()
    await expect(drawer.getByRole('button', { name: /^CPUs: / })).toHaveAccessibleName('CPUs: 8')
    const diff = drawer.getByRole('table', { name: /What changes/ })
    await expect(diff.locator('tr[data-field="cpu"]')).toBeVisible()
    await page.screenshot({ path: shot('revert-review-desktop'), fullPage: true })
    await expectNoSeriousA11y(page, 'revert through the edit drawer')
    const send = drawer.locator('[data-verb="update"]')
    await expect(send).toBeEnabled()
    await send.click()
    await expect(drawer).toBeHidden()
    await expect(page).not.toHaveURL(/action=/)
    await expect(page.getByText('Update VM requested')).toBeVisible()
  })

  test('Dana follows a VM’s lineage to its image, then moves through the resource map with the keyboard', async ({ page }) => {
    test.setTimeout(90_000)
    await signInAs(page, /Continue as Dana Reyes/, vmPage('vm-demo04', '?tab=relationships'))
    await expect(page.getByRole('list', { name: 'What made it' })).toContainText(/Built by|no record/)
    const lineage = page.getByTestId('lineage')
    await expect(lineage).toBeVisible()
    const parents = lineage.getByRole('list', { name: 'Built from', exact: true })
    await expect(parents.getByRole('link').first()).toBeVisible()
    await page.screenshot({ path: shot('lineage-desktop'), fullPage: true })
    await expectNoSeriousA11y(page, 'lineage on the relationships tab')
    const image = await parents.getByRole('link').first().textContent()
    await parents.getByRole('link').first().click()
    await expect(page.getByRole('heading', { level: 1, name: image ?? '' })).toBeVisible()
    await expect(page.getByTestId('lineage').getByRole('list', { name: 'Built from it' })).toContainText('batch-01')

    // the map: focus it, move along the links, open what is focused
    await page.goto('/#/history/map')
    await expect(page.getByRole('heading', { name: 'Resource map', level: 1 })).toBeVisible()
    const map = page.getByTestId('map')
    await expect(map.locator('[data-wfrn]').first()).toBeAttached()
    await map.focus()
    const said = page.getByTestId('map-focus')
    await expect(said).toContainText('Built from')
    const first = await said.textContent()
    await page.keyboard.press('ArrowRight')
    await expect(said).not.toHaveText(first ?? '')
    // right went to something built from what was focused, or the next column; left goes back to what it was built from
    const second = (await said.textContent()) ?? ''
    const parent = /Built from (.+?)\. (?:\d|Nothing)/.exec(second)?.[1]
    expect(parent, 'what the second resource was built from').toBeTruthy()
    await page.keyboard.press('ArrowLeft')
    await expect(said).toHaveText(new RegExp(`^${parent!.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')}, `))
    await page.keyboard.press('ArrowRight')
    await expect(said).not.toHaveText(new RegExp(`^${parent!.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')}, `))
    const opened = ((await said.textContent()) ?? '').split(',')[0]
    await page.keyboard.press('+')
    await page.getByRole('radio', { name: 'Cost' }).click()
    await map.focus()
    await page.screenshot({ path: shot('map-desktop'), fullPage: true })
    await expectNoSeriousA11y(page, 'resource map')
    await page.keyboard.press('Enter')
    await expect(page.getByRole('heading', { level: 1, name: opened })).toBeVisible()

    // the same resources as a list
    await page.goto('/#/history/map?view=list')
    const table = page.getByRole('table', { name: /resources on the map/ })
    await expect(table.locator('tbody tr').first()).toBeVisible()
    await expect(table.getByRole('link', { name: 'batch-01' }).first()).toBeVisible()
    await page.screenshot({ path: shot('map-list-desktop'), fullPage: true })
    await expectNoSeriousA11y(page, 'resource map as a list')
  })

  test('the inventory as of a date lists what existed then, each opening its time travel', async ({ page }) => {
    await signInAs(page, /Continue as Dana Reyes/, '/history/as-of')
    await expect(page.getByRole('heading', { name: 'Inventory as of a date', level: 1 })).toBeVisible()
    await expect(page.getByRole('status').filter({ hasText: /existed at the end of/ })).toBeVisible()
    await expect(page.getByRole('link', { name: 'batch-01', exact: true })).toHaveAttribute('href', /tab=activity&at=/)
    await expectNoSeriousA11y(page, 'inventory as of a date')
  })

  test('the trail, time travel, lineage and the map fit a phone', async ({ page }) => {
    test.setTimeout(90_000)
    await page.setViewportSize({ width: 390, height: 844 })
    await signInAs(page, /Continue as Gonzalo Munoz/, '/history/activity')
    await expect(page.getByRole('heading', { name: 'Activity trail', level: 1 })).toBeVisible()
    await expect(page.getByRole('table', { name: /trail|activity/i }).locator('tbody tr').first()).toBeVisible()
    await expectNoHorizontalOverflow(page)
    await page.screenshot({ path: shot('trail-phone'), fullPage: true })
    await expectNoSeriousA11y(page, 'activity trail at phone width')

    await page.goto(`/#${vmPage('vm-demo04', '?tab=activity')}`)
    await page.getByRole('button', { name: 'Yesterday', exact: true }).click()
    await expect(page.getByTestId('time-table')).toBeVisible()
    await expectNoHorizontalOverflow(page)
    await page.screenshot({ path: shot('time-travel-phone'), fullPage: true })
    await expectNoSeriousA11y(page, 'time travel at phone width')

    await page.goto(`/#${vmPage('vm-demo04', '?tab=relationships')}`)
    await expect(page.getByTestId('lineage')).toBeVisible()
    await expectNoHorizontalOverflow(page)
    await page.screenshot({ path: shot('lineage-phone'), fullPage: true })

    await page.goto('/#/history/map')
    await expect(page.getByTestId('map').locator('[data-wfrn]').first()).toBeAttached()
    await expectNoHorizontalOverflow(page)
    await page.screenshot({ path: shot('map-phone'), fullPage: true })
    await expectNoSeriousA11y(page, 'resource map at phone width')
  })
})
