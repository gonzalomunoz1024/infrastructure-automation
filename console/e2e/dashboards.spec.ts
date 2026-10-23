import { fileURLToPath } from 'node:url'
import { expect, test, type Locator, type Page } from '@playwright/test'
import { expectNoHorizontalOverflow, expectNoSeriousA11y, signInAs } from './support'

/*
 * Phase 9, in mock mode: team dashboards and service health. Dana builds a dashboard with a CPU
 * chart and a drift count, rearranges it with the keyboard alone, saves it and shares it with
 * her team; Mateo, in the team, finds it under Shared with me and may only view it. Mia, a
 * viewer, reads team-compute's shared board but meets console IAM's refusal on every write.
 * Service health shows Search degraded with its incident, and the footer's platform dot leads
 * there. Each page and dialog is checked with axe and at phone width.
 */

const shots = fileURLToPath(new URL('./screenshots/', import.meta.url))
const shot = (name: string) => `${shots}${name}.png`

const selectNamed = (label: string) => new RegExp(`^${label}: `)

async function choose(scope: Page | Locator, page: Page, label: string, option: string | RegExp) {
  await scope.getByRole('button', { name: selectNamed(label) }).click()
  await page.getByRole('option', { name: option }).click()
}

const widgetTitles = (page: Page) => page.locator('[data-widget] h2')

async function signOut(page: Page) {
  await page.goto('/#/settings')
  await page.getByRole('button', { name: 'Sign out' }).click()
  await expect(page).toHaveURL(/#\/signin/)
}

async function onPhone(page: Page, ready: Locator): Promise<void> {
  await page.setViewportSize({ width: 390, height: 844 })
  await page.reload()
  await expect(ready).toBeVisible()
}

test.describe('team dashboards and service health in mock mode', () => {
  test('Dana builds a dashboard with a CPU chart and a drift count, rearranges it by keyboard, shares it with her team, and Mateo sees it', async ({ page }) => {
    test.setTimeout(90_000)
    await signInAs(page, /Continue as Dana Reyes/, '/dashboards')
    await expect(page.getByRole('heading', { name: 'Dashboards', level: 1 })).toBeVisible()
    await expect(page.getByRole('link', { name: 'Compute fleet' })).toBeVisible()
    await expect(page.getByRole('link', { name: 'Batch nights' })).toHaveCount(0)
    await expectNoSeriousA11y(page, 'dashboards list')

    await page.getByRole('button', { name: 'Create dashboard' }).click()
    const create = page.getByRole('dialog', { name: 'Create a dashboard' })
    await expect(create.getByLabel(/^Name/)).toBeFocused()
    await create.getByLabel(/^Name/).fill('Web tier')
    await expectNoSeriousA11y(page, 'create dashboard dialog')
    await create.getByRole('button', { name: 'Create dashboard' }).click()
    await expect(page.getByRole('heading', { name: 'Web tier', level: 1 })).toBeVisible()
    await expect(page).toHaveURL(/edit=1/)

    // a CPU chart of web-01
    await page.getByRole('button', { name: 'Add widget' }).click()
    const add = page.getByRole('dialog', { name: 'Add a widget' })
    await expect(add.getByRole('radio', { name: /Metric chart/ })).toBeChecked()
    await choose(add, page, 'Resource', /^api-01$/)
    await expect(add.getByRole('button', { name: selectNamed('Series') })).toHaveAccessibleName('Series: CPU used')
    await expectNoSeriousA11y(page, 'add widget dialog')
    await add.getByRole('button', { name: 'Add widget' }).click()
    await expect(add).toHaveCount(0)
    // then the drift findings
    await page.getByRole('button', { name: 'Add widget' }).click()
    await add.getByRole('radio', { name: /Drift findings/ }).check()
    await add.getByRole('button', { name: 'Add widget' }).click()
    await expect(widgetTitles(page)).toHaveText(['api-01 CPU used', 'Drift findings'])

    // by keyboard: the drift count first, then wider
    const handle = page.getByRole('button', { name: /^Move Drift findings, place 2 of 2/ })
    await handle.focus()
    await page.keyboard.press('ArrowLeft')
    await expect(widgetTitles(page)).toHaveText(['Drift findings', 'api-01 CPU used'])
    await expect(page.getByRole('button', { name: /^Move Drift findings, place 1 of 2/ })).toBeFocused()
    await expect(page.getByRole('status').filter({ hasText: 'Drift findings moved to place 1 of 2.' })).toBeAttached()
    await page.keyboard.press('Shift+ArrowRight')
    await expect(page.getByRole('button', { name: /^Move Drift findings, place 1 of 2, half the width, 2 rows tall/ })).toBeFocused()
    await page.screenshot({ path: shot('dashboard-editing-desktop'), fullPage: true })
    await expectNoSeriousA11y(page, 'dashboard in edit mode')
    await page.getByRole('button', { name: 'Save dashboard' }).click()
    await expect(page).not.toHaveURL(/edit=1/)
    await expect(widgetTitles(page)).toHaveText(['Drift findings', 'api-01 CPU used'])
    await expect(page.getByRole('figure', { name: 'CPU used, percent' })).toBeVisible()
    await expect(page.locator('[data-drift-open]')).toContainText('open finding')

    // the range of the whole board lives in the address
    await page.getByRole('radiogroup', { name: 'Time range' }).getByRole('radio', { name: '7 days' }).click()
    await expect(page).toHaveURL(/range=7d/)
    await expect(page.getByRole('figure', { name: 'CPU used, percent' }).locator('[data-summary]')).toHaveText(/^Latest /)
    await page.screenshot({ path: shot('dashboard-desktop'), fullPage: true })
    await expectNoSeriousA11y(page, 'dashboard')

    // shared with team-compute, to view
    await page.getByRole('button', { name: 'Share' }).click()
    const share = page.getByRole('dialog', { name: 'Share Web tier' })
    await share.getByRole('checkbox', { name: /team-compute \(members\)/ }).check()
    await expectNoSeriousA11y(page, 'share dialog')
    await share.getByRole('button', { name: 'Save sharing' }).click()
    await expect(share).toHaveCount(0)
    await expect(page.getByText(/Who sees it: team-compute to view/)).toBeVisible()

    // Mateo is in the team: he finds it, and may only view it
    await signOut(page)
    await signInAs(page, /Continue as Mateo Silva/, '/dashboards?scope=shared')
    await page.getByRole('link', { name: 'Web tier' }).click()
    await expect(page.getByRole('heading', { name: 'Web tier', level: 1 })).toBeVisible()
    await expect(page.getByText('Shared with you by Dana Reyes to view', { exact: false })).toBeVisible()
    await expect(widgetTitles(page)).toHaveText(['Drift findings', 'api-01 CPU used'])
    await expect(page.getByRole('button', { name: 'Edit', exact: true })).toBeDisabled()
    await expect(page.locator('[data-view-only]')).toContainText('Dana Reyes shared it with you to view')
    await expectNoSeriousA11y(page, 'dashboard shared to view')
  })

  test('Mia, a viewer, reads team-compute’s shared board and cannot edit it or create one', async ({ page }) => {
    await signInAs(page, /Continue as Mia Chen/, '/dashboards')
    const create = page.getByRole('button', { name: /^Create dashboard/ })
    await expect(create).toHaveAttribute('data-denied', 'console-iam')
    await page.getByRole('link', { name: 'Compute fleet' }).click()
    await expect(page.getByRole('heading', { name: 'Compute fleet', level: 1 })).toBeVisible()
    await expect(page.getByRole('figure', { name: 'CPU used, percent' })).toBeVisible()
    const edit = page.getByRole('button', { name: /^Edit/ })
    await expect(edit).toHaveAttribute('data-denied', 'console-iam')
    await edit.click()
    const refusal = page.getByRole('dialog', { name: /You can.t edit compute fleet/i })
    await expect(refusal).toContainText('dashboards:update')
    await expectNoSeriousA11y(page, 'refusal of an edit')
    await page.keyboard.press('Escape')
    await expect(refusal).toHaveCount(0)
    // asking for edit mode in the address changes nothing
    await page.goto(`${page.url()}?edit=1`)
    await expect(page.getByRole('button', { name: 'Save dashboard' })).toHaveCount(0)
    await page.screenshot({ path: shot('dashboard-viewer-desktop'), fullPage: true })

    await onPhone(page, page.getByRole('heading', { name: 'Compute fleet', level: 1 }))
    await expect(page.getByRole('figure', { name: 'CPU used, percent' })).toBeVisible()
    await expectNoHorizontalOverflow(page)
    await expectNoSeriousA11y(page, 'dashboard at phone width')
    await page.screenshot({ path: shot('dashboard-phone'), fullPage: true })
    await page.goto('/#/dashboards')
    await expect(page.getByRole('link', { name: 'Compute fleet' })).toBeVisible()
    await expectNoHorizontalOverflow(page)
    await expectNoSeriousA11y(page, 'dashboards list at phone width')
  })

  test('service health shows Search degraded with its incident, and the footer’s platform dot leads there', async ({ page }) => {
    await signInAs(page, /Continue as Gonzalo Munoz/, '/')
    const dot = page.getByRole('link', { name: /platform up.*service health/ })
    await expect(dot).toBeVisible()
    await dot.click()
    await expect(page.getByRole('heading', { name: 'Service health', level: 1 })).toBeVisible()
    await expect(page.getByText(/One service is not fully working: Search \(degraded\)/)).toBeVisible()
    await expect(page.locator('[data-service]')).toHaveCount(7)
    const search = page.locator('[data-service="search"]')
    await expect(search).toHaveAttribute('data-status', 'degraded')
    await expect(search).toContainText('Degraded')
    await expect(search.locator('[data-slo="latency-24h"]')).toHaveAttribute('data-met', 'false')
    await expect(search.locator('[data-slo="latency-24h"]')).toContainText('Missed')
    await expect(page.locator('[data-service="platform"]')).toContainText('Operational')
    const incident = page.locator('[data-incident="inc-search-lag"]')
    await expect(incident).toContainText('Search results lag behind and answer slowly')
    await expect(incident).toContainText('Cause found')
    await expect(incident.getByRole('link', { name: 'More from the team' })).toHaveAttribute('href', /^https:\/\//)
    await page.screenshot({ path: shot('health-desktop'), fullPage: true })
    await expectNoSeriousA11y(page, 'service health')

    await onPhone(page, page.getByRole('heading', { name: 'Service health', level: 1 }))
    await expectNoHorizontalOverflow(page)
    await expectNoSeriousA11y(page, 'service health at phone width')
    await page.screenshot({ path: shot('health-phone'), fullPage: true })
  })
})
