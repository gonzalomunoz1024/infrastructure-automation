import { expect, test } from '@playwright/test'
import { registryPlaybooks, registryProviders } from './designer-fixtures'
import { WORKFLOWS, mockApi } from './fixtures'

/*
 * The workflows page against a mocked /api/v1: the New workflow dialog's imports send the
 * reference the backend resolves for each source.
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
