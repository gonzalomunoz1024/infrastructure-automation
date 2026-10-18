import { expect, test } from '@playwright/test'
import { registryPlaybooks, registryProviders } from './designer-fixtures'
import { NOTIFICATIONS, REVIEW_NOTIFICATION, WORKFLOWS, WORKFLOW_CR, WORKFLOW_CR_DEFINITION, changeRequestRoutes, inboxRoutes, mockApi, reply } from './fixtures'

/*
 * The workflows page against a mocked /api/v1: the New workflow dialog's imports send the
 * reference the backend resolves for each source, and the Change requests tab lists what waits
 * for review, of both kinds, by status.
 */

const IMPORTED = 'apiVersion: iaf.platform/v1\nkind: Workflow\nmetadata:\n  name: block-storage-request\n  title: Request block storage\n'

test.describe('workflows: import', () => {
  test('an IDP import lists the tiles of the idp providers and sends the chosen tile id', async ({ page }) => {
    const api = await mockApi(page, {
      'GET /workflows': WORKFLOWS,
      'GET /registry/providers': registryProviders(),
      'GET /registry/playbooks': registryPlaybooks(),
      'POST /workflows:import': { yaml: IMPORTED, notes: ['The IDP tile runs as a mock until the IDP tiles API is wired.'] },
    })
    await page.goto('/#/workflows')
    await page.getByRole('button', { name: 'New workflow' }).first().click()
    const dialog = page.getByRole('dialog', { name: 'New workflow' })
    await dialog.getByRole('button', { name: /^Import/ }).click()
    await dialog.getByRole('radio', { name: 'IDP tile' }).click()
    await expect(dialog.locator('label').filter({ hasText: /^Tile/ })).toBeVisible()
    await dialog.getByRole('button', { name: 'Reference' }).click()
    const tiles = dialog.getByRole('listbox', { name: 'Reference' }).getByRole('option')
    await expect(tiles.first()).toContainText('Request block storage')
    await expect(tiles.first()).toContainText('IDP tiles · block-storage-request')
    await tiles.first().click()
    await dialog.getByRole('button', { name: 'Import', exact: true }).click()
    await expect(dialog.getByText('The IDP tile runs as a mock until the IDP tiles API is wired.')).toBeVisible()
    expect(api.callsTo('POST', '/workflows:import')[0].body).toEqual({ source: 'idp', ref: 'block-storage-request' })

    // the other sources keep their references: a library playbook as <provider>/<playbook>, a provider by name
    await dialog.getByRole('radio', { name: 'Ansible playbook' }).click()
    await dialog.getByRole('button', { name: 'Reference' }).click()
    await dialog.getByRole('option', { name: /ops-playbooks\/vm\.restart/ }).click()
    await dialog.getByRole('button', { name: 'Import', exact: true }).click()
    await expect.poll(() => api.callsTo('POST', '/workflows:import').length).toBe(2)
    expect(api.callsTo('POST', '/workflows:import')[1].body).toEqual({ source: 'ansible', ref: 'ops-playbooks/vm.restart' })
    await dialog.getByRole('radio', { name: 'Terraform module' }).click()
    await dialog.getByRole('button', { name: 'Reference' }).click()
    await dialog.getByRole('option', { name: /Terraform modules/ }).click()
    await dialog.getByRole('button', { name: 'Import', exact: true }).click()
    await expect.poll(() => api.callsTo('POST', '/workflows:import').length).toBe(3)
    expect(api.callsTo('POST', '/workflows:import')[2].body).toEqual({ source: 'terraform', ref: 'terraform-modules' })
  })
})

test.describe('workflows: blank', () => {
  test('a blank workflow lets its owner team run it, and leaves out what was left empty', async ({ page }) => {
    const api = await mockApi(page, { 'GET /workflows': WORKFLOWS, 'POST /workflows': reply(202, { id: 'service-restart', status: 'accepted' }) })
    await page.goto('/#/workflows')
    await page.getByRole('button', { name: 'New workflow' }).first().click()
    const dialog = page.getByRole('dialog', { name: 'New workflow' })
    await dialog.getByRole('button', { name: /^Blank/ }).click()
    await dialog.getByLabel('Title').fill('Restart a service')
    await expect(dialog.getByRole('textbox', { name: 'Name', exact: true })).toHaveValue('restart-a-service')
    // a name typed over the one the title suggests wins
    await dialog.getByRole('textbox', { name: 'Name', exact: true }).fill('service-restart')
    await dialog.getByLabel('Owner').fill('team-platform')
    await dialog.getByRole('button', { name: 'Create and open' }).click()
    await expect.poll(() => api.callsTo('POST', '/workflows').length).toBe(1)
    const yaml = (api.callsTo('POST', '/workflows')[0].body as { yaml: string }).yaml
    expect(yaml).toContain('name: service-restart\n')
    expect(yaml).toContain('policy:\n  rbac:\n    - team-platform\n')
    expect(yaml).not.toMatch(/noun|verb|folder|null/)
  })
})

test.describe('workflows: change requests', () => {
  test('a reviewer finds the open change requests of both kinds on the tab, filters them by status and opens one', async ({ page }) => {
    const api = await mockApi(page, {
      'GET /workflows': WORKFLOWS,
      ...changeRequestRoutes(),
      'GET /change-requests/{id}': WORKFLOW_CR,
      'POST /workflows:parse': { definition: WORKFLOW_CR_DEFINITION, problems: [] },
      'GET /registry/providers': [],
    })
    await page.goto('/#/workflows')
    const views = page.getByRole('tablist', { name: 'Workflows views' })
    // the open ones count on the tab while the workflows show
    const tab = views.getByRole('tab', { name: /Change requests/ })
    await expect(tab).toContainText('2')
    await expect(page.getByRole('table', { name: 'Workflows' })).toBeVisible()
    await tab.click()
    await expect(page).toHaveURL(/#\/workflows\?tab=change-requests$/)
    await expect(tab).toHaveAttribute('aria-selected', 'true')

    const table = page.getByRole('table', { name: 'Change requests' })
    await expect(table.getByRole('row')).toHaveCount(3)
    const workflow = table.getByRole('row', { name: /Publish vm-restart 2\.0\.0/ })
    for (const text of ['cr-202', 'workflow', 'vm-restart', '2.0.0', 'gonzalo', 'open']) await expect(workflow).toContainText(text)
    await expect(workflow.getByRole('img', { name: 'Checks: 1 failed, 3 passed' })).toBeVisible()
    const provider = table.getByRole('row', { name: /Add provider backup-service/ })
    for (const text of ['cr-101', 'provider', 'backup-service', '1.0.0']) await expect(provider).toContainText(text)
    expect(api.callsTo('GET', '/change-requests').map((c) => c.query)).toContain('?status=open')
    expect(api.callsTo('GET', '/change-requests').every((c) => c.query === '?status=open')).toBe(true)

    // the status filter asks the backend and lands in the address
    const status = page.getByRole('radiogroup', { name: 'Change request status' })
    await expect(status.getByRole('radio', { name: 'Open' })).toHaveAttribute('aria-checked', 'true')
    await status.getByRole('radio', { name: 'Merged' }).click()
    await expect(page).toHaveURL(/status=merged/)
    await expect(table.getByRole('row')).toHaveCount(2)
    await expect(table.getByRole('row', { name: /Publish vm-restart 1\.2\.0/ })).toContainText('merged')
    await expect.poll(() => api.callsTo('GET', '/change-requests').some((c) => c.query === '?status=merged')).toBe(true)
    await status.getByRole('radio', { name: 'All' }).click()
    await expect(table.getByRole('row')).toHaveCount(5)
    await expect.poll(() => api.callsTo('GET', '/change-requests').some((c) => c.query === '')).toBe(true)

    // a reload stays on the view; a row opens the change request
    await page.reload()
    await expect(page.getByRole('radiogroup', { name: 'Change request status' }).getByRole('radio', { name: 'All' })).toHaveAttribute('aria-checked', 'true')
    await page.getByRole('radiogroup', { name: 'Change request status' }).getByRole('radio', { name: 'Open' }).click()
    await page.getByRole('table', { name: 'Change requests' }).getByRole('row', { name: /Publish vm-restart 2\.0\.0/ }).click()
    await expect(page).toHaveURL(/#\/change-requests\/cr-202$/)
    await expect(page.getByRole('heading', { level: 1, name: 'Publish vm-restart 2.0.0' })).toBeVisible()
    await expect(page.getByRole('link', { name: 'Change requests' })).toHaveAttribute('href', '#/workflows?tab=change-requests')
  })

  test('with nothing open the tab counts nothing and says nothing waits for review', async ({ page }) => {
    await mockApi(page, { 'GET /workflows': WORKFLOWS, 'GET /change-requests': [] })
    await page.goto('/#/workflows?tab=change-requests')
    const tab = page.getByRole('tablist', { name: 'Workflows views' }).getByRole('tab', { name: /Change requests/ })
    await expect(tab).toHaveAttribute('aria-selected', 'true')
    await expect(page.getByText('Nothing waits for review')).toBeVisible()
    await expect(tab).toHaveText('Change requests')
  })

  test('the inbox asks the owner team to review and links the change request', async ({ page }) => {
    await mockApi(page, { 'GET /workflows': WORKFLOWS, ...changeRequestRoutes(), ...inboxRoutes([{ ...REVIEW_NOTIFICATION }, ...NOTIFICATIONS.map((n) => ({ ...n }))]) })
    await page.goto('/#/workflows')
    await page.getByTestId('notifications-bell').click()
    const first = page.getByRole('dialog', { name: 'Notifications' }).getByRole('list', { name: 'Notifications' }).getByRole('listitem').first()
    await expect(first).toContainText('Review requested: vm-restart 2.0.0')
    await expect(first).toContainText('gonzalo opened a change request for workflow vm-restart 2.0.0')
    await expect(first).toContainText('review')
    await expect(first.getByRole('link', { name: 'Open change request' })).toHaveAttribute('href', '#/change-requests/cr-202')
  })
})
