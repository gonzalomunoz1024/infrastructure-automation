import { expect, test, type Locator, type Page } from '@playwright/test'
import { mockDesignerApi, type MockApi } from './designer-fixtures'

/*
 * Authoring providers from the designer, against the mocked backend of the designer spec plus
 * this spec's own routes: the node creator's "Connect a new system", each provider's health band
 * and the workflow's drafts; the provider field's New provider… and Edit provider; and an HTTP
 * step that imports a cURL command (without what signed in) and is saved as a provider action.
 * The Provider sheet itself is another package's: these tests stop where it opens.
 */

const DRAFTS_KEY = 'iaf-designer-provider-drafts:vm-restart'

/** GET /providers: the providers page's list, with each provider's health band. */
function summary(name: string, title: string, type: string, status: string, calls = 120) {
  return { name, title, type, owner: 'team-platform', version: '1.0.0', environments: ['dev'], actionCount: 1, health: { status, successRate: 99.1, p50Ms: 80, p95Ms: 420, calls, errorTrend: [], reasons: [] } }
}
const SUMMARIES = [summary('vsphere', 'VMware vSphere', 'openapi', 'degraded'), summary('backup', 'Backup service', 'http', 'healthy'), summary('vault', 'HashiCorp Vault', 'http', 'unknown', 0)]

/** A provider draft of this workflow's, as the designer keeps it in this browser. */
const STORAGE_DRAFT = {
  id: 'draft_e2e_storage',
  origin: 'new',
  publishedVersion: null,
  type: 'http',
  title: 'Storage API',
  name: 'storage-api',
  nameTouched: true,
  owner: 'team-storage',
  ownerTouched: true,
  version: '1.0.0',
  environments: [{ key: 1, name: 'dev', url: 'https://storage.example.com' }],
  auth: { type: 'none', credential: '', header: '' },
  actions: [
    {
      key: 2,
      selected: true,
      id: 'volumes.list',
      title: 'List volumes',
      operation: '',
      method: 'GET',
      path: '/volumes',
      playbook: '',
      effect: 'read',
      retry: 'safe',
      approval: false,
      plan: '',
      effectDetail: '',
      description: '',
      inputText: '',
      outputText: '',
      exampleText: '{"items":[]}',
      sloSuccessRate: '',
      sloP95Ms: '',
    },
  ],
  errors: [],
}

function node(page: Page, id: string): Locator {
  return page.locator(`.react-flow__node[data-id="${id}"]`)
}

async function open(page: Page, withDraft = false): Promise<MockApi> {
  const api = await mockDesignerApi(page)
  // routes registered later win: GET /providers is this spec's
  await page.route('**/api/v1/providers', (route) => (route.request().method() === 'GET' ? route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(SUMMARIES) }) : route.fallback()))
  // as a string: this project's e2e types carry no DOM
  if (withDraft) await page.addInitScript(`localStorage.setItem(${JSON.stringify(DRAFTS_KEY)}, ${JSON.stringify(JSON.stringify([STORAGE_DRAFT]))})`)
  await page.goto('/#/workflows/vm-restart')
  await expect(node(page, 'step:checkVmExists')).toBeVisible()
  return api
}

async function storedDrafts(page: Page): Promise<string> {
  return String((await page.evaluate(`localStorage.getItem(${JSON.stringify(DRAFTS_KEY)})`)) ?? '')
}

test.describe('authoring providers in the designer', () => {
  test("the node creator connects a new system, and lists each provider's health and the workflow's drafts", async ({ page }) => {
    await open(page, true)
    await node(page, 'step:notifyDone').click()
    await page.keyboard.press('Tab')
    const creator = page.getByTestId('node-creator')
    await creator.getByRole('option', { name: /^Providers/ }).click()
    await expect(creator.getByRole('heading', { name: 'Providers' })).toBeVisible()

    // the published providers with their health band, the workflow's draft tagged
    await expect(creator.getByRole('option', { name: /VMware vSphere/ })).toContainText('Degraded')
    await expect(creator.getByRole('option', { name: /Backup service/ })).toContainText('Healthy')
    const draft = creator.getByRole('option', { name: /Storage API/ })
    await expect(draft).toContainText('draft')
    await expect(draft).not.toContainText('Healthy')

    // a draft's actions are steps like a published provider's
    await draft.click()
    await expect(creator.getByText('storage-api · draft, not published yet')).toBeVisible()
    await expect(creator.getByRole('option', { name: /List volumes/ })).toContainText('draft')
    await creator.getByRole('button', { name: 'Back' }).click()

    // seven ways to connect a new system
    const connect = creator.getByRole('group', { name: 'Connect a new system' }).getByRole('option')
    await expect(connect).toHaveText([/API from an OpenAPI spec/, /HTTP request \(cURL\)/, /Ansible repository/, /Terraform module/, /IDP tile/, /SDK worker/, /Describe it \(Ask Devin\)/])
    // search finds them from the top as well
    await creator.getByRole('combobox', { name: 'Search steps' }).fill('curl')
    await expect(creator.getByRole('option').first()).toContainText('HTTP request (cURL)')
    // choosing one hands over to the Provider sheet: the creator closes
    await page.keyboard.press('Enter')
    await expect(creator).toBeHidden()
  })

  test('the provider field lists the drafts, ends with New provider… and has an Edit provider pencil', async ({ page }) => {
    await open(page, true)
    await node(page, 'step:checkVmExists').dblclick()
    const ndv = page.getByTestId('ndv')
    const field = ndv.getByRole('button', { name: 'Provider', exact: true })
    await expect(field).toHaveText('VMware vSphere')
    await expect(ndv.getByRole('button', { name: 'Edit provider' })).toBeVisible()
    await field.click()
    const options = ndv.getByRole('listbox', { name: 'Provider' }).getByRole('option')
    await expect(options.filter({ hasText: 'Storage API' })).toContainText('storage-api · draft')
    await expect(options.last()).toHaveText('New provider…')
    // choosing it opens the sheet for this step and writes nothing into the step
    await options.last().click()
    await expect(field).toHaveText('VMware vSphere')
    await expect(ndv.getByRole('button', { name: 'Action', exact: true })).toHaveText('Get VM')
  })

  test('an HTTP step imports a cURL command without what signed in, and is saved as a provider action', async ({ page }) => {
    const api = await open(page)
    await node(page, 'step:notifyDone').click()
    await page.keyboard.press('Tab')
    const creator = page.getByTestId('node-creator')
    await page.keyboard.type('HTTP request')
    await expect(creator.getByRole('option').first()).toContainText('HTTP request')
    await page.keyboard.press('Enter')
    const ndv = page.getByTestId('ndv')
    await expect(ndv.getByRole('textbox', { name: 'Step name' })).toHaveValue('HTTP request')

    // Import cURL, as n8n's HTTP Request node has it; the secrets are obviously fake
    await ndv.getByRole('button', { name: 'Import cURL' }).click()
    const dialog = page.getByRole('dialog', { name: 'Import cURL command' })
    await dialog.getByRole('textbox', { name: 'cURL command' }).fill(
      [
        "curl -X POST 'https://api.example.com/things?page=2&api_key=example-key' \\",
        "  -H 'Authorization: Bearer example-token' \\",
        "  -H 'Content-Type: application/json' \\",
        "  -H 'X-Trace: e2e' \\",
        '  -u ops:example-password \\',
        `  --data-raw '{"name":"web-01","size":2}' --compressed`,
      ].join('\n'),
    )
    await dialog.getByRole('button', { name: 'Import' }).click()
    await expect(dialog).toBeHidden()
    await expect(ndv.getByRole('button', { name: 'Method', exact: true })).toHaveText('POST')
    await expect(ndv.getByRole('textbox', { name: /^URL/ })).toHaveValue('https://api.example.com/things?page=2')
    await expect(ndv.getByRole('textbox', { name: 'Name of X-Trace' })).toBeVisible()
    await expect(ndv.getByRole('textbox', { name: 'Name of Content-Type' })).toBeVisible()
    await expect(ndv.getByRole('textbox', { name: 'Name of Authorization' })).toHaveCount(0)
    await expect(ndv.getByRole('textbox', { name: 'Body', exact: true })).toHaveValue(/"name": "web-01"/)
    // the credential field asks for a credential, naming what was dropped and never its value
    const credential = ndv.getByTestId('http-credential')
    await expect(credential).toHaveAttribute('data-attention', 'true')
    await expect(ndv.getByTestId('curl-credential-note')).toContainText('Authorization header')
    await expect(ndv.getByTestId('curl-credential-note')).toContainText('the user and password (-u)')
    for (const secret of ['example-token', 'example-password', 'example-key']) expect(await page.content()).not.toContain(secret)

    // picking a credential answers it
    await ndv.getByRole('button', { name: 'Credential', exact: true }).click()
    await ndv.getByRole('listbox', { name: 'Credential' }).getByRole('option').filter({ hasText: 'vsphere/token' }).click()
    await expect(credential).not.toHaveAttribute('data-attention', 'true')
    await expect(ndv.getByTestId('curl-credential-note')).toHaveCount(0)

    // the saved workflow has the request and the credential's reference, never a secret
    await page.keyboard.press('ControlOrMeta+s')
    await expect.poll(() => api.drafts.length).toBe(1)
    const saved = JSON.stringify(api.drafts[0].definition)
    expect(saved).toContain('"url":"https://api.example.com/things?page=2"')
    expect(saved).toContain('"credential":"vault:vsphere/token"')
    for (const secret of ['example-token', 'example-password', 'example-key', 'Authorization']) expect(saved).not.toContain(secret)

    // a test gives the step an output, the action's example
    await ndv.getByRole('button', { name: 'Test step' }).first().click()
    await expect.poll(() => api.dryRuns.length).toBe(1)

    // Save as provider action says first what does not come along: X-Trace is no part of an action
    await ndv.getByRole('button', { name: 'Save as provider action' }).click()
    const confirm = page.getByRole('dialog', { name: 'Save as provider action?' })
    await expect(confirm.getByTestId('provider-action-notes')).toHaveText('The header X-Trace is not part of a provider action and was left out.')
    await confirm.getByRole('button', { name: 'Save as provider action' }).click()
    await expect(confirm).toBeHidden()
    // an http provider draft, and the step calls its action
    await expect(ndv.getByRole('button', { name: 'Provider', exact: true })).toHaveText('api.example.com')
    await expect(ndv.getByRole('button', { name: 'Action', exact: true })).toHaveText('POST /things?page=2')
    await expect(ndv.getByTestId('provider-tags')).toContainText('draft')
    await expect(ndv.getByTestId('provider-tags')).toContainText('create')
    const stored = await storedDrafts(page)
    const [draft] = JSON.parse(stored) as { name: string; type: string; owner: string; auth: { type: string; credential: string }; environments: { url: string }[]; actions: { id: string; method: string; path: string; exampleText: string }[] }[]
    expect(draft).toMatchObject({ name: 'example', type: 'http', owner: 'team-compute', auth: { type: 'bearer', credential: 'vault:vsphere/token' } })
    expect(draft.environments.map((e) => e.url)).toEqual(['https://api.example.com'])
    expect(draft.actions.map((a) => [a.id, a.method, a.path])).toEqual([['things.create', 'POST', '/things?page=2']])
    expect(JSON.parse(draft.actions[0].exampleText)).toMatchObject({ ok: true })
    for (const secret of ['example-token', 'example-password', 'example-key']) expect(stored).not.toContain(secret)
  })
})
