import { expect, test, type Page } from '@playwright/test'
import { parse, stringify } from 'yaml'
import { mockDesignerApi, vmRestartYaml, type MockApi } from './designer-fixtures'

/*
 * The designer against the mocked backend: the API settings beyond method and path. A file that
 * deprecates its operation and describes its response body opens into the Settings fields, marks
 * the header, and saves unchanged; the fields write the same keys the checks read.
 */

const DEPRECATED = { since: '2026-10-01', sunset: '2027-01-31', replacement: '/api/v2/vms/{vmId}:restart', message: 'Use v2, which restarts through the maintenance window.' }
const RESPONSE_BODY = { type: 'object', properties: { jobId: { type: 'string' }, powerState: { type: 'string' } } }

type Doc = { api: Record<string, unknown>; workflow: unknown }

/** The sample vm-restart with a deprecation and a response body, as a team would write it. */
function deprecatedVmRestart(): Doc {
  const doc = parse(vmRestartYaml()) as Doc
  const response = (doc.api.response ?? {}) as Record<string, unknown>
  doc.api = { ...doc.api, response: { ...response, body: RESPONSE_BODY }, deprecated: DEPRECATED }
  return doc
}

async function openVmRestart(page: Page): Promise<MockApi> {
  const api = await mockDesignerApi(page)
  await page.goto('/#/workflows/vm-restart')
  await expect(page.locator('.react-flow__node[data-id="step:checkVmExists"]')).toBeVisible()
  return api
}

/** Replaces the workflow through the YAML drawer, the way a file is pasted in. */
async function loadYaml(page: Page, yaml: string): Promise<void> {
  await page.getByRole('button', { name: 'YAML' }).click()
  const editor = page.getByTestId('yaml-editor')
  await expect(editor).toHaveValue(/name: /)
  await editor.fill(yaml)
  await page.getByRole('button', { name: 'Apply YAML' }).click()
  await expect(page.getByText('YAML applied')).toBeVisible()
  await page.keyboard.press('Escape')
  await expect(editor).toBeHidden()
}

test.describe('API settings', () => {
  test('a deprecated operation with a response body shows in the header and the settings, and saves unchanged', async ({ page }) => {
    const api = await openVmRestart(page)
    const doc = deprecatedVmRestart()
    await loadYaml(page, stringify(doc))
    await expect(page.getByTestId('deprecated-tag')).toBeVisible()
    await expect(page.getByTestId('deprecated-tag').getByTitle(/^Deprecated since 2026-10-01; sunset 2027-01-31/)).toBeVisible()

    await page.getByRole('tab', { name: 'Settings' }).click()
    const section = page.getByTestId('settings-view').getByRole('region', { name: 'API' })
    await expect(section.getByRole('textbox', { name: /^Deprecated since/ })).toHaveValue('2026-10-01')
    await expect(section.getByRole('textbox', { name: /^Sunset/ })).toHaveValue('2027-01-31')
    await expect(section.getByRole('textbox', { name: /^Replacement/ })).toHaveValue('/api/v2/vms/{vmId}:restart')
    await expect(section.getByRole('textbox', { name: /^Message/ })).toHaveValue(DEPRECATED.message)
    await expect(section.getByRole('textbox', { name: /^Response body/ })).toHaveValue(/"powerState"/)

    await page.getByTestId('save-button').click()
    await expect.poll(() => api.drafts.length).toBe(1)
    const saved = api.drafts[0].definition as Doc
    expect(saved.api).toEqual(doc.api)
    expect(saved.workflow).toEqual(doc.workflow)
  })

  test('the settings write the deprecation and the response body where the checks read them', async ({ page }) => {
    const api = await openVmRestart(page)
    await page.getByRole('tab', { name: 'Settings' }).click()
    const section = page.getByTestId('settings-view').getByRole('region', { name: 'API' })
    await section.getByRole('textbox', { name: /^Deprecated since/ }).fill('2026-11-01')
    await section.getByRole('textbox', { name: /^Sunset/ }).fill('2027-05-01')
    await section.getByRole('textbox', { name: /^Replacement/ }).fill('/api/v2/vms/{vmId}:restart')
    const body = section.getByRole('textbox', { name: /^Response body/ })
    await body.fill('{ "type": "object", "properties": { "jobId": { "type": "string" } } }')
    await body.blur()
    await expect(page.getByTestId('deprecated-tag')).toBeVisible()

    await page.getByTestId('save-button').click()
    await expect.poll(() => api.drafts.length).toBe(1)
    const saved = api.drafts[0].definition as Doc
    expect(saved.api.deprecated).toEqual({ since: '2026-11-01', sunset: '2027-05-01', replacement: '/api/v2/vms/{vmId}:restart' })
    expect((saved.api.response as { body: unknown }).body).toEqual({ type: 'object', properties: { jobId: { type: 'string' } } })
    // the original keys stay
    const original = parse(vmRestartYaml()) as Doc
    expect(saved.api.method).toEqual(original.api.method)
    expect(saved.api.path).toEqual(original.api.path)
    expect(saved.api.input).toEqual(original.api.input)
  })
})
