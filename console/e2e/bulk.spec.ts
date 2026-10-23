import { fileURLToPath } from 'node:url'
import { expect, test } from '@playwright/test'
import { expectNoHorizontalOverflow, expectNoSeriousA11y, signInAs } from './support'

/*
 * Phase 4, many changes and the requests that carry them, in mock mode: Mateo restarts the 50
 * active VMs on the list's first page as one request, which goes out in the workflow's own waves
 * (3, then 10, then 37 rows), each wave once the one before finished and its prod rows were
 * approved; and a restart whose step fails pauses, and goes on once Mateo retries it with the
 * reason changed. The mock runs requests on a clock: 6 s to approve, 8 s to apply a single
 * request, 1 s a bulk run's row.
 */

const shots = fileURLToPath(new URL('./screenshots/', import.meta.url))
const shot = (name: string) => `${shots}${name}.png`

const ACCOUNT = '123456789012'
const wfrnOf = (id: string) => `wfrn:wf:compute:global:${ACCOUNT}:vm/${id}`
const vmPage = (id: string) => `/console/compute/vm/${encodeURIComponent(wfrnOf(id))}`

test.describe('many changes, and the requests that carry them', () => {
  test('Mateo restarts 50 VMs as one request, in waves of 3, 10 and 37', async ({ page }) => {
    test.setTimeout(120_000)
    await signInAs(page, /Continue as Mateo Silva/, '/console/compute/vm?f=status%3Dactive')
    const table = page.getByRole('grid', { name: 'Virtual machines' })
    await expect(table.getByRole('row').nth(1)).toBeVisible()

    // the whole page selected: the selection bar offers the verbs that run in bulk
    await page.getByRole('checkbox', { name: 'Select all rows' }).check()
    const selection = page.getByRole('region', { name: 'Selection' })
    await expect(selection).toContainText('50 virtual machines selected')
    await selection.getByRole('button', { name: 'Actions', exact: true }).click()
    const menu = page.getByRole('menu', { name: 'Actions for 50 virtual machines' })
    await expect(menu.getByRole('menuitem', { name: /Delete VM/ })).toBeVisible()
    await menu.getByRole('menuitem', { name: /VM restart/ }).click()

    // the drawer: each row keeps its own environment, the waves the rollout makes, the preview
    const drawer = page.getByRole('dialog', { name: 'VM restart: 50 virtual machines' })
    await expect(drawer).toBeVisible()
    await expect(drawer.locator('[data-own-inputs]')).toHaveText('Each row keeps its own environment.')
    await expect(drawer.locator('[data-waves]')).toHaveAttribute('data-waves', '3,10,37')
    await expect(drawer.locator('[data-waves]')).toContainText('3, then 10, then 37 rows')
    await drawer.getByLabel('Reason').fill('monthly patch window')
    await expect(drawer.getByRole('list', { name: 'What policy says' })).toContainText(/rows wait for an approver from compute-approvers/)
    await expect(drawer.locator('[data-unrunnable]')).toHaveCount(0)

    // the other choices say what they would do, and the workflow's own comes back
    await drawer.getByRole('radio', { name: 'Two waves' }).click()
    await expect(drawer.locator('[data-waves]')).toHaveAttribute('data-waves', '5,45')
    await drawer.getByRole('radio', { name: 'Its own waves' }).click()
    await expect(drawer.locator('[data-waves]')).toHaveAttribute('data-waves', '3,10,37')
    await expectNoSeriousA11y(page, 'bulk drawer')
    await page.screenshot({ path: shot('bulk-drawer-desktop') })

    // sent: the request page follows the run, wave by wave
    await drawer.locator('[data-verb="restart"]').click()
    await expect(page).toHaveURL(/#\/requests\/job_/)
    await expect(page.getByText('Bulk request', { exact: true })).toBeVisible()
    const waves = page.getByRole('list', { name: 'Waves' })
    await expect(waves.locator('[data-wave]')).toHaveCount(3)
    await expect(waves.locator('[data-wave="0"]')).toContainText('3 rows')
    await expect(waves.locator('[data-wave="1"]')).toContainText('10 rows')
    await expect(waves.locator('[data-wave="2"]')).toContainText('37 rows')
    await expect(page.getByRole('grid', { name: 'The 50 rows of this run' })).toBeVisible()
    await expect(waves.locator('[data-wave="0"]')).not.toHaveAttribute('data-wave-status', 'pending')
    await expect(waves.locator('[data-wave="2"]')).toHaveAttribute('data-wave-status', 'pending')
    await page.screenshot({ path: shot('bulk-waves-desktop') })

    // every wave went out in turn, and every row succeeded
    await expect(page.locator('[data-batch-progress]')).toHaveAttribute('data-batch-progress', '50/50', { timeout: 60_000 })
    for (const i of [0, 1, 2]) await expect(waves.locator(`[data-wave="${i}"]`)).toHaveAttribute('data-wave-status', 'succeeded')
    await expect(page.getByText('50 rows succeeded')).toBeVisible()
    await expect(page.locator('[data-job-status]')).toHaveAttribute('data-job-status', 'succeeded')
    await expectNoSeriousA11y(page, 'bulk request page')
  })

  test('a restart whose step fails pauses, and goes on once Mateo retries it with the reason changed', async ({ page }) => {
    test.setTimeout(90_000)
    await signInAs(page, /Continue as Mateo Silva/, vmPage('vm-demo02'))
    await expect(page.getByRole('heading', { name: 'api-01', level: 1 })).toBeVisible()
    await page.getByRole('button', { name: 'Actions', exact: true }).click()
    await page.getByRole('menu', { name: 'Actions for api-01' }).getByRole('menuitem', { name: /VM restart/ }).click()
    const drawer = page.getByRole('dialog', { name: 'VM restart: api-01' })
    await drawer.getByLabel('Reason').fill('fail on purpose')
    await drawer.locator('[data-verb="restart"]').click()
    await page.getByRole('button', { name: 'View request' }).click()
    await expect(page).toHaveURL(/#\/requests\/job_/)

    // the apply step fails: the request pauses on it and says what went wrong and the ways on
    const paused = page.locator('[data-attention]')
    await expect(paused).toBeVisible({ timeout: 20_000 })
    await expect(paused.getByRole('heading', { name: /^Paused on / })).toBeVisible()
    await expect(page.locator('[data-failure-card]')).toBeVisible()
    for (const name of ['Retry the step', 'Retry with changes', 'Skip it', 'Abort the request']) await expect(paused.getByRole('button', { name })).toBeVisible()
    await expectNoSeriousA11y(page, 'paused request')
    await page.screenshot({ path: shot('request-paused-desktop') })

    // retry with changes: only the changed reason is sent, and the request goes on in place
    await paused.getByRole('button', { name: 'Retry with changes' }).click()
    const dialog = page.getByRole('dialog', { name: 'Retry with changes' })
    await expect(dialog.getByLabel('Reason')).toHaveValue('fail on purpose')
    await dialog.getByLabel('Reason').fill('stuck after a kernel update')
    await dialog.getByRole('button', { name: 'Retry', exact: true }).click()
    await expect(dialog).toBeHidden()
    await expect(paused).toBeHidden()
    await expect(page.locator('[data-job-status]')).toHaveAttribute('data-job-status', 'running')
    await expect(page.locator('[data-job-status]')).toHaveAttribute('data-job-status', 'succeeded', { timeout: 30_000 })
    await expect(page.locator('[data-failure-card]')).toHaveCount(0)
  })

  test('the bulk drawer fits a phone', async ({ page }) => {
    await page.setViewportSize({ width: 390, height: 844 })
    await signInAs(page, /Continue as Mateo Silva/, '/console/compute/vm?f=status%3Dactive')
    await expect(page.getByRole('grid', { name: 'Virtual machines' }).getByRole('row').nth(1)).toBeVisible()
    await page.getByRole('checkbox', { name: 'Select all rows' }).check()
    await page.getByRole('region', { name: 'Selection' }).getByRole('button', { name: 'Actions', exact: true }).click()
    await page.getByRole('menuitem', { name: /VM restart/ }).click()
    await expect(page.getByRole('dialog', { name: 'VM restart: 50 virtual machines' })).toBeVisible()
    await expectNoHorizontalOverflow(page)
    await expectNoSeriousA11y(page, 'bulk drawer at phone width')
  })
})
