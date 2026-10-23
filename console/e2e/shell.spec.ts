import { fileURLToPath } from 'node:url'
import AxeBuilder from '@axe-core/playwright'
import { expect, test, type Page } from '@playwright/test'

/*
 * The shell's journey in mock mode (VITE_CONSOLE_MOCK=1, no platform): sign in as a development
 * identity, the home, the Services menu, the virtual machines list, one machine, the theme, a
 * phone, and axe on the three main pages. The screenshots it writes are committed.
 */

const shots = fileURLToPath(new URL('./screenshots/', import.meta.url))
const shot = (name: string) => `${shots}${name}.png`

async function expectNoSeriousA11y(page: Page, where: string) {
  const results = await new AxeBuilder({ page }).analyze()
  const bad = results.violations.filter((v) => v.impact === 'serious' || v.impact === 'critical')
  const lines = bad.map((v) => `${v.id} (${v.impact}): ${v.help} at ${v.nodes.slice(0, 3).map((n) => n.target.join(' ')).join(' | ')}`)
  expect(lines, `serious or critical axe violations on the ${where}`).toEqual([])
}

async function expectNoHorizontalOverflow(page: Page) {
  const widths = await page.evaluate(() => {
    const main = document.getElementById('main')
    return { document: document.documentElement.scrollWidth, viewport: window.innerWidth, content: main ? main.scrollWidth - main.clientWidth : 0 }
  })
  expect(widths.document, 'the page is no wider than the viewport').toBeLessThanOrEqual(widths.viewport)
  expect(widths.content, 'the content does not scroll sideways').toBeLessThanOrEqual(0)
}

async function signInAsGonzalo(page: Page) {
  await page.goto('/')
  await expect(page).toHaveURL(/#\/signin/)
  await expect(page.getByRole('heading', { name: 'Sign in', level: 1 })).toBeVisible()
  await page.getByRole('button', { name: /Continue as Gonzalo Munoz/ }).click()
  await expect(page).toHaveURL(/#\/$/)
  await expect(page.getByRole('heading', { name: 'Console home', level: 1 })).toBeVisible()
}

test.describe('the console shell in mock mode', () => {
  test('sign in, browse a service, open a resource, switch the theme', async ({ page }) => {
    await signInAsGonzalo(page)
    await expect(page.getByText('Signed in as Gonzalo Munoz')).toBeVisible()
    await expect(page.getByRole('list', { name: 'Services' })).toBeVisible()
    await expect(page.getByRole('link', { name: 'Virtual machines' }).first()).toBeVisible()
    await expect(page.getByText(/^Console v\d/)).toBeVisible()
    await page.screenshot({ path: shot('home-desktop'), fullPage: true })
    await expectNoSeriousA11y(page, 'home')

    // the Services menu lists every service with its types
    await page.getByRole('button', { name: 'Services', exact: true }).click()
    const menu = page.getByRole('dialog', { name: 'Services' })
    await expect(menu).toBeVisible()
    await expect(menu.getByRole('link', { name: 'Compute' })).toBeVisible()
    await expect(menu.getByRole('link', { name: 'Databases' })).toBeVisible()
    await menu.getByRole('link', { name: 'Virtual machines' }).click()
    await expect(menu).toBeHidden()
    await expect(page).toHaveURL(/#\/console\/compute\/vm$/)
    await expect(page.getByRole('heading', { name: 'Virtual machines', level: 1 })).toBeVisible()

    // the list draws the descriptor's columns, a wfrn per row
    const grid = page.getByRole('grid', { name: 'Virtual machines' })
    await expect(grid).toBeVisible()
    for (const header of [/^Name/, /^Status/, /^wfrn/, /cpu/i, /memory/i, /environment/i, /^Owner/, /^Created/]) {
      await expect(grid.getByRole('columnheader', { name: header })).toBeVisible()
    }
    const rows = grid.getByRole('row')
    await expect(rows.nth(1)).toBeVisible()
    await expect(grid.locator('[data-wfrn^="wfrn:wf:compute:global:123456789012:vm/"]').first()).toBeAttached()
    await expect(page.getByText(/virtual machines on this page/)).toBeVisible()
    await expect(page.getByRole('navigation', { name: 'Compute pages' }).getByRole('link', { name: 'Virtual machines' })).toHaveAttribute('aria-current', 'page')
    await page.screenshot({ path: shot('list-desktop'), fullPage: true })
    await expectNoSeriousA11y(page, 'list')

    // the first machine
    const first = rows.nth(1)
    const name = (await first.getByRole('gridcell').first().innerText()).trim()
    await first.click()
    await expect(page).toHaveURL(/#\/console\/compute\/vm\/wfrn%3A/)
    await expect(page.getByRole('heading', { name, level: 1 })).toBeVisible()
    await expect(page.getByRole('tab', { name: 'Configuration' })).toHaveAttribute('aria-selected', 'true')
    await expect(page.getByRole('heading', { name: 'Sizing' })).toBeVisible()
    await page.getByRole('tab', { name: 'JSON' }).click()
    await expect(page).toHaveURL(/tab=json/)
    await page.getByRole('tab', { name: 'Configuration' }).click()
    await expect(page).not.toHaveURL(/tab=/)
    await page.screenshot({ path: shot('detail-desktop'), fullPage: true })
    await expectNoSeriousA11y(page, 'detail')

    // the theme toggle in the status bar
    await page.getByRole('button', { name: 'Switch to dark mode' }).click()
    await expect(page.locator('html')).toHaveClass(/dark/)
    await page.screenshot({ path: shot('detail-dark-desktop'), fullPage: true })
    await page.getByRole('button', { name: 'Switch to light mode' }).click()
    await expect(page.locator('html')).not.toHaveClass(/dark/)
    await page.reload()
    await expect(page.locator('html')).not.toHaveClass(/dark/)
  })

  test('fits a phone, with the service navigation as a drawer', async ({ page }) => {
    await page.setViewportSize({ width: 390, height: 844 })
    await signInAsGonzalo(page)
    await expectNoHorizontalOverflow(page)
    await page.screenshot({ path: shot('home-phone'), fullPage: true })

    await page.goto('/#/console/compute/vm')
    const grid = page.getByRole('grid', { name: 'Virtual machines' })
    await expect(grid).toBeVisible()
    await expect(grid.getByRole('row').nth(1)).toBeVisible()
    await expectNoHorizontalOverflow(page)
    await page.screenshot({ path: shot('list-phone'), fullPage: true })

    await page.getByRole('button', { name: 'Open the service menu' }).click()
    const drawer = page.getByRole('dialog', { name: 'Compute navigation' })
    await expect(drawer).toBeVisible()
    await drawer.getByRole('link', { name: 'Overview' }).click()
    await expect(page).toHaveURL(/#\/console\/compute$/)
    await expect(drawer).toBeHidden()
    await expect(page.getByRole('heading', { name: 'Compute', level: 1 })).toBeVisible()
    await expectNoHorizontalOverflow(page)
  })

  test('the requests page lists jobs and opens one', async ({ page }) => {
    await signInAsGonzalo(page)
    await page.getByRole('link', { name: 'All requests' }).click()
    await expect(page).toHaveURL(/#\/requests$/)
    const grid = page.getByRole('grid', { name: 'Requests' })
    await expect(grid.getByRole('row').nth(1)).toBeVisible()
    await grid.getByRole('row').nth(1).click()
    await expect(page).toHaveURL(/#\/requests\/job_/)
    await expect(page.getByRole('heading', { name: 'Steps' })).toBeVisible()
    await expectNoSeriousA11y(page, 'request')
  })

  test('the platform feed shows the platform\'s events as they happen', async ({ page }) => {
    await signInAsGonzalo(page)
    await page.getByRole('button', { name: 'Platform feed' }).click()
    const drawer = page.getByRole('dialog', { name: 'Platform feed' })
    await expect(drawer).toBeVisible()
    await expect(drawer.getByText('Live', { exact: true })).toBeVisible()
    // the platform names every server-sent event after its type; the drawer hears them all and shows the type
    const events = drawer.getByRole('list', { name: 'Events' })
    const items = events.getByRole('listitem')
    await expect(items.first()).toBeVisible()
    await expect(items.first().getByText(/^(job|batch|resource|resource-type|workflow|provider|region|content|release|audit)\./)).toBeVisible()
    const before = await items.count()
    await expect.poll(() => items.count(), { timeout: 10_000, message: 'a live event arrives' }).toBeGreaterThan(before)
    // paused, the list holds still; an event opens to its envelope
    await drawer.getByRole('button', { name: 'Pause' }).click()
    const first = items.first()
    await first.getByRole('button').first().click()
    await expect(first.getByRole('button').first()).toHaveAttribute('aria-expanded', 'true')
    await expect(first.getByText('specVersion')).toBeVisible()
    await page.screenshot({ path: shot('feed-desktop'), fullPage: true })
    await expectNoSeriousA11y(page, 'feed')
  })

  test('signing out returns to the door and remembers where you were going', async ({ page }) => {
    await signInAsGonzalo(page)
    await page.goto('/#/iam')
    await expect(page.getByRole('heading', { name: 'Identity and access', level: 1 })).toBeVisible()
    await page.goto('/#/settings')
    await page.getByRole('button', { name: 'Sign out' }).click()
    await expect(page).toHaveURL(/#\/signin/)
    await page.goto('/#/requests')
    await expect(page).toHaveURL(/#\/signin\?next=%2Frequests/)
    await page.getByRole('button', { name: /Continue as Ana Costa/ }).click()
    await expect(page).toHaveURL(/#\/requests$/)
    await expect(page.getByText('Signed in as Ana Costa')).toBeHidden()
  })
})
