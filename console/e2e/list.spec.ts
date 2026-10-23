import { readFile } from 'node:fs/promises'
import { fileURLToPath } from 'node:url'
import { expect, test, type Page } from '@playwright/test'
import { expectNoHorizontalOverflow, expectNoSeriousA11y, signInAs } from './support'

/*
 * Phase 3a's list, in mock mode: a service's landing counts each lifecycle state and each count
 * opens the list filtered to it; the list filters, sorts and pages on the server, keeps all of
 * that in its link, saves views and a column choice that follow the person across a reload, and
 * exports the page or a selection as CSV. Axe on each screen, and the list on a phone.
 */

const shots = fileURLToPath(new URL('./screenshots/', import.meta.url))
const shot = (name: string) => `${shots}${name}.png`

const ADMIN = /Continue as Gonzalo Munoz/

const grid = (page: Page) => page.getByRole('grid', { name: 'Virtual machines' })
const rows = (page: Page) => grid(page).getByRole('rowgroup').nth(1).getByRole('row')
const filterBox = (page: Page) => page.getByRole('combobox', { name: 'Filter' })
const viewsButton = (page: Page) => page.getByRole('button', { name: /^View: / })

/** The names in the rows on screen, top to bottom. */
async function namesOnScreen(page: Page, n = 5): Promise<string[]> {
  const out: string[] = []
  for (let i = 0; i < n; i++) out.push((await rows(page).nth(i).getByRole('gridcell').nth(1).innerText()).trim())
  return out
}

/** Adds a `property = value` filter the way a person picks it: the property, the operator, the value. */
async function addFilter(page: Page, property: string, value: string) {
  await filterBox(page).click()
  await filterBox(page).fill(property)
  await page.getByRole('option', { name: `${property} property` }).click()
  await page.getByRole('option', { name: /^= / }).click()
  await page.getByRole('option', { name: `${value} value` }).click()
}

test.describe('the resource list in mock mode', () => {
  test('the service landing counts each state, and a count opens the list filtered to it', async ({ page }) => {
    await signInAs(page, ADMIN, '/console/compute')
    await expect(page.getByRole('heading', { name: 'Compute', level: 1 })).toBeVisible()
    const tiles = page.getByRole('list', { name: 'Virtual machines by state' })
    await expect(tiles.getByRole('link', { name: /^Total \d/ })).toBeVisible()
    const activeTile = tiles.getByRole('link', { name: /^Active \d/ })
    const active = (await activeTile.innerText()).match(/\d[\d,]*/)?.[0]
    expect(active, 'the Active tile shows a count').toBeTruthy()
    await expectNoSeriousA11y(page, 'service landing')
    await page.screenshot({ path: shot('service-landing-desktop'), fullPage: true })

    await activeTile.click()
    await expect(page).toHaveURL(/#\/console\/compute\/vm\?f=status%3Dactive$/)
    await expect(page.getByText(`${active} virtual machines match`)).toBeVisible()
    await expect(page.getByRole('button', { name: 'Remove filter Status = active' })).toBeVisible()
    for (let i = 0; i < 5; i++) await expect(rows(page).nth(i)).toContainText('active')
  })

  test('filters, sorts and pages on the server, keeps it all in the link, and exports CSV', async ({ page }) => {
    await signInAs(page, ADMIN, '/console/compute/vm')
    await expect(rows(page).first()).toBeVisible()
    await expect(page.getByText(/^\d[\d,]*\+? virtual machines$/)).toBeVisible()

    // free text, to the server's q
    await filterBox(page).fill('web')
    await filterBox(page).press('Enter')
    await expect(page).toHaveURL(/f=%7Eweb/)
    await expect(page.getByText(/^\d[\d,]* virtual machines match$/)).toBeVisible()
    for (const name of await namesOnScreen(page)) expect(name).toContain('web')

    // the name column, the other way round
    const nameHeader = grid(page).getByRole('columnheader', { name: /^Name/ })
    await expect(nameHeader).toHaveAttribute('aria-sort', 'ascending')
    await nameHeader.getByRole('button').click()
    await expect(nameHeader).toHaveAttribute('aria-sort', 'descending')
    await expect(page).toHaveURL(/sort=-name/)
    const descending = await namesOnScreen(page)
    expect([...descending].sort().reverse(), 'the server sorts by name, descending').toEqual(descending)

    // 25 a page, and the next page
    await page.getByRole('button', { name: /^Rows per page/ }).click()
    await page.getByRole('option', { name: '25 per page' }).click()
    await expect(page).toHaveURL(/size=25/)
    const pager = page.getByRole('navigation', { name: 'Pagination' })
    await expect(pager).toContainText('Showing 25 virtual machines, more available · page 1')
    await page.getByRole('button', { name: 'Next page' }).click()
    await expect(pager).toContainText('page 2')
    const second = await namesOnScreen(page, 1)
    expect(second[0] < descending[0], 'the second page carries on where the first stopped').toBe(true)

    // the link keeps the list: a reload shows the same filter, sort and page size
    await page.reload()
    await expect(page.getByRole('button', { name: 'Remove filter "web"' })).toBeVisible()
    await expect(nameHeader).toHaveAttribute('aria-sort', 'descending')
    await expect(page.getByRole('button', { name: 'Rows per page: 25 per page' })).toBeVisible()

    // select two rows and export them
    const [first, next] = await namesOnScreen(page, 2)
    await grid(page).getByRole('checkbox', { name: `Select ${first}` }).check()
    await grid(page).getByRole('checkbox', { name: `Select ${next}` }).check()
    const selection = page.getByRole('region', { name: 'Selection' })
    await expect(selection).toContainText('2 virtual machines selected')
    await expectNoSeriousA11y(page, 'list with a selection')
    await page.screenshot({ path: shot('list-selection-desktop'), fullPage: true })
    const download = page.waitForEvent('download')
    await selection.getByRole('button', { name: 'Export selected' }).click()
    const file = await download
    expect(file.suggestedFilename()).toMatch(/^virtual-machines-\d{4}-\d{2}-\d{2}\.csv$/)
    const csv = await readFile(await file.path(), 'utf8')
    expect(csv.charCodeAt(0), 'a byte-order mark first, for spreadsheets').toBe(0xfeff)
    const lines = csv.slice(1).trimEnd().split('\r\n')
    expect(lines[0]).toMatch(/^Name,Status,wfrn,/)
    expect(lines.slice(1).map((l) => l.split(',')[0])).toEqual([first, next])
    await expect(page.getByText('Exported as CSV')).toBeVisible()

    await selection.getByRole('button', { name: 'Clear selection' }).click()
    await expect(selection).toBeHidden()
  })

  test('saves a view that follows the person, then applies, changes and deletes it', async ({ page }) => {
    await signInAs(page, ADMIN, '/console/compute/vm')
    await expect(rows(page).first()).toBeVisible()
    await addFilter(page, 'Status', 'active')
    await expect(page).toHaveURL(/f=status%3Dactive/)

    await viewsButton(page).click()
    const menu = page.getByRole('menu', { name: 'Saved views' })
    await expect(menu).toBeVisible()
    await expect(menu.getByRole('menuitemradio', { name: 'All virtual machines' })).toHaveAttribute('aria-checked', 'true')
    await expectNoSeriousA11y(page, 'saved views menu')
    await menu.getByRole('menuitem', { name: 'Save as a new view…' }).click()
    const dialog = page.getByRole('dialog', { name: 'Save this view' })
    await expect(dialog).toBeVisible()
    await expect(dialog).toContainText('Status is active')
    await dialog.getByRole('textbox', { name: /^Name/ }).fill('Active machines')
    await expectNoSeriousA11y(page, 'save view dialog')
    await page.screenshot({ path: shot('list-save-view-desktop'), fullPage: true })
    await dialog.getByRole('button', { name: 'Save view' }).click()
    await expect(dialog).toBeHidden()
    await expect(viewsButton(page)).toHaveText(/View: Active machines/)
    await expect(page).toHaveURL(/view=v_/)

    // the view is the person's: it is still there after a reload, from the BFF
    await page.goto('/#/console/compute/vm')
    await page.reload()
    await expect(viewsButton(page)).toHaveText(/View: All virtual machines/)
    await viewsButton(page).click()
    await menu.getByRole('menuitemradio', { name: /^Active machines/ }).click()
    await expect(page.getByRole('button', { name: 'Remove filter Status = active' })).toBeVisible()
    await expect(viewsButton(page)).toHaveText(/View: Active machines$/)

    // a change to the list marks the view changed, until saved into it
    await filterBox(page).fill('web')
    await filterBox(page).press('Enter')
    await expect(viewsButton(page)).toHaveText(/View: Active machines · changed/)
    await viewsButton(page).click()
    await menu.getByRole('menuitem', { name: 'Save changes to “Active machines”' }).click()
    await expect(viewsButton(page)).toHaveText(/View: Active machines$/)

    // and deleted, it goes from the menu
    await viewsButton(page).click()
    await menu.getByRole('menuitem', { name: 'Delete “Active machines”…' }).click()
    const confirm = page.getByRole('dialog', { name: 'Delete the view “Active machines”?' })
    await confirm.getByRole('button', { name: 'Delete view' }).click()
    await expect(viewsButton(page)).toHaveText(/View: All virtual machines/)
    await viewsButton(page).click()
    await expect(menu.getByRole('menuitemradio', { name: /^Active machines/ })).toHaveCount(0)
    await page.keyboard.press('Escape')
    await expect(menu).toBeHidden()
    await expect(viewsButton(page)).toBeFocused()
  })

  test('chooses the columns, which persist, and goes back to the type’s set', async ({ page }) => {
    await signInAs(page, ADMIN, '/console/compute/vm')
    await expect(rows(page).first()).toBeVisible()
    const header = (name: string) => grid(page).getByRole('columnheader', { name, exact: true })
    await expect(header('wfrn')).toBeVisible()
    await expect(header('Drift')).toHaveCount(0)

    await page.getByRole('button', { name: 'Columns' }).click()
    const panel = page.getByRole('dialog', { name: 'Columns' })
    await expect(panel).toBeVisible()
    await expect(panel.getByRole('checkbox', { name: /^Name/ })).toBeDisabled()
    await panel.getByRole('checkbox', { name: 'wfrn' }).uncheck()
    await panel.getByRole('checkbox', { name: 'Drift' }).check()
    await expect(header('wfrn')).toHaveCount(0)
    await expect(header('Drift')).toBeVisible()
    await expectNoSeriousA11y(page, 'column chooser')
    await page.screenshot({ path: shot('list-columns-desktop'), fullPage: true })
    await page.keyboard.press('Escape')
    await expect(panel).toBeHidden()
    await expect(page.getByRole('button', { name: 'Columns' })).toBeFocused()

    await page.reload()
    await expect(rows(page).first()).toBeVisible()
    await expect(header('wfrn')).toHaveCount(0)
    await expect(header('Drift')).toBeVisible()

    await page.getByRole('button', { name: 'Columns' }).click()
    await panel.getByRole('button', { name: 'Back to the type’s columns' }).click()
    await expect(header('wfrn')).toBeVisible()
    await expect(header('Drift')).toHaveCount(0)
  })

  test('the filtered list and its menus fit a phone', async ({ page }) => {
    await page.setViewportSize({ width: 390, height: 844 })
    await signInAs(page, ADMIN, '/console/compute/vm?f=status%3Dactive')
    await expect(rows(page).first()).toBeVisible()
    await expect(page.getByRole('button', { name: 'Remove filter Status = active' })).toBeVisible()
    await expectNoHorizontalOverflow(page)
    await expectNoSeriousA11y(page, 'list on a phone')
    await viewsButton(page).click()
    await expect(page.getByRole('menu', { name: 'Saved views' })).toBeVisible()
    await expectNoHorizontalOverflow(page)
    await page.keyboard.press('Escape')
    await page.getByRole('button', { name: 'Columns' }).click()
    await expect(page.getByRole('dialog', { name: 'Columns' })).toBeVisible()
    await expectNoHorizontalOverflow(page)
    await page.screenshot({ path: shot('list-columns-phone'), fullPage: true })
  })
})
