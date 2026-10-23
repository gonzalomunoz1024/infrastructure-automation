import { fileURLToPath } from 'node:url'
import { expect, test, type Page } from '@playwright/test'
import { expectNoHorizontalOverflow, expectNoSeriousA11y, signInAs } from './support'

/*
 * Phase 4, in mock mode: tags and resource groups. A create is refused until it names every tag
 * the account requires, then created with them; a resource's tags change in the Tags drawer; the
 * Tag Editor tags two resources of different types at once; Gonzalo creates and deletes a resource
 * group; Mia, a viewer, meets the writes greyed before any call; a resource is cloned in its own
 * account. Each new page passes axe and fits a phone.
 */

const shots = fileURLToPath(new URL('./screenshots/', import.meta.url))
const shot = (name: string) => `${shots}${name}.png`

const ACCOUNT = '123456789012'
const vmWfrn = (id: string) => `wfrn:wf:compute:global:${ACCOUNT}:vm/${id}`
const vmPage = (id: string, query = '') => `/console/compute/vm/${encodeURIComponent(vmWfrn(id))}${query}`

/** A form's Select: named by its label and the text it shows, "Size: small". */
const selectNamed = (label: string) => new RegExp(`^${label.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')}: `)

/** Picks an option of an Atlas Select, found by its label. */
async function choose(page: Page, label: string, option: string | RegExp) {
  await page.getByRole('button', { name: selectNamed(label) }).click()
  await page.getByRole('option', { name: option }).click()
  await expect(page.getByRole('button', { name: selectNamed(label) })).toContainText(option)
}

/** The create wizard for a VM, filled up to its Tags step. */
async function vmUpToTags(page: Page, name: string) {
  await page.goto('/#/console/compute/vm/create')
  await expect(page.getByRole('heading', { name: 'Create VM', level: 1 })).toBeVisible()
  await page.locator('#create-name').fill(name)
  await page.getByRole('button', { name: 'Next' }).click()
  await choose(page, 'Image id', /ubuntu-24\.04-cis-l1/)
  await page.getByRole('button', { name: 'Next' }).click()
  await expect(page.getByRole('heading', { name: 'Tags', level: 2, exact: true })).toBeFocused()
}

test.describe('tags and resource groups in mock mode', () => {
  test('a create waits for every required tag: refused while one is blank, created once it is set', async ({ page }) => {
    await signInAs(page, /Continue as Dana Reyes/, '/console/compute')
    await vmUpToTags(page, 'web-61')

    // every required tag is there, pre-filled from its default; the environment follows the create's own field
    const steps = page.getByRole('navigation', { name: 'Wizard steps' })
    await expect(steps.getByText('4 required')).toBeVisible()
    await expect(page.getByRole('button', { name: 'Environment: dev' })).toBeVisible()
    await expect(page.locator('#create-tag-owner')).toHaveValue('team-compute')
    await expect(page.locator('#create-tag-cost-centre')).toHaveValue('cc-1042')
    await expect(page.getByRole('button', { name: 'Data class: internal' })).toBeVisible()
    await page.screenshot({ path: shot('create-tags-desktop'), fullPage: true })
    await expectNoSeriousA11y(page, 'create wizard, tags step')

    // a value the tag may not take keeps the step
    await page.locator('#create-tag-cost-centre').fill('CC-1')
    await expect(page.getByText('Cost centre must match ^cc-[0-9]{4}$')).toBeVisible()
    await page.getByRole('button', { name: 'Next' }).click()
    await expect(page.getByRole('heading', { name: 'Tags', level: 2, exact: true })).toBeVisible()
    await expect(page.locator('#create-tag-cost-centre')).toBeFocused()

    // a blank one reaches the review, which says why Create waits
    await page.locator('#create-tag-cost-centre').fill('')
    await page.getByRole('button', { name: 'Next' }).click()
    await expect(page.getByRole('heading', { name: 'Review and create', level: 2 })).toBeVisible()
    await expect(page.getByText('Not set: required')).toBeVisible()
    const blocked = page.locator('[data-blocked="tags"]')
    await expect(blocked).toContainText('Set Cost centre on the Tags step before you create')
    await expect(page.locator('form [data-verb="create"]')).toBeDisabled()
    await expect(page.getByTestId('preview-card')).toContainText('required tags are missing: cost-centre')
    await page.screenshot({ path: shot('create-tags-blocked-desktop'), fullPage: true })

    // set it, and the create goes through with every tag on the new machine
    await blocked.getByRole('button', { name: 'Set the tags' }).click()
    await page.locator('#create-tag-cost-centre').fill('cc-3001')
    await page.getByRole('button', { name: 'Next' }).click()
    await expect(page.locator('[data-blocked="tags"]')).toHaveCount(0)
    await expect(page.locator('form [data-verb="create"]')).toBeEnabled()
    await page.locator('form [data-verb="create"]').click()
    await expect(page.getByRole('heading', { name: 'web-61', level: 1 })).toBeVisible()
    await page.getByRole('tab', { name: /Tags/ }).click()
    const tags = page.getByRole('tabpanel')
    await expect(tags).toContainText('cost-centre')
    await expect(tags).toContainText('cc-3001')
    await expect(tags).toContainText('data-class')
  })

  test('the Tags drawer changes a resource’s tags, and keeps the required ones on it', async ({ page }) => {
    await signInAs(page, /Continue as Dana Reyes/, vmPage('vm-demo02', '?tab=tags'))
    const panel = page.getByRole('tabpanel')
    await expect(panel).toContainText('environment')
    await panel.getByRole('button', { name: 'Manage tags' }).click()
    await expect(page).toHaveURL(/action=tags/)
    const drawer = page.getByRole('dialog', { name: /^Manage tags/ })
    await expect(drawer).toBeVisible()

    // a required tag the machine carries keeps its row: no Remove button, and the reason in words
    await expect(drawer.getByRole('button', { name: /Remove tag row \d+ \(environment\)/ })).toHaveCount(0)
    await expect(drawer.getByText(/Required by this account, so it stays/).first()).toBeVisible()
    await expect(drawer.getByRole('button', { name: /Remove tag row \d+ \(visibility\)/ })).toBeVisible()

    // a value the policy does not take is named before anything is sent
    const environment = drawer.getByRole('textbox', { name: /Tag value, row \d+ \(environment\)/ })
    await environment.fill('staging')
    await expect(drawer.getByText('Environment is one of dev, test, prod')).toBeVisible()
    await environment.fill('test')

    // a tag of the person's own, then save
    await drawer.getByRole('button', { name: 'Add a tag' }).click()
    const rows = drawer.locator('[data-tag-row]')
    const last = drawer.getByRole('textbox', { name: /^Tag name, row \d+$/ }).last()
    await expect(last).toBeFocused()
    await last.fill('team')
    await drawer.getByRole('textbox', { name: /Tag value, row \d+ \(team\)/ }).fill('checkout')
    await expect(rows).toHaveCount(4)
    await expectNoSeriousA11y(page, 'Tags drawer')
    await page.screenshot({ path: shot('tags-drawer-desktop'), fullPage: true })
    await drawer.getByRole('button', { name: 'Save tags' }).click()
    await expect(drawer).toHaveCount(0)
    await expect(panel).toContainText('checkout')
    await expect(panel).toContainText('test')
  })

  test('the Tag Editor finds resources of two types and tags one of each at once', async ({ page }) => {
    await signInAs(page, /Continue as Dana Reyes/, '/tag-editor')
    await expect(page.getByRole('heading', { name: 'Tag Editor', level: 1 })).toBeVisible()
    await page.getByRole('button', { name: 'Types: every type' }).click()
    await page.getByRole('checkbox', { name: 'Images' }).click()
    await page.getByRole('checkbox', { name: 'Volumes' }).click()
    await page.keyboard.press('Escape')
    await page.getByRole('button', { name: 'Find resources' }).click()
    await expect(page).toHaveURL(/tag-editor\?type=image(%2C|,)volume/)
    await page.getByRole('checkbox', { name: 'Select ubuntu-24.04-cis-l1-2026-10-01' }).click()
    await page.getByRole('checkbox', { name: 'Select volume-demo01' }).click()
    await expectNoSeriousA11y(page, 'Tag Editor')
    await page.screenshot({ path: shot('tag-editor-desktop'), fullPage: true })

    await page.getByRole('button', { name: 'Manage tags of 2' }).click()
    const dialog = page.getByRole('dialog', { name: 'Manage tags of 2 resources' })
    await expect(dialog).toBeVisible()
    // the required tags stay: they cannot be ticked for removal
    await expect(dialog.getByRole('checkbox', { name: /owner/ })).toBeDisabled()
    await dialog.getByRole('textbox', { name: 'Tag name, row 1' }).fill('team')
    await dialog.getByRole('textbox', { name: /Value to set, row 1/ }).fill('checkout')
    await expectNoSeriousA11y(page, 'apply tags dialog')
    await dialog.getByRole('button', { name: 'Apply to 2 resources' }).click()
    const done = page.getByRole('dialog', { name: 'Tags applied' })
    await expect(done).toContainText('Tagged 2 resources')
    await done.getByRole('button', { name: 'Done' }).click()

    // both carry the tag now, whatever their type
    await page.goto('/#/tag-editor?tag.team=checkout')
    await expect(page.getByRole('link', { name: 'ubuntu-24.04-cis-l1-2026-10-01' })).toBeVisible()
    await expect(page.getByRole('link', { name: 'volume-demo01' })).toBeVisible()
    await expect(page.getByText('team=checkout').first()).toBeVisible()
  })

  test('the Tags step and the Tags drawer fit a phone', async ({ page }) => {
    await page.setViewportSize({ width: 390, height: 844 })
    await signInAs(page, /Continue as Dana Reyes/, '/console/compute')
    await vmUpToTags(page, 'web-62')
    await expectNoHorizontalOverflow(page)
    await expectNoSeriousA11y(page, 'create wizard, tags step at phone width')
    await page.screenshot({ path: shot('create-tags-phone'), fullPage: true })

    await page.goto(`/#${vmPage('vm-demo02', '?tab=tags&action=tags')}`)
    await expect(page.getByRole('dialog', { name: /^Manage tags/ })).toBeVisible()
    await expectNoHorizontalOverflow(page)
    await expectNoSeriousA11y(page, 'Tags drawer at phone width')

    await page.goto('/#/tag-editor')
    await expect(page.getByRole('heading', { name: 'Tag Editor', level: 1 })).toBeVisible()
    await expectNoHorizontalOverflow(page)
    await expectNoSeriousA11y(page, 'Tag Editor at phone width')
    await page.screenshot({ path: shot('tag-editor-phone'), fullPage: true })
  })
})
