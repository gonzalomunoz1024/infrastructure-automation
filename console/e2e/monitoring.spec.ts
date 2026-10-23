import { fileURLToPath } from 'node:url'
import { expect, test, type Locator, type Page } from '@playwright/test'
import { expectNoHorizontalOverflow, expectNoSeriousA11y, signInAs } from './support'

/*
 * Phase 5, in mock mode: a vm's Monitoring tab (a chart per series over a day, the range in the
 * address, a chart as a table, a point read from the keyboard), an alarm that fires as soon as
 * it is made and its deletion, the account's alarms firing first, rightsizing a machine through
 * its update workflow and dismissing another recommendation, and a viewer meeting the writes
 * they may not make, explained. The mock runs requests on a clock: 6 s to approve, 8 s to apply.
 */

const shots = fileURLToPath(new URL('./screenshots/', import.meta.url))
const shot = (name: string) => `${shots}${name}.png`

const ACCOUNT = '123456789012'
const wfrnOf = (id: string) => `wfrn:wf:compute:global:${ACCOUNT}:vm/${id}`
const vmPage = (id: string, query = '') => `/console/compute/vm/${encodeURIComponent(wfrnOf(id))}${query}`

const chart = (page: Page, name: string) => page.getByRole('figure', { name })

/** The page as a phone loads it: narrowed first, then loaded at that width, since a live resize lets the shell lay itself out again. */
async function onPhone(page: Page, ready: Locator): Promise<void> {
  await page.setViewportSize({ width: 390, height: 844 })
  await page.reload()
  await expect(ready).toBeVisible()
}

test.describe('monitoring and rightsizing in mock mode', () => {
  test('the Monitoring tab draws a chart per series over a day, keeps the range in the address, turns a chart into a table and reads a point from the keyboard', async ({ page }) => {
    await signInAs(page, /Continue as Gonzalo Munoz/, vmPage('vm-demo01', '?tab=monitoring'))
    const cpu = chart(page, 'CPU used, percent')
    await expect(cpu).toBeVisible()
    await expect(page.getByRole('figure')).toHaveCount(3)
    await expect(chart(page, 'Memory used, percent')).toBeVisible()
    await expect(chart(page, 'Disk used, percent')).toBeVisible()
    await expect(cpu.locator('[data-summary]')).toHaveText(/^Latest \d+\.\d%, average \d+\.\d%, highest \d+\.\d% at \w{3} \d+ \w{3}, \d\d:\d\d\.$/)
    // a day by default, a point every 5 minutes; web-01's alarm draws its line on the CPU chart
    const ranges = page.getByRole('radiogroup', { name: 'Time range' })
    await expect(ranges.getByRole('radio', { name: '1 day' })).toHaveAttribute('aria-checked', 'true')
    await expect(page.locator('[data-metrics-status]')).toContainText('a point every 5 minutes')
    await expect(cpu.locator('[data-threshold="90"]')).toHaveCount(1)
    await expect(cpu.locator('[data-threshold="90"]')).toContainText('web-01 CPU above 90% (OK)')
    await expect(page.locator('[data-alarm="web-01-cpu-high"]')).toContainText('OK')
    await page.screenshot({ path: shot('monitoring-desktop'), fullPage: true })
    await expectNoSeriousA11y(page, 'Monitoring tab')

    await ranges.getByRole('radio', { name: '1 hour' }).click()
    await expect(page).toHaveURL(/range=1h/)
    await expect(page).toHaveURL(/tab=monitoring/)
    await expect(page.locator('[data-metrics-status]')).toContainText('a point every minute')
    await page.reload()
    await expect(page.getByRole('radiogroup', { name: 'Time range' }).getByRole('radio', { name: '1 hour' })).toHaveAttribute('aria-checked', 'true')

    // the keyboard walks the points; the slider says the time and the value
    const slider = cpu.getByRole('slider')
    await slider.focus()
    await expect(slider).toHaveAttribute('aria-valuenow', '60')
    await expect(slider).toHaveAttribute('aria-valuetext', /^\w{3} \d+ \w{3}, \d\d:\d\d: \d+\.\d percent$/)
    await expect(cpu.locator('[data-readout]')).toBeVisible()
    await page.keyboard.press('ArrowLeft')
    await expect(slider).toHaveAttribute('aria-valuenow', '59')
    await page.keyboard.press('Home')
    await expect(slider).toHaveAttribute('aria-valuenow', '0')
    await page.keyboard.press('End')
    await expect(slider).toHaveAttribute('aria-valuenow', '60')

    // the same points as a table, newest first
    await cpu.getByRole('button', { name: 'Show as table' }).click()
    const table = cpu.getByRole('table')
    await expect(table).toBeVisible()
    await expect(table.getByRole('row')).toHaveCount(62)
    await expect(cpu.getByRole('button', { name: 'Show as chart' })).toHaveAttribute('aria-pressed', 'true')
    await cpu.getByRole('button', { name: 'Show as chart' }).click()
    await expect(cpu.getByRole('slider')).toBeVisible()

    // at phone width the charts and the alarms fit
    await onPhone(page, cpu)
    await expectNoHorizontalOverflow(page)
    await expectNoSeriousA11y(page, 'Monitoring tab at phone width')

    // in the dark appearance, the same charts and words at AA
    await page.setViewportSize({ width: 1280, height: 720 })
    await page.keyboard.press('ControlOrMeta+k')
    const palette = page.getByRole('dialog', { name: 'Command palette' })
    await expect(palette).toBeVisible()
    await page.keyboard.type('Use the dark appearance')
    await page.keyboard.press('Enter')
    await expect(palette).toBeHidden()
    await expect(page.locator('html')).toHaveClass(/dark/)
    await expect(cpu.getByRole('slider')).toBeVisible()
    await expectNoSeriousA11y(page, 'Monitoring tab, dark')
    await page.screenshot({ path: shot('monitoring-dark-desktop'), fullPage: true })
  })

  test('Dana creates an alarm that fires at once, sees its line on the chart, and deletes it', async ({ page }) => {
    await signInAs(page, /Continue as Dana Reyes/, vmPage('vm-demo01', '?tab=monitoring'))
    await expect(chart(page, 'CPU used, percent')).toBeVisible()
    await page.getByRole('button', { name: 'Create alarm' }).click()
    const dialog = page.getByRole('dialog', { name: 'Create an alarm' })
    await expect(dialog).toBeVisible()
    const threshold = dialog.getByLabel(/^Threshold/)
    await expect(threshold).toBeFocused()
    await threshold.fill('40')
    await dialog.getByLabel('Window, minutes').fill('5')
    await dialog.getByLabel('Name').fill('web-01 busy')
    await expect(dialog).toContainText('Fires when the average of CPU used over 5 minutes is above 40%.')
    await expectNoSeriousA11y(page, 'alarm dialog')
    await dialog.getByRole('button', { name: 'Create alarm' }).click()
    await expect(dialog).toHaveCount(0)

    const row = page.locator('[data-alarm]').filter({ hasText: 'web-01 busy' })
    await expect(row).toContainText('In alarm')
    await expect(row).toContainText('CPU used above 40% on average over 5 minutes')
    await expect(chart(page, 'CPU used, percent').locator('[data-threshold="40"]')).toContainText('web-01 busy (In alarm)')
    await page.screenshot({ path: shot('monitoring-alarm-desktop'), fullPage: true })

    // an edit keeps the alarm and its id
    await row.getByRole('button', { name: 'Edit' }).click()
    const edit = page.getByRole('dialog', { name: 'Edit web-01 busy' })
    await edit.getByLabel(/^Threshold/).fill('99')
    await edit.getByRole('button', { name: 'Save alarm' }).click()
    await expect(edit).toHaveCount(0)
    await expect(row).toContainText('OK')
    await expect(chart(page, 'CPU used, percent').locator('[data-threshold="99"]')).toHaveCount(1)

    await row.getByRole('button', { name: 'Delete' }).click()
    const confirm = page.getByRole('dialog', { name: 'Delete web-01 busy?' })
    await confirm.getByRole('button', { name: 'Delete alarm' }).click()
    await expect(row).toHaveCount(0)
    await expect(chart(page, 'CPU used, percent').locator('[data-threshold="99"]')).toHaveCount(0)
  })

  test('the Alarms page lists the account’s alarms firing first, filters by state and leads to the resource’s Monitoring tab', async ({ page }) => {
    await signInAs(page, /Continue as Gonzalo Munoz/, '/operations/alarms')
    await expect(page.getByRole('heading', { name: 'Alarms', level: 1 })).toBeVisible()
    const table = page.getByRole('table', { name: /Alarms of Compute/ })
    const rows = table.getByRole('row')
    await expect(rows.nth(1)).toContainText('shop-01 doing nothing')
    await expect(rows.nth(1)).toContainText('In alarm')
    await expect(rows.nth(2)).toContainText('Not enough data')
    await expect(rows).toHaveCount(6)
    await page.screenshot({ path: shot('alarms-desktop'), fullPage: true })
    await expectNoSeriousA11y(page, 'Alarms page')

    await page.getByRole('radiogroup', { name: 'State' }).getByRole('radio', { name: 'In alarm' }).click()
    await expect(page).toHaveURL(/state=alarm/)
    await expect(rows).toHaveCount(2)
    await rows.nth(1).getByRole('link', { name: 'vm-demo06' }).click()
    await expect(page).toHaveURL(/tab=monitoring/)
    await expect(chart(page, 'CPU used, percent').locator('[data-threshold="5"]')).toContainText('shop-01 doing nothing (In alarm)')

    await page.goto('/#/operations/alarms')
    await onPhone(page, table)
    await expectNoHorizontalOverflow(page)
    await expectNoSeriousA11y(page, 'Alarms page at phone width')
  })

  test('Dana resizes an oversized machine through its update workflow and follows the request, then dismisses another recommendation for 30 days', async ({ page }) => {
    test.setTimeout(90_000)
    await signInAs(page, /Continue as Dana Reyes/, '/operations/rightsizing')
    await expect(page.getByRole('heading', { name: 'Rightsizing', level: 1 })).toBeVisible()
    const summary = page.getByRole('region', { name: 'Summary' })
    await expect(summary).toContainText('Open recommendations')
    await expect(summary).toContainText('$')
    const table = page.getByRole('table', { name: /Open recommendations/ })
    const web02 = table.getByRole('row').filter({ has: page.getByRole('link', { name: 'web-02', exact: true }) })
    await expect(web02).toContainText('Resize from 4 CPUs and 16 GB of memory to 2 CPUs and 8 GB of memory')
    await expect(web02).toContainText('$38.50')
    // an operator may resize but not delete: the idle machine's Delete is locked, with the reason behind it
    const shop01 = table.getByRole('row').filter({ has: page.getByRole('link', { name: 'shop-01', exact: true }) })
    await expect(shop01.getByRole('button', { name: /^Delete/ })).toHaveAttribute('data-denied', 'console-iam')
    await page.screenshot({ path: shot('rightsizing-desktop'), fullPage: true })
    await expectNoSeriousA11y(page, 'Rightsizing page')

    await web02.getByRole('button', { name: 'Resize' }).click()
    const confirm = page.getByRole('dialog', { name: 'Resize web-02?' })
    await expect(confirm).toContainText('From 4 CPUs and 16 GB of memory to 2 CPUs and 8 GB of memory')
    await confirm.getByRole('button', { name: 'Resize' }).click()
    const started = page.locator('[data-started]')
    await expect(started).toContainText('Resize of web-02 requested.')
    await expect(web02).toHaveCount(0)
    await page.getByRole('radiogroup', { name: 'Status' }).getByRole('radio', { name: 'Applied' }).click()
    await expect(page.getByRole('table', { name: /Applied recommendations/ }).getByRole('row').filter({ has: page.getByRole('link', { name: 'web-02', exact: true }) })).toContainText('Applied by dana')

    // the request runs the update workflow: an approval for production, then the apply
    await started.getByRole('link', { name: /View request job_/ }).click()
    await expect(page).toHaveURL(/#\/requests\/job_/)
    await expect(page.getByText('Succeeded').first()).toBeVisible({ timeout: 45_000 })

    // dismissing another one for 30 days, with a reason
    await page.goto('/#/operations/rightsizing')
    const cache = page.getByRole('table', { name: /Open recommendations/ }).getByRole('row').filter({ has: page.getByRole('link', { name: 'cache-067', exact: true }) })
    await cache.getByRole('button', { name: 'Dismiss' }).click()
    const dismiss = page.getByRole('dialog', { name: 'Dismiss the recommendation for cache-067' })
    await dismiss.getByRole('radio', { name: '30 days' }).click()
    await dismiss.getByLabel('Reason').fill('Kept for the sale in November')
    await expectNoSeriousA11y(page, 'dismiss dialog')
    await dismiss.getByRole('button', { name: 'Dismiss' }).click()
    await expect(dismiss).toHaveCount(0)
    await expect(cache).toHaveCount(0)
    await page.getByRole('radiogroup', { name: 'Status' }).getByRole('radio', { name: 'Dismissed' }).click()
    const dismissed = page.getByRole('table', { name: /Dismissed recommendations/ }).getByRole('row').filter({ has: page.getByRole('link', { name: 'cache-067', exact: true }) })
    await expect(dismissed).toContainText('Kept for the sale in November')
    await expect(dismissed).toContainText('Until')

    await onPhone(page, dismissed)
    await expectNoHorizontalOverflow(page)
    await expectNoSeriousA11y(page, 'Rightsizing page at phone width')
  })

  test('Gonzalo deletes an idle machine only after typing its name', async ({ page }) => {
    await signInAs(page, /Continue as Gonzalo Munoz/, '/operations/rightsizing?kind=idle')
    const shop01 = page.getByRole('table', { name: /Open recommendations/ }).getByRole('row').filter({ has: page.getByRole('link', { name: 'shop-01', exact: true }) })
    await shop01.getByRole('button', { name: 'Delete' }).click()
    const confirm = page.getByRole('dialog', { name: 'Delete shop-01?' })
    await expect(confirm).toContainText('idle for 14 days')
    const go = confirm.getByRole('button', { name: 'Delete' })
    await expect(go).toBeDisabled()
    await confirm.getByLabel(/Type shop-01 to confirm/).fill('shop-01')
    await go.click()
    await expect(page.locator('[data-started]')).toContainText('Delete of shop-01 requested.')
    await expect(shop01).toHaveCount(0)
  })

  test('Mia, a viewer, reads the charts and the recommendations, and every write she may not make is locked and explained', async ({ page }) => {
    await signInAs(page, /Continue as Mia Chen/, vmPage('vm-demo06', '?tab=monitoring'))
    await expect(chart(page, 'CPU used, percent')).toBeVisible()
    const create = page.getByRole('button', { name: /^Create alarm/ })
    await expect(create).toHaveAttribute('data-denied', 'console-iam')
    await create.click()
    const refusal = page.getByRole('dialog', { name: /You can.t create an alarm on shop-01 here/ })
    await expect(refusal).toContainText('compute:update')
    await expect(page.locator('[data-refusal="console-iam"]')).toBeAttached()
    await page.keyboard.press('Escape')
    await expect(refusal).toHaveCount(0)
    await expect(page.locator('[data-alarm="shop-01-doing-nothing"]').getByRole('button', { name: /^Edit/ })).toHaveAttribute('data-denied', 'console-iam')

    await page.goto('/#/operations/rightsizing')
    const row = page.getByRole('table', { name: /Open recommendations/ }).getByRole('row').filter({ has: page.getByRole('link', { name: 'web-02', exact: true }) })
    await expect(row.getByRole('button', { name: /^Resize/ })).toHaveAttribute('data-denied', 'console-iam')
    await expect(row.getByRole('button', { name: /^Dismiss/ })).toHaveAttribute('data-denied', 'console-iam')
    await row.getByRole('button', { name: /^Resize/ }).click()
    await expect(page.getByRole('dialog', { name: /You can.t resize web-02 here/ })).toContainText('compute:update')
    await page.screenshot({ path: shot('rightsizing-refused-desktop'), fullPage: true })
  })
})
