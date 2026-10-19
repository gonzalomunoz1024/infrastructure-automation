import { expect, test, type Locator, type Page, type Route } from '@playwright/test'
import { mockDesignerApi } from './designer-fixtures'
import { CONNECTION, CREDENTIALS, PROVIDER_CR, SPEC, TRY_RESULT, VALIDATION } from './fixtures'

/*
 * The Provider sheet over the canvas: a new system connected from its OpenAPI spec, its connection
 * tested, an action picked and tried, the provider reviewed, and the action on the canvas as a
 * step. The node creator's "Connect a new system" entries open the sheet in the product; this spec
 * opens it the way they do, through the designer store's openProviderSheet, so it does not depend
 * on them.
 */

type Sheet = { entry: string; provider?: string; target?: unknown }

function node(page: Page, id: string): Locator {
  return page.locator(`.react-flow__node[data-id="${id}"]`)
}

/** Calls the open designer's store, found through React's fiber from the designer's root element. */
async function openSheet(page: Page, options: Sheet): Promise<void> {
  // as a string: this project's e2e types carry no DOM
  const opened = await page.evaluate(`(() => {
    const el = document.querySelector('[data-testid="designer"]')
    const key = el && Object.keys(el).find((k) => k.startsWith('__reactFiber$'))
    for (let f = key ? el[key] : null; f; f = f.return) {
      const store = f.memoizedProps && f.memoizedProps.value
      if (store && typeof store.openProviderSheet === 'function') {
        store.openProviderSheet(${JSON.stringify(options)})
        return true
      }
    }
    return false
  })()`)
  expect(opened).toBe(true)
}

interface ProviderCalls {
  imports: unknown[]
  tests: unknown[]
  tries: unknown[]
  validations: unknown[]
  drafts: unknown[]
  publishes: unknown[]
}

/** What Devin drafts from a description: an http provider, its file named and owned. */
const DEVIN_YAML = [
  'apiVersion: iaf.platform/v1',
  'kind: Provider',
  'metadata:',
  '  name: dns-records',
  '  title: DNS records',
  '  owner: team-compute',
  '  version: 1.0.0',
  'spec:',
  '  type: http',
  '  endpoints:',
  '    dev: https://dns-dev.example.com/api',
  '  auth:',
  '    type: bearer',
  '    credential: vault:dns/token',
  '  actions:',
  '    - id: records.list',
  '      title: List records',
  '      operation: { method: GET, path: /records }',
  '      effect: read',
  '      retry: safe',
  '    - id: record.upsert',
  '      title: Upsert a record',
  '      operation: { method: PUT, path: "/records/{name}" }',
  '      effect: change',
  '      retry: safe',
  '',
].join('\n')

/** The provider endpoints the sheet calls, over the designer's own mocks. */
async function mockProviderApi(page: Page): Promise<ProviderCalls> {
  const calls: ProviderCalls = { imports: [], tests: [], tries: [], validations: [], drafts: [], publishes: [] }
  const json = (route: Route, body: unknown) => route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(body) })
  await page.route('**/api/v1/**', async (route) => {
    const req = route.request()
    const path = new URL(req.url()).pathname.replace(/^\/api\/v1/, '')
    const method = req.method()
    if (method === 'GET' && path === '/credentials') return json(route, CREDENTIALS)
    if (method === 'POST' && path === '/providers:import-spec') {
      calls.imports.push(req.postDataJSON())
      return json(route, SPEC)
    }
    if (method === 'POST' && path === '/providers:test-connection') {
      calls.tests.push(req.postDataJSON())
      return json(route, CONNECTION)
    }
    if (method === 'POST' && path === '/providers:try') {
      calls.tries.push(req.postDataJSON())
      return json(route, TRY_RESULT)
    }
    if (method === 'POST' && path === '/providers:validate') {
      calls.validations.push(req.postDataJSON())
      return json(route, VALIDATION)
    }
    if (method === 'POST' && path === '/providers:draft') {
      calls.drafts.push(req.postDataJSON())
      return json(route, { yaml: DEVIN_YAML, summary: 'An http provider for the DNS API, with a read and an upsert.', assumptions: ['The token is a bearer token in vault:dns/token.'] })
    }
    if (method === 'POST' && path === '/providers:publish') {
      calls.publishes.push(req.postDataJSON())
      return json(route, { id: 'cr-201', status: 'open' })
    }
    if (method === 'GET' && path === '/change-requests/cr-201') return json(route, { ...PROVIDER_CR, id: 'cr-201', name: 'dns-records', status: 'open' })
    return route.fallback()
  })
  return calls
}

test.describe('the Provider sheet', () => {
  test('connects a system from its spec, tries an action, reviews it and puts the action on the canvas', async ({ page }) => {
    await mockDesignerApi(page)
    const calls = await mockProviderApi(page)
    await page.goto('/#/workflows/vm-restart')
    await expect(node(page, 'step:checkVmExists')).toBeVisible()

    // opened from the node creator after the approval: a new system, at Connect, OpenAPI picked
    const insert = { kind: 'insert', point: { kind: 'after', step: 'approval', handle: 'main' } }
    await openSheet(page, { entry: 'openapi', target: insert })
    const sheet = page.getByTestId('provider-sheet')
    await expect(sheet.getByRole('heading', { name: /New provider/ })).toBeVisible()
    const rail = sheet.getByRole('navigation', { name: 'Provider sections' })
    await expect(rail.locator('[aria-current="step"]')).toContainText('Connect')
    await expect(sheet.getByRole('radio', { name: 'OpenAPI' })).toHaveAttribute('aria-checked', 'true')
    await expect(sheet.getByRole('textbox', { name: 'Owner', exact: true })).toHaveValue('team-compute')

    // Connect: name it, import the spec, sign in, test the connection
    await sheet.getByRole('textbox', { name: 'Title', exact: true }).fill('Backup service')
    await expect(sheet.getByRole('textbox', { name: 'Name', exact: true })).toHaveValue('backup-service')
    await sheet.getByRole('textbox', { name: 'Spec URL' }).fill('https://backup.example.com/openapi.json')
    await sheet.getByRole('button', { name: 'Import spec' }).click()
    await expect(page.getByText('2 operations from Backup service 1.0.0.')).toBeVisible()
    expect(calls.imports).toEqual([{ type: 'openapi', url: 'https://backup.example.com/openapi.json' }])
    await expect(sheet.getByRole('textbox', { name: 'Environment URL' }).first()).toHaveValue('https://backup-dev.example.com/api')
    await sheet.getByRole('button', { name: 'Credential', exact: true }).click()
    await page.getByRole('option', { name: /backup\/api-key/ }).click()
    await sheet.getByRole('button', { name: 'Test connection' }).click()
    await expect(sheet.getByRole('list', { name: 'Connection results' })).toContainText('200 from GET /jobs')
    // kept as the new provider's review evidence, for the workflow's team
    expect(calls.tests).toEqual([
      {
        endpoints: { dev: 'https://backup-dev.example.com/api', prod: 'https://backup.example.com/api' },
        auth: { type: 'header', credential: 'vault:backup/api-key', header: 'X-Api-Key' },
        provider: 'backup-service',
        owner: 'team-compute',
      },
    ])

    // closing keeps the draft: the same entry opened again carries on with it, everything still there
    await sheet.getByRole('button', { name: 'Close', exact: true }).click()
    await expect(sheet).toHaveCount(0)
    const stored = String(await page.evaluate(`localStorage.getItem('iaf-designer-provider-drafts:vm-restart')`))
    expect(stored).toContain('"name":"backup-service"')
    expect(stored).toContain('"credential":"vault:backup/api-key"')
    await openSheet(page, { entry: 'openapi', target: insert })
    await expect(sheet.getByTestId('provider-sheet-resumed')).toContainText('Carrying on with the draft left unfinished here.')
    await expect(sheet.getByRole('textbox', { name: 'Title', exact: true })).toHaveValue('Backup service')
    await expect(sheet.getByRole('list', { name: 'Connection results' })).toContainText('200 from GET /jobs')
    await expect(sheet.getByRole('button', { name: 'Discard draft' })).toBeVisible()

    // Actions: the spec's operations, both selected
    await rail.getByRole('button', { name: /Actions/ }).click()
    const ops = sheet.getByRole('table', { name: 'Operations' })
    await expect(ops).toContainText('listJobs')
    await expect(ops).toContainText('createJob')

    // pick the action to use; Try it opens on it
    await sheet.getByRole('button', { name: 'Action to use' }).click()
    await page.getByRole('option', { name: /listJobs/ }).click()
    await rail.getByRole('button', { name: /Try it/ }).click()
    await expect(sheet.getByRole('button', { name: 'Action', exact: true })).toContainText('listJobs')
    // Escape on a list open in the sheet closes the list only: the sheet stays, and nothing is inserted
    await sheet.getByRole('button', { name: 'Environment', exact: true }).click()
    await expect(page.getByRole('listbox', { name: 'Environment' })).toBeVisible()
    await page.keyboard.press('Escape')
    await expect(page.getByRole('listbox', { name: 'Environment' })).toHaveCount(0)
    await expect(sheet).toBeVisible()
    await expect(page.getByTestId('ndv')).toHaveCount(0)
    await sheet.getByRole('button', { name: 'Try it', exact: true }).click()
    await expect(page.getByText('HTTP 200 in 182ms.')).toBeVisible()
    expect((calls.tries[0] as { action: string; environment: string }).action).toBe('listJobs')
    expect((calls.tries[0] as { environment: string }).environment).toBe('dev')
    await sheet.getByRole('button', { name: 'Record as sample' }).click()
    await expect(sheet.getByRole('button', { name: 'Sample recorded' })).toBeDisabled()

    // Review: the file, its checks, and Publish provider only
    await sheet.getByRole('button', { name: 'Next' }).click()
    await expect(rail.locator('[aria-current="step"]')).toContainText('Review')
    for (const line of ['name: backup-service', 'owner: team-compute', 'credential: vault:backup/api-key', 'operation: listJobs', 'bk-9001']) await expect(sheet.locator('pre').first()).toContainText(line)
    await sheet.getByRole('button', { name: 'Run checks' }).click()
    await expect(sheet.getByText('Ready to publish.')).toBeVisible()
    expect(String((calls.validations[0] as { yaml: string }).yaml)).toContain('kind: Provider')
    await expect(sheet.getByRole('button', { name: 'Publish provider only' })).toBeEnabled()

    // Use this action: the sheet closes and the step is on the canvas after the approval, opened
    await sheet.getByRole('button', { name: 'Use this action' }).click()
    await expect(sheet).toHaveCount(0)
    const ndv = page.getByTestId('ndv')
    await expect(ndv.getByRole('textbox', { name: 'Step name' })).toHaveValue('List backup jobs')
    await ndv.getByRole('button', { name: 'Back to canvas' }).click()
    await expect(page.locator('.react-flow__node').filter({ hasText: 'List backup jobs' })).toBeVisible()
    // the draft stays for the workflow's Publish
    expect(String(await page.evaluate(`localStorage.getItem('iaf-designer-provider-drafts:vm-restart')`))).toContain('"name":"backup-service"')
  })

  test("describes a system in words: Devin's draft is reviewed, and the action picked goes on the canvas when the sheet closes", async ({ page }) => {
    await mockDesignerApi(page)
    const calls = await mockProviderApi(page)
    await page.goto('/#/workflows/vm-restart')
    await expect(node(page, 'step:checkVmExists')).toBeVisible()

    // Describe it: Ask Devin opens over the sheet, and takes the focus and Escape first
    await openSheet(page, { entry: 'describe', target: { kind: 'insert', point: { kind: 'after', step: 'approval', handle: 'main' } } })
    const sheet = page.getByTestId('provider-sheet')
    const devin = page.getByRole('dialog', { name: 'Ask Devin to draft the provider' })
    await expect(devin).toBeVisible()
    await page.keyboard.press('Escape')
    await expect(devin).toHaveCount(0)
    await expect(sheet).toBeVisible()
    // asked again from Connect
    await sheet.getByRole('button', { name: 'Ask Devin to draft it' }).click()
    await expect(devin).toBeVisible()
    const description = devin.getByRole('textbox', { name: 'Description' })
    // the keyboard reaches the dialog's fields: the sheet under it no longer takes Tab
    await page.keyboard.press('Tab')
    await expect(devin.getByRole('button', { name: 'Close', exact: true })).toBeFocused()
    await page.keyboard.press('Tab')
    await expect(description).toBeFocused()
    await expect(description).toHaveValue(/An OpenAPI provider, owned by team-compute, version 1\.0\.0\./)
    await description.fill('The DNS API: list the records of a zone and upsert one. Owned by team-compute.')
    await devin.getByRole('button', { name: 'Ask Devin' }).click()
    await expect(devin.getByText('An http provider for the DNS API, with a read and an upsert.')).toBeVisible()
    expect(calls.drafts).toEqual([{ description: 'The DNS API: list the records of a zone and upsert one. Owned by team-compute.' }])
    await devin.getByRole('button', { name: 'Use this draft' }).click()
    await expect(devin).toHaveCount(0)

    // the review, on Devin's file, which names the provider and says its type
    const rail = sheet.getByRole('navigation', { name: 'Provider sections' })
    await expect(rail.locator('[aria-current="step"]')).toContainText('Review')
    await expect(sheet.getByRole('heading', { name: /DNS records/ })).toBeVisible()
    await expect(sheet.getByText('HTTP provider · a draft')).toBeVisible()
    await expect(sheet.locator('pre').first()).toContainText('name: dns-records')
    await expect(sheet.getByText("This draft replaces the form's YAML.")).toBeVisible()
    // the file's actions are not the form's: no Actions, and Try it is done from the step
    await expect(rail.getByRole('button')).toHaveText([/Connect/, /Try it/, /Review/])
    await rail.getByRole('button', { name: /Try it/ }).click()
    await expect(sheet.getByText('Tried from the step')).toBeVisible()

    // the file's actions are the ones to pick; closing once one is picked puts it on the canvas
    await sheet.getByRole('button', { name: 'Action to use' }).click()
    await expect(page.getByRole('option', { name: /records\.list/ })).toBeVisible()
    await page.getByRole('option', { name: /record\.upsert/ }).click()
    await page.keyboard.press('Escape')
    await expect(sheet).toHaveCount(0)
    await expect(page.getByTestId('ndv').getByRole('textbox', { name: 'Step name' })).toHaveValue('Upsert a record')
    expect(String(await page.evaluate(`localStorage.getItem('iaf-designer-provider-drafts:vm-restart')`))).toContain('"name":"dns-records"')
  })

  test('publishes a new provider on its own: its draft stays, read-only, and the action picked goes on the canvas', async ({ page }) => {
    await mockDesignerApi(page)
    const calls = await mockProviderApi(page)
    await page.goto('/#/workflows/vm-restart')
    await expect(node(page, 'step:checkVmExists')).toBeVisible()

    const insert = { kind: 'insert', point: { kind: 'after', step: 'approval', handle: 'main' } }
    await openSheet(page, { entry: 'describe', target: insert })
    const sheet = page.getByTestId('provider-sheet')
    const devin = page.getByRole('dialog', { name: 'Ask Devin to draft the provider' })
    await devin.getByRole('textbox', { name: 'Description' }).fill('The DNS API: list the records of a zone and upsert one.')
    await devin.getByRole('button', { name: 'Ask Devin' }).click()
    await devin.getByRole('button', { name: 'Use this draft' }).click()
    await expect(devin).toHaveCount(0)

    await sheet.getByRole('button', { name: 'Action to use' }).click()
    await page.getByRole('option', { name: /records\.list/ }).click()
    await sheet.getByRole('button', { name: 'Run checks' }).click()
    await expect(sheet.getByText('Ready to publish.')).toBeVisible()
    await expect(sheet.getByText(/The draft stays, read-only/)).toBeVisible()
    await sheet.getByRole('button', { name: 'Publish provider only' }).click()
    await expect(page.getByText('Opened change request cr-201')).toBeVisible()
    expect(String((calls.publishes[0] as { yaml: string }).yaml)).toContain('name: dns-records')

    // the sheet closes and the action is a step; the draft stays with its change request, so the step still resolves
    await expect(sheet).toHaveCount(0)
    await expect(page.getByTestId('ndv').getByRole('textbox', { name: 'Step name' })).toHaveValue('List records')
    const stored = String(await page.evaluate(`localStorage.getItem('iaf-designer-provider-drafts:vm-restart')`))
    expect(stored).toContain('"name":"dns-records"')
    expect(stored).toContain('"changeRequest":"cr-201"')

    // reopened on that provider (its step's Edit provider): read-only until the change request merges
    await page.getByTestId('ndv').getByRole('button', { name: 'Back to canvas' }).click()
    await openSheet(page, { entry: 'step', provider: 'dns-records', target: null })
    await expect(sheet.getByTestId('provider-sheet-published')).toContainText('Published on its own as change request cr-201')
    await expect(sheet.getByRole('textbox', { name: 'Title', exact: true })).toBeDisabled()
    await sheet.getByRole('navigation', { name: 'Provider sections' }).getByRole('button', { name: /Review/ }).click()
    await expect(sheet.getByRole('button', { name: 'Publish provider only' })).toBeDisabled()
  })
})
