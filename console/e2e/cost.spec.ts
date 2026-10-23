import { createHash } from 'node:crypto'
import { readFile } from 'node:fs/promises'
import { fileURLToPath } from 'node:url'
import { expect, test, type Locator, type Page } from '@playwright/test'
import { expectNoHorizontalOverflow, expectNoSeriousA11y, signInAs, tabTo } from './support'

/*
 * Cost, budgets and chargeback (WP 7.3, 9.3 and 9.9), in mock mode: the Cost Explorer grouped,
 * filtered, ranged and scoped with everything in its URL, its chart read by keyboard, its table
 * and its CSV; a budget created, changed after someone else changed it first, and deleted, and a
 * budget's own page; a viewer who reads cost and budgets but may neither create a budget nor
 * export the chargeback; a closed month's chargeback whose CSV checks out byte for byte against
 * the published SHA-256, and the month so far; a resource's monthly cost on its page; the home's
 * cost widget; a budget that refuses a new resource in its review; and a phone. The amounts come
 * from the mock's ledger, which moves with the clock, so the journeys check their shape, not their
 * value.
 */

const shots = fileURLToPath(new URL('./screenshots/', import.meta.url))
const shot = (name: string) => `${shots}${name}.png`

const ADMIN = /Continue as Gonzalo Munoz/
const VIEWER = /Continue as Mia Chen/
const ACCOUNT = '123456789012'
const vmPage = (id: string) => `/console/compute/vm/${encodeURIComponent(`wfrn:wf:compute:global:${ACCOUNT}:vm/${id}`)}`

/** Money as the pages write it, "$1,234.56". */
const MONEY = /\$[\d,]+\.\d{2}/

const DAY_MS = 86_400_000
const isoDay = (t: number) => new Date(t).toISOString().slice(0, 10)
const longDay = (day: string) => new Date(`${day}T00:00:00Z`).toLocaleDateString('en-US', { weekday: 'long', year: 'numeric', month: 'long', day: 'numeric', timeZone: 'UTC' })

/** The month `offset` months from this one (UTC), as "2026-09" and as "September 2026". */
function month(offset: number): { key: string; name: string } {
  const now = new Date()
  const d = new Date(Date.UTC(now.getUTCFullYear(), now.getUTCMonth() + offset, 1))
  return { key: d.toISOString().slice(0, 7), name: d.toLocaleString('en-US', { month: 'long', year: 'numeric', timeZone: 'UTC' }) }
}

const escape = (s: string) => s.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')

/** An Atlas Select, named by its label and what it shows: "Group by: Service". */
const select = (page: Page | Locator, label: string) => page.getByRole('button', { name: new RegExp(`^${escape(label)}: `) })

/** Picks an option of a Select found by its label, and waits for the Select to show it. */
async function choose(page: Page, label: string, option: string) {
  await select(page, label).click()
  await page.getByRole('option', { name: option, exact: true }).click()
  await expect(select(page, label)).toHaveAccessibleName(`${label}: ${option}`)
}

/** Clicks what saves a file and returns the file: its name and its bytes. */
async function download(page: Page, button: Locator): Promise<{ name: string; bytes: Buffer }> {
  const saving = page.waitForEvent('download')
  await button.click()
  const file = await saving
  return { name: file.suggestedFilename(), bytes: await readFile(await file.path()) }
}

/** A fact of a detail page, found by its label. */
const fact = (page: Page, label: string) => page.locator('dl > div').filter({ has: page.locator('dt', { hasText: new RegExp(`^${escape(label)}$`) }) }).locator('dd')

/** Nothing on the page is waiting for an answer: no skeleton, no dimmed frame. */
async function loaded(page: Page) {
  await expect(page.locator('[aria-busy="true"]')).toHaveCount(0)
}

/** Someone else changes a budget's amount through the BFF while this page holds an older version. */
async function changeElsewhere(page: Page, budgetId: string, amount: number) {
  const status = await page.evaluate(
    async ({ id, amount }) => {
      const session = (await (await fetch('/console/v1/session')).json()) as { csrfToken: string }
      const budget = (await (await fetch(`/console/v1/budgets/${id}`)).json()) as { name: string; thresholds: number[]; blockCreates: boolean; version: number }
      const res = await fetch(`/console/v1/budgets/${id}`, {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json', 'If-Match': `"${budget.version}"`, 'X-Csrf-Token': session.csrfToken },
        body: JSON.stringify({ name: budget.name, amount, thresholds: budget.thresholds, blockCreates: budget.blockCreates }),
      })
      return res.status
    },
    { id: budgetId, amount },
  )
  expect(status, 'the change made elsewhere is saved').toBe(200)
}

test.describe('cost, budgets and chargeback in mock mode', () => {
  test('the Cost Explorer: grouped, filtered, ranged and scoped, as a chart, a table and a CSV, and every view a link', async ({ page }) => {
    await signInAs(page, ADMIN, '/cost')
    await expect(page.getByRole('heading', { name: 'Cost and usage', level: 1 })).toBeVisible()
    await expect(page.getByText('What Compute cost by the platform\'s price lists, by day or by month.')).toBeVisible()
    for (const stat of ['Total', `Forecast for ${month(0).name}`, 'Monthly run rate', 'Not priced']) await expect(page.getByText(stat, { exact: true }).first()).toBeVisible()
    await expect(page.getByText(/^Likely \$[\d,]+\.\d{2} to \$[\d,]+\.\d{2}$/)).toBeVisible()
    await expect(page.getByText('Estimates from list prices, not invoices.').first()).toBeVisible()
    await expect(page.getByText(/^Updated .+\. Closed through .+; today is provisional, drawn fainter\.$/)).toBeVisible()

    // this month by day, one column a day, grouped by service to start with
    await expect(select(page, 'Date range')).toHaveAccessibleName('Date range: Month to date')
    await expect(page.getByRole('radio', { name: 'Daily' })).toBeChecked()
    await expect(page.getByRole('heading', { name: 'Daily cost by service' })).toBeVisible()
    await expect(page.locator('[data-bar]')).toHaveCount(new Date().getUTCDate())
    await expect(page.getByRole('table', { name: /^Cost by service, / })).toBeVisible()

    // by a tag: a column per value, a legend, the same colours in the table
    await choose(page, 'Group by', 'Tag: environment')
    await expect(page).toHaveURL(/groupBy=tag(%3A|:)environment/)
    await expect(page.getByRole('heading', { name: 'Daily cost by environment tag' })).toBeVisible()
    const legend = page.getByRole('list', { name: 'Legend' })
    await expect(legend.getByRole('listitem')).not.toHaveCount(0)
    await expect(legend).toContainText('prod')
    await expect(page.getByRole('table', { name: /^Cost by environment tag, / }).getByRole('row', { name: /^prod / })).toContainText(MONEY)
    await loaded(page)
    // tall enough for the chart under the figures
    await page.setViewportSize({ width: 1280, height: 1000 })
    await page.screenshot({ path: shot('cost-explorer-desktop'), fullPage: true })
    await page.setViewportSize({ width: 1280, height: 720 })
    await expectNoSeriousA11y(page, 'Cost Explorer')

    // filters: a service, then a tag typed as key=value (a malformed one is said so)
    await choose(page, 'Service', 'Compute')
    await expect(page).toHaveURL(/service=compute/)
    const parameters = page.getByRole('group', { name: 'Report parameters' })
    const tag = page.locator('#cost-tag')
    await tag.fill('environment')
    await tag.press('Enter')
    await expect(parameters.getByRole('alert')).toHaveText('Write a tag as key=value.')
    await tag.fill('environment=prod')
    await tag.press('Enter')
    await expect(page).toHaveURL(/tag=environment(%3D|=)prod/)
    await expect(parameters.getByRole('alert')).toHaveCount(0)
    await loaded(page)
    await expect(page.getByRole('list', { name: 'Legend' })).toHaveCount(0)
    await tag.fill('')
    await tag.press('Enter')
    await expect(page).not.toHaveURL(/tag=/)
    await choose(page, 'Service', 'All services')
    await expect(page).not.toHaveURL(/service=/)

    // a custom range: the days on screen to start with, by day while it is short
    await choose(page, 'Date range', 'Custom')
    await expect(page.locator('#cost-from')).toHaveValue(`${month(0).key}-01`)
    await page.locator('#cost-from').fill(`${month(-1).key}-15`)
    await expect(page).toHaveURL(new RegExp(`from=${month(-1).key}-15`))
    await expect(page.getByRole('radio', { name: 'Daily' })).toBeChecked()

    // three months by month, for every account, by account
    await choose(page, 'Date range', 'Last 3 months')
    await expect(page.getByRole('radio', { name: 'Monthly' })).toBeChecked()
    await expect(page).toHaveURL(/range=3m/)
    await expect(page).not.toHaveURL(/from=/)
    await page.getByRole('radio', { name: 'Organization' }).click()
    await expect(page).toHaveURL(/scope=organization/)
    await expect(page.getByText('What your accounts cost by the platform\'s price lists, by day or by month.')).toBeVisible()
    await choose(page, 'Group by', 'Account')
    await expect(page.getByRole('heading', { name: 'Monthly cost by account' })).toBeVisible()
    await expect(page.locator('[data-bar]')).toHaveCount(3)
    const byAccount = page.getByRole('table', { name: /^Cost by account, / })
    await expect(byAccount.getByRole('row', { name: /^Compute / })).toBeVisible()
    await expect(byAccount.getByRole('row', { name: /^Data / })).toBeVisible()

    // the chart's numbers as a table
    await page.getByRole('radio', { name: 'Table' }).click()
    await expect(page).toHaveURL(/view=table/)
    const periods = page.getByRole('table', { name: /^Monthly cost, / })
    await expect(periods.getByRole('row')).toHaveCount(4)
    await expect(periods.getByRole('row').last()).toContainText('provisional')
    await loaded(page)
    await expectNoSeriousA11y(page, 'Cost Explorer as a table')

    // the view is a link: a reload draws the same one
    await page.reload()
    await expect(select(page, 'Date range')).toHaveAccessibleName('Date range: Last 3 months')
    await expect(select(page, 'Group by')).toHaveAccessibleName('Group by: Account')
    await expect(page.getByRole('radio', { name: 'Organization' })).toBeChecked()
    await expect(page.getByRole('radio', { name: 'Table' })).toBeChecked()
    await expect(page.getByRole('table', { name: /^Monthly cost, / })).toBeVisible()

    // the CSV is the table: a row per month, a column per account, the totals last
    const csv = await download(page, page.getByRole('button', { name: 'Download CSV' }))
    expect(csv.name).toMatch(/^cost-\d{4}-\d{2}-01-to-\d{4}-\d{2}-\d{2}-by-account\.csv$/)
    const lines = csv.bytes.toString('utf8').replace(/^\uFEFF/, '').trim().split(/\r?\n/)
    expect(lines[0]).toMatch(/^Month,(.+,)?Total,Currency,Provisional$/)
    expect(lines[0]).toContain('Compute')
    expect(lines[0]).toContain('Data')
    expect(lines).toHaveLength(5)
    expect(lines[4]).toMatch(/^Total,/)
  })

  test('the chart is read by keyboard, a column at a time, and the explorer has a dark mode', async ({ page }) => {
    await signInAs(page, ADMIN, '/cost?groupBy=tag:environment')
    await expect(page.getByRole('group', { name: /^Daily cost by environment tag, / })).toBeVisible()
    await loaded(page)
    // the pointer away from the chart, so only the focus shows a tooltip
    await page.mouse.move(0, 0)
    const now = Date.now()
    const today = isoDay(now)
    const first = `${today.slice(0, 8)}01`
    const column = (day: string) => page.locator(`[data-bar="${day}"]`)

    // one stop in the tab order, on today's column, which says it is still open
    await tabTo(page, page.locator('[data-bar][tabindex="0"]'), 60)
    await expect(column(today)).toBeFocused()
    await expect(column(today)).toHaveAttribute('aria-label', new RegExp(`^${escape(longDay(today))}: \\$[\\d,]+\\.\\d{2}\\..*Provisional: the day is still open$`))
    // the tooltip shows on focus as on hover, with each value
    await expect(page.getByText(longDay(today), { exact: true })).toBeVisible()
    if (today !== first) {
      await page.keyboard.press('ArrowLeft')
      await expect(column(isoDay(now - DAY_MS))).toBeFocused()
      await expect(page.getByText(longDay(isoDay(now - DAY_MS)), { exact: true })).toBeVisible()
    }
    await page.keyboard.press('Home')
    await expect(column(first)).toBeFocused()
    await page.keyboard.press('End')
    await expect(column(today)).toBeFocused()
    await page.keyboard.press('Escape')
    await expect(page.getByText(longDay(today), { exact: true })).toHaveCount(0)
    await page.keyboard.press('Tab')
    await expect(page.locator('[data-bar]:focus')).toHaveCount(0)

    // dark: the series take the palette's dark steps
    await page.getByRole('button', { name: 'Switch to dark mode' }).click()
    await expect(page.locator('html')).toHaveClass(/dark/)
    await page.mouse.move(0, 0)
    await loaded(page)
    await page.screenshot({ path: shot('cost-explorer-dark-desktop'), fullPage: true })
    await expectNoSeriousA11y(page, 'Cost Explorer in dark mode')
    await page.getByRole('button', { name: 'Switch to light mode' }).click()
    await expect(page.locator('html')).not.toHaveClass(/dark/)
  })

  test('Gonzalo creates a budget, changes it after someone else changed it first, and deletes it', async ({ page }) => {
    await signInAs(page, ADMIN, '/cost/budgets')
    await expect(page.getByRole('heading', { name: 'Budgets', level: 1 })).toBeVisible()
    const list = page.getByRole('table', { name: 'Budgets of Compute' })
    for (const name of ['Compute monthly', 'Production', 'Test environments']) await expect(list.getByRole('link', { name })).toBeVisible()
    await expect(page.getByText(/^3 budgets(, 1 over)?(, 1 forecast to go over)?(, all on course)?\.$/)).toBeVisible()
    await expect(list.getByRole('progressbar', { name: /^Production: [\d.]+% of the amount spent$/ })).toBeVisible()
    await page.screenshot({ path: shot('budgets-desktop'), fullPage: true })
    await expectNoSeriousA11y(page, 'budgets')

    // by keyboard: the drawer opens on its first field, and Escape gives the focus back
    const create = page.getByRole('button', { name: 'Create budget' })
    await expect(create).not.toHaveAttribute('data-denied')
    await tabTo(page, create)
    await page.keyboard.press('Enter')
    const drawer = page.getByRole('dialog', { name: 'Create budget' })
    await expect(drawer).toBeVisible()
    await expect(drawer.locator('#budget-name')).toBeFocused()
    await page.keyboard.press('Escape')
    await expect(drawer).toBeHidden()
    await expect(create).toBeFocused()

    // the form checks what it can, the cost service the rest (a name the account has already)
    await create.click()
    await drawer.getByRole('button', { name: 'Create budget' }).click()
    await expect(drawer.getByText('A budget needs a name.')).toBeVisible()
    await expect(drawer.getByText(/^An amount above 0/)).toBeVisible()
    await expectNoSeriousA11y(page, 'budget drawer with problems')
    await drawer.locator('#budget-name').fill('Production')
    await drawer.locator('#budget-amount').fill('250')
    await drawer.getByRole('button', { name: 'Create budget' }).click()
    await expect(drawer.getByText('The account has a budget named Production already')).toBeVisible()

    // a tag's budget with a third threshold
    await drawer.locator('#budget-name').fill('Staging')
    await drawer.getByRole('radio', { name: 'Resources with a tag' }).click()
    await expect(drawer.locator('#budget-tag-key')).toHaveValue('environment')
    await drawer.locator('#budget-tag-value').fill('dev')
    await drawer.getByRole('button', { name: 'Add threshold' }).click()
    await drawer.getByRole('textbox', { name: 'Threshold 3, percent of the amount' }).fill('50')
    await page.screenshot({ path: shot('budget-drawer-desktop'), fullPage: true })
    await expectNoSeriousA11y(page, 'budget drawer')
    await drawer.getByRole('button', { name: 'Create budget' }).click()

    // its page
    await expect(page).toHaveURL(/#\/cost\/budgets\/bud_[0-9a-z]{26}$/)
    await expect(page.getByRole('heading', { name: 'Staging', level: 1 })).toBeVisible()
    await expect(page.getByText('Budget created')).toBeVisible()
    await expect(page.getByText('Resources tagged environment = dev')).toBeVisible()
    await expect(fact(page, 'Amount')).toHaveText('$250.00 a month')
    await expect(fact(page, 'Thresholds')).toHaveText('50%, 80% and 100%')
    await expect(fact(page, 'Blocks creates')).toHaveText('No')
    const budgetId = /bud_[0-9a-z]{26}/.exec(page.url())![0]

    // someone else changes it while the drawer is open: the drawer says so and loads their version
    await page.getByRole('button', { name: 'Edit', exact: true }).click()
    const edit = page.getByRole('dialog', { name: 'Edit budget' })
    await expect(edit.getByRole('heading', { name: 'Edit Staging' })).toBeVisible()
    await expect(edit.locator('#budget-amount')).toHaveValue('250.00')
    await edit.locator('#budget-amount').fill('300')
    await changeElsewhere(page, budgetId, 275)
    await edit.getByRole('button', { name: 'Save changes' }).click()
    await expect(edit.getByRole('alert')).toContainText('Someone changed this budget first')
    await expect(edit.getByRole('button', { name: 'Save changes' })).toBeDisabled()
    await expectNoSeriousA11y(page, 'budget drawer after a conflict')
    await edit.getByRole('button', { name: 'Reload' }).click()
    await expect(edit.getByText('This is the budget as it is now. Make your change again and save.')).toBeVisible()
    await expect(edit.locator('#budget-amount')).toHaveValue('275.00')
    await edit.locator('#budget-amount').fill('300')
    await edit.getByRole('button', { name: 'Save changes' }).click()
    await expect(edit).toBeHidden()
    await expect(page.getByText('Budget saved')).toBeVisible()
    await expect(fact(page, 'Amount')).toHaveText('$300.00 a month')

    // deleted, after a confirmation
    await page.getByRole('button', { name: 'Delete', exact: true }).click()
    const confirm = page.getByRole('dialog', { name: 'Delete Staging?' })
    await expect(confirm).toBeVisible()
    await expectNoSeriousA11y(page, 'delete budget confirmation')
    await confirm.getByRole('button', { name: 'Delete budget' }).click()
    await expect(page).toHaveURL(/#\/cost\/budgets$/)
    await expect(page.getByText('Budget deleted')).toBeVisible()
    await expect(list.getByRole('link', { name: 'Production' })).toBeVisible()
    await expect(list.getByRole('link', { name: 'Staging' })).toHaveCount(0)
  })

  test('a budget\'s page: the month against the amount, the months before, and what counts', async ({ page }) => {
    await signInAs(page, ADMIN, '/cost/budgets')
    await page.getByRole('table', { name: 'Budgets of Compute' }).getByRole('link', { name: 'Production' }).click()
    await expect(page).toHaveURL(/#\/cost\/budgets\/bud_/)
    await expect(page.getByRole('heading', { name: 'Production', level: 1 })).toBeVisible()
    await expect(page.getByText('Resources tagged environment = prod').first()).toBeVisible()
    await expect(fact(page, `Spent in ${month(0).name}`)).toHaveText(/^\$[\d,]+\.\d{2}, [\d.]+%$/)
    await expect(fact(page, 'Blocks creates')).toHaveText('Yes, while over')
    await expect(fact(page, 'Thresholds')).toHaveText('80% and 100%')
    const chart = page.getByRole('img', { name: new RegExp(`^Spent so far in ${month(0).name}: \\$[\\d,]+\\.\\d{2} of \\$[\\d,]+\\.\\d{2}, forecast \\$[\\d,]+\\.\\d{2} by the month's end$`) })
    await expect(chart).toBeVisible()
    const months = page.getByRole('table').filter({ hasText: `${month(0).name} (so far)` })
    await expect(months).toBeVisible()
    await loaded(page)
    await page.setViewportSize({ width: 1280, height: 1000 })
    await page.screenshot({ path: shot('budget-desktop'), fullPage: true })
    await page.setViewportSize({ width: 1280, height: 720 })
    await expectNoSeriousA11y(page, 'budget')

    // the month as a table, day by day
    await page.getByRole('radio', { name: 'Table' }).click()
    const days = page.getByRole('table', { name: `Spend by day in ${month(0).name}` })
    await expect(days.getByRole('row')).toHaveCount(new Date().getUTCDate() + 1)

    // what counts, in the Cost Explorer
    await page.getByRole('link', { name: 'See this month in the Cost Explorer' }).click()
    await expect(page).toHaveURL(/#\/cost\?.*tag=environment(%3D|=)prod/)
    await expect(page.locator('#cost-tag')).toHaveValue('environment=prod')
    await expect(page.getByRole('heading', { name: 'Daily cost by service' })).toBeVisible()
  })

  test('Mia, a viewer, reads cost and budgets but may not create or change a budget, or export the chargeback', async ({ page }) => {
    await signInAs(page, VIEWER, '/cost/budgets')
    await expect(page.getByRole('heading', { name: 'Budgets', level: 1 })).toBeVisible()
    await expect(page.getByRole('table', { name: 'Budgets of Compute' }).getByRole('link', { name: 'Production' })).toBeVisible()
    const create = page.getByRole('button', { name: 'Create budget' })
    await expect(create).toHaveAttribute('data-denied', 'console-iam')
    await create.click()
    let refusal = page.getByRole('dialog', { name: /You can.t create budget here/ })
    await expect(refusal).toBeVisible()
    await expect(refusal.getByText('Your role does not allow budgets:create')).toBeVisible()
    await expectNoSeriousA11y(page, 'refusal to create a budget')
    await page.keyboard.press('Escape')
    await expect(refusal).toBeHidden()

    // a budget's changes follow what the BFF says it allows
    await page.getByRole('table', { name: 'Budgets of Compute' }).getByRole('link', { name: 'Production' }).click()
    await expect(page.getByRole('heading', { name: 'Production', level: 1 })).toBeVisible()
    const edit = page.getByRole('button', { name: /^Edit\b/ })
    await expect(edit).toHaveAttribute('data-denied', 'console-iam')
    await expect(page.getByRole('button', { name: /^Delete\b/ })).toHaveAttribute('data-denied', 'console-iam')
    await edit.click()
    refusal = page.getByRole('dialog', { name: /You can.t edit budget here/ })
    await expect(refusal.getByText('Your role does not allow budgets:update')).toBeVisible()
    await page.keyboard.press('Escape')

    // the explorer reads, the chargeback is the exporters'
    await page.goto('/#/cost')
    await expect(page.getByText('Total', { exact: true }).first()).toBeVisible()
    await page.goto('/#/cost/chargeback')
    await expect(page.getByText('You cannot export the chargeback of Compute')).toBeVisible()
    await expect(page.getByRole('button', { name: 'Download CSV' })).toHaveAttribute('data-denied', 'console-iam')
    await expectNoSeriousA11y(page, 'chargeback refused')
    await page.getByRole('button', { name: 'See why', exact: true }).click()
    refusal = page.getByRole('dialog', { name: /You can.t download chargeback CSV here/ })
    await expect(refusal.getByText('Your role does not allow cost:export')).toBeVisible()
  })

  test('the chargeback: a closed month is final and its CSV checks out byte for byte; the month so far is provisional', async ({ page }) => {
    const closed = month(-1)
    const open = month(0)
    await signInAs(page, ADMIN, '/cost/chargeback')
    await expect(page.getByRole('heading', { name: 'Chargeback', level: 1 })).toBeVisible()
    await expect(select(page, 'Month')).toHaveAccessibleName(`Month: ${closed.name}`)
    await expect(page.getByText('Final', { exact: true })).toBeVisible()
    await expect(page.getByText(/^Published to finance .+ UTC; it never changes\.$/)).toBeVisible()
    const published = page.getByText(/^[0-9a-f]{64}$/)
    const rows = page.getByRole('region', { name: `Chargeback rows for ${closed.name}` })
    await expect(rows.getByRole('rowheader', { name: 'Subtotal, cost centre CC-4100' })).toBeVisible()
    await expect(rows.getByRole('rowheader', { name: 'Total', exact: true })).toBeVisible()
    await expectNoSeriousA11y(page, 'chargeback, a closed month')

    // the file as finance gets it: UTF-8 without a byte order mark, its SHA-256 the published one
    let file = await download(page, page.getByRole('button', { name: 'Download CSV' }))
    expect(file.name).toBe(`chargeback-${closed.key}-${ACCOUNT}.csv`)
    expect(file.bytes[0]).not.toBe(0xef)
    expect(file.bytes.toString('utf8').split('\r\n')[0]).toBe('month,cost_centre,account_id,account_name,service,tag_key,tag_value,amount,currency,final')
    expect(createHash('sha256').update(file.bytes).digest('hex')).toBe(await published.innerText())
    await expect(page.getByRole('status').filter({ hasText: 'Its SHA-256 matches the published month.' })).toContainText('Checked')

    // every account, split by a tag: one more cost centre, a column for the tag, another checksum
    await page.getByRole('radio', { name: 'Organization' }).click()
    await choose(page, 'Split by tag', 'environment')
    await expect(page).toHaveURL(/scope=organization/)
    await expect(page).toHaveURL(/tag=environment/)
    await expect(rows.getByRole('columnheader', { name: 'Tag: environment' })).toBeVisible()
    await expect(rows.getByRole('rowheader', { name: 'Subtotal, cost centre CC-4200' })).toBeVisible()
    await loaded(page)
    file = await download(page, page.getByRole('button', { name: 'Download CSV' }))
    expect(file.name).toBe(`chargeback-${closed.key}-organization-by-environment.csv`)
    expect(createHash('sha256').update(file.bytes).digest('hex')).toBe(await published.innerText())
    await expect(page.getByRole('status').filter({ hasText: 'Its SHA-256 matches the published month.' })).toBeVisible()
    await page.screenshot({ path: shot('chargeback-desktop'), fullPage: true })

    // the month so far: provisional, nothing published to check against
    await choose(page, 'Month', `${open.name} (so far)`)
    await expect(page.getByText('Provisional', { exact: true })).toBeVisible()
    await expect(page.getByText(/^As of .+ UTC; it grows until the month closes\.$/)).toBeVisible()
    await expect(published).toHaveCount(0)
    await loaded(page)
    file = await download(page, page.getByRole('button', { name: 'Download CSV' }))
    expect(file.name).toBe(`chargeback-${open.key}-organization-by-environment.csv`)
    expect(file.bytes.toString('utf8').split('\r\n')[1]).toMatch(/,false$/)
    await expect(page.getByRole('status').filter({ hasText: `${open.name} is still open, so nothing is published to check it against` })).toBeVisible()
    await expectNoSeriousA11y(page, 'chargeback, the month so far')
  })

  test('a resource\'s page prices it and links to the explorer narrowed to it; the home shows the month so far', async ({ page }) => {
    await signInAs(page, ADMIN, vmPage('vm-demo02'))
    await expect(page.getByText('Monthly cost', { exact: true })).toBeVisible()
    const cost = page.locator('[data-cost="priced"]')
    await expect(cost).toContainText(/^\$[\d,]+\.\d{2} a month/)
    await expect(cost).toContainText(/\$[\d,]+\.\d{2} this month so far\./)
    await expect(cost.getByRole('button', { name: /^How it is priced: Priced by the price list list-prices: vm\.create on vsphere/ })).toBeVisible()
    await expectNoSeriousA11y(page, 'resource page with its monthly cost')
    await cost.getByRole('link', { name: 'See in Cost Explorer' }).click()
    await expect(page).toHaveURL(/#\/cost\?.*wfrn=/)
    await expect(page.getByText('One resource:')).toBeVisible()
    await expect(page.getByRole('heading', { name: 'Daily cost', exact: true })).toBeVisible()
    await page.getByRole('button', { name: 'Show every resource' }).click()
    await expect(page.getByText('One resource:')).toHaveCount(0)
    await expect(page).not.toHaveURL(/wfrn=/)

    await page.goto('/#/')
    const widget = page.locator('section').filter({ has: page.getByRole('heading', { name: 'Cost and usage', level: 2 }) })
    await expect(widget.getByText('Month to date')).toBeVisible()
    await expect(widget.getByText(`${month(0).name} so far in Compute. Estimates from list prices, not invoices.`)).toBeVisible()
    await expect(widget.getByRole('heading', { name: 'Top services', level: 3 })).toBeVisible()
    await expect(widget.getByRole('list').getByRole('listitem')).not.toHaveCount(0)
    await expect(widget.getByRole('progressbar').first()).toHaveAccessibleName(/^.+: [\d.]+% of the month so far$/)
    await widget.getByRole('link', { name: 'Cost Explorer' }).click()
    await expect(page).toHaveURL(/#\/cost$/)
    await expect(page.getByRole('heading', { name: 'Cost and usage', level: 1 })).toBeVisible()
  })

  test('a budget over its amount that blocks creates refuses a new resource in its review, by name', async ({ page }) => {
    await signInAs(page, ADMIN, '/cost/budgets')
    await page.getByRole('button', { name: 'Create budget' }).click()
    const drawer = page.getByRole('dialog', { name: 'Create budget' })
    await drawer.locator('#budget-name').fill('Freeze')
    await drawer.locator('#budget-amount').fill('1')
    await drawer.getByRole('checkbox', { name: /^Block creates while over/ }).check()
    await drawer.getByRole('button', { name: 'Create budget' }).click()
    await expect(page.getByRole('heading', { name: 'Freeze', level: 1 })).toBeVisible()
    await expect(page.getByText('Over', { exact: true }).first()).toBeVisible()

    await page.goto('/#/console/compute/vm/create')
    await expect(page.getByRole('heading', { name: 'Create VM', level: 1 })).toBeVisible()
    await page.locator('#create-name').fill('web-77')
    await page.getByRole('button', { name: /^Size: / }).click()
    await page.getByRole('option', { name: /medium/ }).click()
    await page.getByRole('button', { name: 'Next' }).click()
    await page.getByRole('button', { name: /^Image id: / }).click()
    await page.getByRole('option', { name: /ubuntu-24\.04-cis-l1/ }).click()
    await page.getByRole('button', { name: 'Next' }).click()
    const preview = page.getByTestId('preview-card')
    await expect(preview).toContainText('Denied')
    await expect(preview).toContainText(/The budget Freeze is over: \$[\d,]+\.\d{2} of \$1\.00 spent in/)
  })

  test('fits a phone', async ({ page }) => {
    await page.setViewportSize({ width: 390, height: 844 })
    await signInAs(page, ADMIN, '/cost?groupBy=tag:environment')
    await expect(page.getByRole('group', { name: /^Daily cost by environment tag, / })).toBeVisible()
    await loaded(page)
    await expectNoHorizontalOverflow(page)
    await page.screenshot({ path: shot('cost-explorer-phone'), fullPage: true })

    await page.goto('/#/cost/budgets')
    await expect(page.getByRole('table', { name: 'Budgets of Compute' }).getByRole('link', { name: 'Production' })).toBeVisible()
    await expectNoHorizontalOverflow(page)
    await page.getByRole('table', { name: 'Budgets of Compute' }).getByRole('link', { name: 'Production' }).click()
    await expect(page.getByRole('heading', { name: 'Production', level: 1 })).toBeVisible()
    await expect(page.getByRole('img', { name: /^Spent so far in / })).toBeVisible()
    await expectNoHorizontalOverflow(page)

    await page.goto('/#/cost/chargeback?scope=organization&tag=environment')
    await expect(page.getByRole('region', { name: /^Chargeback rows for / })).toBeVisible()
    await loaded(page)
    await expectNoHorizontalOverflow(page)
    await page.screenshot({ path: shot('chargeback-phone'), fullPage: true })
  })
})
