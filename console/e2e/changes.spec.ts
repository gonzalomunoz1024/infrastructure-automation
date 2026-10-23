import { fileURLToPath } from 'node:url'
import { expect, test, type Locator, type Page } from '@playwright/test'
import { expectNoHorizontalOverflow, expectNoSeriousA11y, signInAs } from './support'

/*
 * Phase 4, changing resources safely, in mock mode: edit a VM's memory through Edit configuration
 * (what changes, the preview, the request as code, then the page follows the request until it
 * shows the new value), meet a delete refused while other resources depend on the VM with its
 * blast radius in words, and see Edit locked for a viewer, with why. The drawer and the card fit a
 * phone. The mock answers the BFF's shapes and runs requests on a clock: 6 s to approve, 8 s to
 * apply.
 */

const shots = fileURLToPath(new URL('./screenshots/', import.meta.url))
const shot = (name: string) => `${shots}${name}.png`

const ACCOUNT = '123456789012'
const wfrnOf = (id: string) => `wfrn:wf:compute:global:${ACCOUNT}:vm/${id}`
const vmPage = (id: string, query = '') => `/console/compute/vm/${encodeURIComponent(wfrnOf(id))}${query}`

/** Picks an option of a form's Select, found by its label: its name reads "Memory (GiB): 16". */
async function choose(scope: Locator, page: Page, label: string, option: string) {
  const select = scope.getByRole('button', { name: new RegExp(`^${label.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')}: `) })
  await select.click()
  await page.getByRole('option', { name: option, exact: true }).click()
  await expect(select).toHaveAccessibleName(`${label}: ${option}`)
}

test.describe('changing resources safely', () => {
  test('Gonzalo edits a VM’s memory: what changes, the preview and the code, then the page follows the request to the new value', async ({ page }) => {
    test.setTimeout(90_000)
    await signInAs(page, /Continue as Gonzalo Munoz/, vmPage('vm-demo03'))
    await expect(page.getByRole('heading', { name: 'web-02', level: 1 })).toBeVisible()

    // Edit configuration, from the Actions menu
    await page.getByRole('button', { name: 'Actions', exact: true }).click()
    const menu = page.getByRole('menu', { name: 'Actions for web-02' })
    const item = menu.getByRole('menuitem', { name: /Edit configuration/ })
    await expect(item).not.toHaveAttribute('data-phase')
    await expect(item).not.toHaveAttribute('data-denied')
    await item.click()
    await expect(page).toHaveURL(/action=edit/)
    const drawer = page.getByRole('dialog', { name: 'Edit configuration: web-02' })
    await expect(drawer).toBeVisible()

    // the form starts from what the VM is asked to be; nothing changed, nothing to send
    await expect(drawer.getByRole('button', { name: /^Memory \(GiB\): / })).toHaveAccessibleName('Memory (GiB): 16')
    await expect(drawer.getByRole('button', { name: /^CPUs: / })).toHaveAccessibleName('CPUs: 4')
    await expect(drawer.getByText(/^Nothing to change yet\. Change a field above/)).toBeVisible()
    const send = drawer.locator('[data-verb="update"]')
    await expect(send).toBeDisabled()
    await expect(send).toHaveAccessibleName(/Nothing to change yet/)

    // what changes: only the field that changes, now and after
    await choose(drawer, page, 'Memory (GiB)', '32')
    const diff = drawer.getByRole('table', { name: /What changes/ })
    await expect(diff).toBeVisible()
    await expect(diff.locator('tbody tr')).toHaveCount(1)
    const row = diff.locator('tr[data-field="memoryGb"]')
    await expect(row.getByRole('rowheader')).toHaveText('Memory (GiB)')
    await expect(row.getByRole('cell').nth(0)).toHaveText('16 GiB')
    await expect(row.getByRole('cell').nth(1)).toHaveText('32 GiB')
    await drawer.locator('#edit-reason').fill('the shop needs more cache')

    // the preview of the update: a prod VM waits for an approver
    const preview = drawer.getByTestId('preview-card')
    await expect(preview).toContainText('Needs approval')
    await expect(preview).toContainText('memoryGb 16 → 32')

    // the request as code: only the changed field, and the version it was read at
    await drawer.getByText('View as code').click()
    const code = drawer.getByTestId('code-view')
    await expect(code).toContainText('iaf compute update-vm')
    await expect(code).toContainText('--memory-gb 32')
    await expect(send).toBeEnabled()
    await page.screenshot({ path: shot('edit-drawer-desktop'), fullPage: true })
    await expectNoSeriousA11y(page, 'edit configuration drawer')

    await send.click()
    await expect(drawer).toBeHidden()
    await expect(page).not.toHaveURL(/action=/)
    await expect(page.getByText('Update VM requested')).toBeVisible()
    const banner = page.locator('[data-job-status]')
    await expect(banner).toHaveAttribute('data-job-status', 'waiting-approval')

    // while it waits and runs, Edit waits for it too
    await page.getByRole('button', { name: 'Actions', exact: true }).click()
    await expect(menu.getByRole('menuitem', { name: /Edit configuration/ })).toHaveAttribute('data-unavailable', 'true')
    await page.keyboard.press('Escape')

    // approved, applied: the page reads the VM again and shows what it is now asked to be
    await expect(page.locator('[data-job-status]')).toHaveCount(0, { timeout: 45_000 })
    const memory = page.locator('dl > div', { has: page.locator('dt', { hasText: /^Memory/ }) }).locator('dd')
    await expect(memory).toHaveText(/^32\b/)
    await expect(page.locator('dl > div', { has: page.locator('dt', { hasText: /^Cpu/i }) }).locator('dd')).toHaveText(/^4\b/)
    await memory.scrollIntoViewIfNeeded()
    await page.screenshot({ path: shot('edit-applied-desktop'), fullPage: true })
  })

  test('a delete is refused while other resources depend on the VM, and its blast radius names them', async ({ page }) => {
    await signInAs(page, /Continue as Gonzalo Munoz/, vmPage('vm-demo01', '?action=delete'))
    const drawer = page.getByRole('dialog', { name: 'Delete VM: web-01' })
    await expect(drawer).toBeVisible()
    await drawer.locator('#action-delete-reason').fill('replaced by a larger VM')

    // words first: what would be left without what it was built from, nearest first
    const card = drawer.getByTestId('blast-radius')
    await expect(card).toBeVisible()
    await expect(card).toHaveAttribute('data-total', '2')
    await expect(card.getByRole('heading', { name: 'What depends on web-01' })).toBeVisible()
    await expect(card).toContainText('Deleting web-01 would leave 2 resources without what they were built from: 2 depend on it directly.')
    const direct = card.getByRole('list', { name: /Directly/ })
    await expect(direct.getByRole('listitem')).toHaveCount(2)
    await expect(direct.getByRole('link', { name: 'shop-prod' })).toBeVisible()
    await expect(direct.getByRole('link', { name: 'volume-demo01' })).toBeVisible()
    await expect(direct.getByRole('listitem').filter({ hasText: 'shop-prod' })).toContainText('Environment')
    // every dependent lives in this account: nothing counted elsewhere
    await expect(card.getByRole('list', { name: 'In other accounts' })).toHaveCount(0)

    // the policy denies it, and the drawer says what to do instead
    await expect(drawer.getByTestId('preview-card')).toContainText('Denied')
    await expect(drawer.locator('[data-held-back="dependents"]')).toHaveText('Nothing can be deleted while other resources depend on it. Delete them or move them onto something else first, then delete web-01.')
    const del = drawer.locator('[data-verb="delete"]')
    await expect(del).toBeDisabled()
    await expect(del).toHaveAccessibleName(/Held back: other resources depend on web-01/)
    await del.click({ force: true })
    await expect(page.getByRole('dialog', { name: 'Delete VM: web-01?' })).toHaveCount(0)
    await page.screenshot({ path: shot('blast-radius-desktop'), fullPage: true })
    await expectNoSeriousA11y(page, 'delete drawer with its blast radius')

    // a dependent is a link to its own page
    await direct.getByRole('link', { name: 'volume-demo01' }).click()
    await expect(page.getByRole('heading', { name: 'volume-demo01', level: 1 })).toBeVisible()
    await expect(page).not.toHaveURL(/action=/)
  })

  test('nothing depends on a VM: the delete says so calmly and goes on to its confirmation', async ({ page }) => {
    await signInAs(page, /Continue as Gonzalo Munoz/, vmPage('vm-gen-001', '?action=delete'))
    const drawer = page.getByRole('dialog', { name: 'Delete VM: api-011' })
    await drawer.locator('#action-delete-reason').fill('decommissioned after the migration')
    await expect(drawer.getByTestId('preview-card')).toContainText('Needs approval')
    // no warning card for good news; the confirmation says it in one line
    await expect(drawer.getByTestId('blast-radius')).toHaveCount(0)
    await drawer.locator('[data-verb="delete"]').click()
    const confirm = page.getByRole('dialog', { name: 'Delete VM: api-011?' })
    await expect(confirm.getByTestId('confirm-blast-radius')).toHaveText('Nothing else depends on it.')
    await confirm.getByRole('button', { name: 'Keep it' }).click()
    await expect(confirm).toBeHidden()
  })

  test('Mia, a viewer, sees Edit locked with the reason, in the menu and on the Configuration tab', async ({ page }) => {
    await signInAs(page, /Continue as Mia Chen/, vmPage('vm-demo03'))
    await expect(page.getByRole('heading', { name: 'web-02', level: 1 })).toBeVisible()

    await page.getByRole('button', { name: 'Actions', exact: true }).click()
    const item = page.getByRole('menu', { name: 'Actions for web-02' }).getByRole('menuitem', { name: /Edit configuration/ })
    await expect(item).toHaveAttribute('data-denied', /.+/)
    await expect(item).toContainText('Not allowed:')
    await page.keyboard.press('Escape')

    // the Configuration tab's Edit: the same lock, and the refusal behind it
    const edit = page.locator('main [data-verb="update"]').first()
    await expect(edit).toHaveAttribute('data-denied', /.+/)
    await expect(edit).toHaveAccessibleName(/Not allowed/)
    await edit.click()
    const refusal = page.getByRole('dialog', { name: /Edit this virtual machine/i })
    await expect(refusal).toBeVisible()
    await page.screenshot({ path: shot('edit-denied-desktop'), fullPage: true })
    await expectNoSeriousA11y(page, 'edit refused for a viewer')
  })

  test('the edit drawer and the blast radius fit a phone', async ({ page }) => {
    await page.setViewportSize({ width: 390, height: 844 })
    await signInAs(page, /Continue as Gonzalo Munoz/, vmPage('vm-demo03', '?action=edit'))
    const drawer = page.getByRole('dialog', { name: 'Edit configuration: web-02' })
    await expect(drawer).toBeVisible()
    await choose(drawer, page, 'Memory (GiB)', '32')
    await expect(drawer.getByRole('table', { name: /What changes/ })).toBeVisible()
    await expect(drawer.getByTestId('preview-card')).toContainText('Needs approval')
    await expectNoHorizontalOverflow(page)
    const box = await drawer.boundingBox()
    expect(box && box.x >= 0 && box.x + box.width <= 390).toBe(true)
    await page.screenshot({ path: shot('edit-drawer-phone'), fullPage: true })
    await expectNoSeriousA11y(page, 'edit drawer at phone width')

    await page.goto(`/#${vmPage('vm-demo01', '?action=delete')}`)
    const del = page.getByRole('dialog', { name: 'Delete VM: web-01' })
    await del.locator('#action-delete-reason').fill('replaced by a larger VM')
    const card = del.getByTestId('blast-radius')
    await expect(card).toBeVisible()
    await expectNoHorizontalOverflow(page)
    const cardBox = await card.boundingBox()
    expect(cardBox && cardBox.x >= 0 && cardBox.x + cardBox.width <= 390).toBe(true)
    await page.screenshot({ path: shot('blast-radius-phone'), fullPage: true })
    await expectNoSeriousA11y(page, 'blast radius at phone width')
  })
})
