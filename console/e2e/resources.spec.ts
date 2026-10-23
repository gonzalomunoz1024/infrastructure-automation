import { fileURLToPath } from 'node:url'
import { expect, test, type Page } from '@playwright/test'
import { expectNoHorizontalOverflow, expectNoSeriousA11y, signInAs } from './support'

/*
 * Phase 3, in mock mode: create a VM through the wizard generated from its workflow's schema
 * (review, cost and view as code before Create), follow it to active on its page, run a verb
 * from the Actions menu, delete a VM through the type-to-confirm dialog and its approval, meet a
 * delete held back by what depends on it, read a drift finding, and do it all at phone width.
 * The mock answers the BFF's shapes and runs requests on a clock: 6 s to approve, 8 s to apply.
 */

const shots = fileURLToPath(new URL('./screenshots/', import.meta.url))
const shot = (name: string) => `${shots}${name}.png`

const ACCOUNT = '123456789012'
const wfrnOf = (id: string) => `wfrn:wf:compute:global:${ACCOUNT}:vm/${id}`
const vmPage = (id: string, query = '') => `/console/compute/vm/${encodeURIComponent(wfrnOf(id))}${query}`

/** A form's Select: named by its label and the text it shows, "Size: small". */
const selectNamed = (label: string) => new RegExp(`^${label.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')}: `)

/** Picks an option of an Atlas Select, found by its label. */
async function choose(page: Page, label: string, option: string | RegExp) {
  await page.getByRole('button', { name: selectNamed(label) }).click()
  await page.getByRole('option', { name: option }).click()
  await expect(page.getByRole('button', { name: selectNamed(label) })).toContainText(option)
}

test.describe('resources in mock mode', () => {
  test('Dana creates a VM through the wizard: a step per group, a review with its cost and code, then its page follows the request to active', async ({ page }) => {
    test.setTimeout(90_000)
    // the service's page tours it, written from what its providers publish, until dismissed
    await signInAs(page, /Continue as Dana Reyes/, '/console/compute')
    const tour = page.getByTestId('getting-started')
    await expect(tour.getByRole('list', { name: 'Getting started with Compute' })).toBeVisible()
    await expect(tour.getByRole('link', { name: 'Create a virtual machine' })).toHaveAttribute('href', /#\/console\/compute\/vm\/create/)
    await page.screenshot({ path: shot('service-tour-desktop'), fullPage: true })
    await expectNoSeriousA11y(page, 'service page with its tour')
    await tour.getByRole('button', { name: 'Dismiss the getting-started tour' }).click()
    await expect(tour).toHaveCount(0)
    await page.reload()
    await expect(page.getByRole('list', { name: 'Virtual machines by state' })).toBeVisible()
    await expect(page.getByTestId('getting-started')).toHaveCount(0)

    await page.goto('/#/console/compute/vm')
    await expect(page.getByRole('heading', { name: 'Virtual machines', level: 1 })).toBeVisible()
    const create = page.getByRole('button', { name: /^Create (VM|virtual machine)/i }).first()
    await expect(create).not.toHaveAttribute('data-denied')
    await expect(create).not.toHaveAttribute('data-phase')
    await create.click()

    // the steps come from the schema: the type groups image under Boot
    await expect(page).toHaveURL(/#\/console\/compute\/vm\/create$/)
    await expect(page.getByRole('heading', { name: 'Create VM', level: 1 })).toBeVisible()
    const steps = page.getByRole('navigation', { name: 'Wizard steps' })
    await expect(steps.getByText('Virtual machine details')).toBeVisible()
    await expect(steps.getByText('Boot')).toBeVisible()
    await expect(steps.getByText('Tags')).toBeVisible()
    await expect(steps.getByText('Review and create')).toBeVisible()
    await expect(page.getByText('Step 1 of 4')).toBeVisible()

    // Next checks the step first, and lands on the field that needs attention
    await page.getByRole('button', { name: 'Next' }).click()
    await expect(page.locator('#create-name')).toBeFocused()
    await page.locator('#create-name').fill('web-42')
    await choose(page, 'Size', 'medium')
    await page.screenshot({ path: shot('create-details-desktop'), fullPage: true })
    await expectNoSeriousA11y(page, 'create wizard, details step')

    // the image: one from the inventory
    await page.getByRole('button', { name: 'Next' }).click()
    await expect(page.getByRole('heading', { name: 'Boot', level: 2 })).toBeFocused()
    await expect(page.getByRole('radio', { name: 'Use an existing image' })).toBeChecked()
    await choose(page, 'Image id', /ubuntu-24\.04-cis-l1/)
    await page.getByRole('button', { name: 'Next' }).click()

    // the account's required tags, each pre-filled from its default (the environment follows the create's own field)
    await expect(page.getByRole('heading', { name: 'Tags', level: 2, exact: true })).toBeFocused()
    await expect(page.locator('#create-tag-cost-centre')).toHaveValue('cc-1042')
    await expect(page.getByRole('button', { name: 'Environment: dev' })).toBeVisible()
    await page.getByRole('button', { name: 'Next' }).click()

    // the review: what was asked for, what will happen, what it costs, and the request as code
    await expect(page.getByRole('heading', { name: 'Review and create', level: 2 })).toBeVisible()
    await expect(page.getByRole('button', { name: 'Edit virtual machine details' })).toBeVisible()
    await expect(page.getByRole('button', { name: 'Edit boot' })).toBeVisible()
    const preview = page.getByTestId('preview-card')
    await expect(preview).toBeVisible()
    await expect(preview).toContainText('Allowed')
    await expect(page.getByTestId('preview-cost')).toContainText(/\d/)
    const code = page.getByTestId('view-as-code')
    await expect(code).toHaveAttribute('open', '')
    await expect(code.getByTestId('code-view')).toContainText('iaf compute create-vm')
    await expect(code.getByTestId('code-view')).toContainText('--name web-42')
    await code.getByRole('radio', { name: 'Workflow YAML' }).click()
    await expect(code.getByTestId('code-view')).toContainText('kind: WorkflowRun')
    await preview.scrollIntoViewIfNeeded()
    await page.screenshot({ path: shot('create-review-desktop'), fullPage: true })
    await expectNoSeriousA11y(page, 'create wizard, review step')

    // Create: the request is accepted and the new VM's page follows it
    await page.locator('form [data-verb="create"]').click()
    await expect(page).toHaveURL(/#\/console\/compute\/vm\/wfrn%3A/)
    await expect(page.getByRole('heading', { name: 'web-42', level: 1 })).toBeVisible()
    const banner = page.locator('[data-job-status]')
    await expect(banner).toHaveAttribute('data-job-status', 'running')
    await expect(banner).toContainText('Create VM is running')
    // too new for the hourly drift scan
    await expect(page.getByText('Not checked yet')).toBeVisible()
    await page.screenshot({ path: shot('resource-in-progress-desktop'), fullPage: true })
    await expectNoSeriousA11y(page, 'resource page with a request in flight')

    // eight seconds later it is ready, and nothing is in flight any more
    await expect(banner).toHaveCount(0, { timeout: 30_000 })
    await expect(page.getByText('ready', { exact: true }).first()).toBeVisible()
    // its trail starts with the request that made it
    await page.getByRole('tab', { name: /^Activity/ }).click()
    const trail = page.getByRole('list', { name: 'Activity' })
    await expect(trail).toContainText('Registered in the inventory')
    await expect(trail).toContainText('by dana, through create-vm')
    await expect(trail.getByRole('link', { name: /^job_/ })).toBeVisible()
  })

  test('Dana runs a verb from the Actions menu: the drawer previews it, the request runs, and the menu waits for it', async ({ page }) => {
    await signInAs(page, /Continue as Dana Reyes/, vmPage('vm-demo02'))
    await expect(page.getByRole('heading', { name: 'api-01', level: 1 })).toBeVisible()

    // the menu opens from the keyboard and gives focus back when closed
    const actions = page.getByRole('button', { name: 'Actions', exact: true })
    await actions.focus()
    await page.keyboard.press('ArrowDown')
    const menu = page.getByRole('menu', { name: 'Actions for api-01' })
    await expect(menu).toBeVisible()
    await expect(menu.getByRole('menuitem').first()).toBeFocused()
    await expect(menu.getByRole('menuitem', { name: /Manage tags/ })).not.toHaveAttribute('data-phase')
    await page.keyboard.press('Escape')
    await expect(menu).toBeHidden()
    await expect(actions).toBeFocused()

    await actions.click()
    await menu.getByRole('menuitem', { name: /VM restart/ }).click()
    await expect(page).toHaveURL(/action=restart/)
    const drawer = page.getByRole('dialog', { name: 'VM restart: api-01' })
    await expect(drawer).toBeVisible()
    // the form starts from what the VM is: a dev VM restarts in dev
    await expect(drawer.getByRole('button', { name: selectNamed('Environment') })).toHaveAccessibleName('Environment: dev')
    await drawer.locator('#action-restart-reason').fill('stuck after a kernel update')
    await expect(drawer.getByTestId('preview-card')).toContainText('Allowed')
    await drawer.getByText('View as code').click()
    await expect(drawer.getByTestId('code-view')).toContainText('iaf compute restart-vm')
    await page.screenshot({ path: shot('action-drawer-desktop'), fullPage: true })
    await expectNoSeriousA11y(page, 'action drawer')

    await drawer.locator('[data-verb="restart"]').click()
    await expect(drawer).toBeHidden()
    await expect(page).not.toHaveURL(/action=/)
    await expect(page.getByText('VM restart requested')).toBeVisible()
    await expect(page.locator('[data-job-status="running"]')).toContainText('VM restart is running')

    // while it runs, the menu says the verbs wait for it
    await actions.click()
    await expect(menu.getByRole('menuitem', { name: /Patch CVE/ })).toHaveAttribute('data-unavailable', 'true')
    await page.keyboard.press('Escape')
    await expect(page.locator('[data-job-status]')).toHaveCount(0, { timeout: 30_000 })
  })

  test('Gonzalo deletes a VM by typing its name, an approver says yes, and the page keeps its tombstone', async ({ page }) => {
    test.setTimeout(90_000)
    await signInAs(page, /Continue as Gonzalo Munoz/, vmPage('vm-gen-001'))
    await expect(page.getByRole('heading', { name: 'api-011', level: 1 })).toBeVisible()
    await page.locator('header [data-verb="delete"], [data-verb="delete"]').first().click()
    await expect(page).toHaveURL(/action=delete/)
    const drawer = page.getByRole('dialog', { name: 'Delete VM: api-011' })
    await expect(drawer).toBeVisible()

    // the reason is the workflow's own field, checked before anything is sent
    await drawer.locator('[data-verb="delete"]').click()
    await expect(drawer.locator('#action-delete-reason')).toBeFocused()
    await drawer.locator('#action-delete-reason').fill('decommissioned after the migration')
    await expect(drawer.getByTestId('preview-card')).toContainText('Needs approval')
    await expect(drawer.getByTestId('preview-cost')).toBeVisible()
    await drawer.locator('[data-verb="delete"]').click()

    // the type-to-confirm dialog
    const confirm = page.getByRole('dialog', { name: 'Delete VM: api-011?' })
    await expect(confirm).toBeVisible()
    const sure = confirm.getByRole('button', { name: 'Delete VM' })
    await expect(sure).toBeDisabled()
    await confirm.getByLabel('Type api-011 to confirm').fill('api-011')
    await expect(sure).toBeEnabled()
    await page.screenshot({ path: shot('delete-confirm-desktop'), fullPage: true })
    await expectNoSeriousA11y(page, 'delete confirmation')
    await sure.click()

    await expect(confirm).toBeHidden()
    await expect(drawer).toBeHidden()
    const banner = page.locator('[data-job-status]')
    await expect(banner).toHaveAttribute('data-job-status', 'waiting-approval')
    await expect(banner).toContainText('Delete VM is waiting for an approver')

    // approved, run, gone: the record stays readable, nothing can run on it
    const tombstone = page.locator('[data-deleted="true"]')
    await expect(tombstone).toBeVisible({ timeout: 45_000 })
    await expect(tombstone).toContainText('This virtual machine was deleted')
    await expect(tombstone).toContainText('stay readable until')
    await expect(page.getByRole('button', { name: 'Actions', exact: true })).toBeDisabled()
    // nothing left to delete, and no live view to show stale drift from
    await expect(page.locator('[data-verb="delete"]')).toHaveCount(0)
    await expect(page.getByText('In sync')).toHaveCount(0)
    await page.screenshot({ path: shot('resource-deleted-desktop'), fullPage: true })
    await expectNoSeriousA11y(page, 'deleted resource')
  })

  test('a delete is held back by what depends on it, and drift is read field by field', async ({ page }) => {
    await signInAs(page, /Continue as Gonzalo Munoz/, vmPage('vm-demo02', '?action=delete'))
    const drawer = page.getByRole('dialog', { name: 'Delete VM: api-01' })
    await expect(drawer).toBeVisible()
    await drawer.locator('#action-delete-reason').fill('replaced by a larger VM')
    const preview = drawer.getByTestId('preview-card')
    await expect(preview).toContainText('Denied')
    await expect(preview).toContainText('built from this virtual machine')
    await expect(drawer.locator('[data-verb="delete"]')).toBeDisabled()
    await drawer.getByRole('button', { name: 'Cancel' }).click()
    await expect(drawer).toBeHidden()
    await expect(page).not.toHaveURL(/action=/)

    // drift: what was asked for against what the provider reports
    await page.getByRole('tab', { name: /^Drift/ }).click()
    await expect(page).toHaveURL(/tab=drift/)
    const fields = page.getByRole('table', { name: 'Fields that drifted' })
    await expect(fields).toBeVisible()
    await expect(fields.getByRole('row')).toHaveCount(2)
    // the decisions are live (phase 5): Revert is one click, Accept and Ignore ask first (e2e/drift.spec.ts)
    await expect(page.locator('[data-finding] [data-verb="revert"]')).toBeEnabled()
    await page.screenshot({ path: shot('drift-desktop'), fullPage: true })
    await expectNoSeriousA11y(page, 'drift tab')

    // who may do what here, and why not
    await page.getByRole('tab', { name: /^Permissions/ }).click()
    await expect(page.getByText('Allowed').first()).toBeVisible()
  })

  test('the service tour, the wizard and a resource page fit a phone', async ({ page }) => {
    await page.setViewportSize({ width: 390, height: 844 })
    await signInAs(page, /Continue as Dana Reyes/, '/console/compute')
    await expect(page.getByTestId('getting-started')).toBeVisible()
    await expectNoHorizontalOverflow(page)
    await expectNoSeriousA11y(page, 'service page at phone width')

    await page.goto('/#/console/compute/vm/create')
    await expect(page.getByRole('heading', { name: 'Create VM', level: 1 })).toBeVisible()
    await expect(page.getByText('Step 1 of 4')).toBeVisible()
    await expectNoHorizontalOverflow(page)
    await page.screenshot({ path: shot('create-phone'), fullPage: true })
    await expectNoSeriousA11y(page, 'create wizard at phone width')

    await page.goto(`/#${vmPage('vm-demo01')}`)
    await expect(page.getByRole('heading', { name: 'web-01', level: 1 })).toBeVisible()
    await expectNoHorizontalOverflow(page)
    await page.getByRole('button', { name: 'Actions', exact: true }).click()
    const menu = page.getByRole('menu', { name: 'Actions for web-01' })
    await expect(menu).toBeVisible()
    const box = await menu.boundingBox()
    expect(box && box.x >= 0 && box.x + box.width <= 390).toBe(true)
    await page.screenshot({ path: shot('resource-phone'), fullPage: true })
    await expectNoSeriousA11y(page, 'resource page at phone width')
  })
})
