import { fileURLToPath } from 'node:url'
import { expect, test, type Page } from '@playwright/test'
import { expectNoSeriousA11y, signInAs, tabTo } from './support'

/*
 * Phase 3a's keyboard paths and the dark appearance, in mock mode (WCAG 2.2 AA): the skip link
 * first; the palette (Cmd+K or Ctrl+K) to a list, the filter built and the row opened with the
 * keys alone; a resource's action and the settings from the palette; the saved views menu, its
 * dialog and the column chooser without a pointer, each giving the focus back where it was; a
 * refused action from the palette; and axe on the main pages and the palette in the dark.
 */

const shots = fileURLToPath(new URL('./screenshots/', import.meta.url))
const shot = (name: string) => `${shots}${name}.png`

const ADMIN = /Continue as Gonzalo Munoz/
const VIEWER = /Continue as Mia Chen/

const palette = (page: Page) => page.getByRole('dialog', { name: 'Command palette' })
const grid = (page: Page) => page.getByRole('grid', { name: 'Virtual machines' })
const rows = (page: Page) => grid(page).getByRole('rowgroup').nth(1).getByRole('row')
const filterBox = (page: Page) => page.getByRole('combobox', { name: 'Filter' })

/** Opens the palette with its shortcut, types `text` and runs the first result. */
async function runFromPalette(page: Page, text: string, expectFirst?: RegExp) {
  await page.keyboard.press('ControlOrMeta+k')
  await expect(palette(page)).toBeVisible()
  await page.keyboard.type(text)
  if (expectFirst) await expect(palette(page).getByRole('option').first()).toHaveAccessibleName(expectFirst)
  await page.keyboard.press('Enter')
  await expect(palette(page)).toBeHidden()
}

test.describe('the console by keyboard, and in the dark', () => {
  test('the skip link is the first stop and lands on the content', async ({ page }) => {
    await signInAs(page, ADMIN, '/console/compute/vm')
    await expect(rows(page).first()).toBeVisible()
    await page.locator('body').focus()
    await page.keyboard.press('Tab')
    const skip = page.getByRole('button', { name: 'Skip to content' })
    await expect(skip).toBeFocused()
    await expect(skip).toBeInViewport()
    await page.keyboard.press('Enter')
    await expect(page.locator('#main')).toBeFocused()
  })

  test('from the palette to a filtered list to a resource, with the keys alone', async ({ page }) => {
    await signInAs(page, ADMIN)
    await expect(page.getByRole('heading', { name: 'Console home', level: 1 })).toBeVisible()
    await runFromPalette(page, 'Virtual machines', /^Virtual machines/)
    await expect(page).toHaveURL(/#\/console\/compute\/vm$/)
    await expect(rows(page).first()).toBeVisible()

    // the filter: property, operator, value, each picked with Enter
    await tabTo(page, filterBox(page), 80)
    await page.keyboard.type('Status')
    await page.keyboard.press('Enter')
    await expect(page.getByRole('combobox', { name: 'Operator for Status' })).toBeFocused()
    await page.keyboard.press('Enter')
    await expect(page.getByRole('combobox', { name: 'Value for Status =' })).toBeFocused()
    await page.keyboard.type('active')
    await page.keyboard.press('Enter')
    await expect(page.getByRole('button', { name: 'Remove filter Status = active' })).toBeVisible()
    await expect(page).toHaveURL(/f=status%3Dactive/)

    // the grid: Down to the first row, Enter opens it
    await tabTo(page, grid(page), 40)
    await page.keyboard.press('ArrowDown')
    await expect(grid(page)).toHaveAttribute('aria-activedescendant', /.+/)
    await page.keyboard.press('Enter')
    await expect(page).toHaveURL(/#\/console\/compute\/vm\/wfrn/)

    // a resource's action from the palette opens it on the resource's page
    await runFromPalette(page, 'VM restart (this', /^VM restart \(this virtual machine\)/)
    await expect(page).toHaveURL(/[?&]action=restart/)
  })

  test('the saved views menu, its dialog and the column chooser, without a pointer', async ({ page }) => {
    await signInAs(page, ADMIN, '/console/compute/vm')
    await expect(rows(page).first()).toBeVisible()

    const views = page.getByRole('button', { name: /^View: / })
    await tabTo(page, views, 80)
    await page.keyboard.press('Enter')
    const menu = page.getByRole('menu', { name: 'Saved views' })
    await expect(menu).toBeVisible()
    await expect(menu.getByRole('menuitemradio', { name: 'All virtual machines' })).toBeFocused()
    await page.keyboard.press('ArrowDown')
    await expect(menu.getByRole('menuitem', { name: 'Save as a new view…' })).toBeFocused()
    await page.keyboard.press('End')
    await expect(menu.getByRole('menuitem', { name: 'Save as a new view…' })).toBeFocused()
    await page.keyboard.press('Home')
    await expect(menu.getByRole('menuitemradio', { name: 'All virtual machines' })).toBeFocused()
    await page.keyboard.press('ArrowUp')
    await expect(menu.getByRole('menuitem', { name: 'Save as a new view…' }), 'Up from the first item wraps to the last').toBeFocused()
    await page.keyboard.press('Enter')
    const dialog = page.getByRole('dialog', { name: 'Save this view' })
    await expect(dialog).toBeVisible()
    await expect(dialog.getByRole('textbox', { name: /^Name/ })).toBeFocused()
    await page.keyboard.type('Everything')
    await page.keyboard.press('Enter')
    await expect(dialog).toBeHidden()
    await expect(views).toHaveText(/View: Everything/)
    await expect(views, 'the focus is back on the menu’s button').toBeFocused()

    // Escape closes the menu and gives the focus back to its button
    await page.keyboard.press('ArrowDown')
    await expect(menu).toBeVisible()
    await page.keyboard.press('Escape')
    await expect(menu).toBeHidden()
    await expect(views).toBeFocused()

    // the column chooser: Space toggles a column, Escape closes it on its button
    const columns = page.getByRole('button', { name: 'Columns' })
    await tabTo(page, columns, 20)
    await page.keyboard.press('Enter')
    const panel = page.getByRole('dialog', { name: 'Columns' })
    await expect(panel).toBeVisible()
    const status = panel.getByRole('checkbox', { name: 'Status', exact: true })
    await expect(status).toBeFocused()
    await page.keyboard.press('Space')
    await expect(grid(page).getByRole('columnheader', { name: 'Status', exact: true })).toHaveCount(0)
    await tabTo(page, panel.getByRole('button', { name: 'Back to the type’s columns' }), 30)
    await page.keyboard.press('Enter')
    await expect(grid(page).getByRole('columnheader', { name: 'Status', exact: true })).toBeVisible()
    await page.keyboard.press('Escape')
    await expect(panel).toBeHidden()
    await expect(columns).toBeFocused()
  })

  test('a refused action from the palette opens the refusal, and Escape closes it', async ({ page }) => {
    await signInAs(page, VIEWER)
    await expect(page.getByRole('heading', { name: 'Console home', level: 1 })).toBeVisible()
    await page.keyboard.press('ControlOrMeta+k')
    await page.keyboard.type('Create VM')
    const create = palette(page).getByRole('option', { name: /^Create VM/ }).first()
    await expect(create).toContainText('not allowed')
    await page.keyboard.press('Enter')
    const refusal = page.getByRole('dialog', { name: /You can.t create VM here/ })
    await expect(refusal).toBeVisible()
    await expectNoSeriousA11y(page, 'refusal from the palette')
    await page.keyboard.press('Escape')
    await expect(refusal).toBeHidden()
  })

  test('the dark appearance, from the palette, passes axe on the main pages and the palette', async ({ page }) => {
    await signInAs(page, ADMIN, '/console/compute/vm')
    await expect(rows(page).first()).toBeVisible()
    await runFromPalette(page, 'Use the dark appearance', /^Use the dark appearance/)
    await expect(page.locator('html')).toHaveClass(/dark/)
    await expectNoSeriousA11y(page, 'list, dark')
    await page.screenshot({ path: shot('list-dark-desktop'), fullPage: true })

    await page.goto('/#/console/compute')
    await expect(page.getByRole('list', { name: 'Virtual machines by state' })).toBeVisible()
    await expectNoSeriousA11y(page, 'service landing, dark')

    await page.goto('/#/iam/users')
    await expect(page.getByRole('table', { name: /^Users of/ }).getByRole('row').nth(1)).toBeVisible()
    await expectNoSeriousA11y(page, 'users, dark')

    await page.goto('/#/settings')
    await expect(page.getByRole('heading', { name: 'Settings', level: 1 })).toBeVisible()
    await expectNoSeriousA11y(page, 'settings, dark')

    await page.keyboard.press('ControlOrMeta+k')
    await expect(palette(page)).toBeVisible()
    await page.keyboard.type('vm')
    await expect(palette(page).getByRole('option').first()).toBeVisible()
    await expectNoSeriousA11y(page, 'palette, dark')
    await page.screenshot({ path: shot('palette-dark-desktop') })
    await page.keyboard.press('Escape')
    await expect(palette(page)).toBeHidden()

    // and back, so the next journey starts in the light
    await runFromPalette(page, 'Use the light appearance', /^Use the light appearance/)
    await expect(page.locator('html')).not.toHaveClass(/dark/)
  })
})
