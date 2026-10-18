import { readFileSync } from 'node:fs'
import { expect, test, type Locator, type Page, type Route } from '@playwright/test'
import { parse } from 'yaml'
import { mockDesignerApi } from './designer-fixtures'

/*
 * A provider step's node detail view against a mocked backend: the health dot on the canvas, the
 * Provider tab (the provider page's health, SLO, alerts, release and change requests, Edit SLO),
 * the Settings tab's shared action, whose first edit starts a draft of the provider's next patch,
 * and Try for real, whose answer the Output pane shows with Save as sample, Fix schema and Use
 * the real response. The designer's own mocks answer the rest (designer-fixtures.ts); this file
 * adds the provider endpoints in front of them.
 */

const VSPHERE_YAML = readFileSync(new URL('../../providers/team-compute/vsphere.yaml', import.meta.url), 'utf8')
const DRAFTS_KEY = 'iaf-designer-provider-drafts:vm-restart'

type Obj = Record<string, unknown>

function vsphereDetail(): Obj {
  const doc = parse(VSPHERE_YAML) as { metadata: Obj; spec: { endpoints: Record<string, string>; auth: Obj; actions: Obj[]; slo: Obj } }
  const actions = doc.spec.actions.map((a) => ({
    id: a.id,
    title: a.title,
    operation: a.operation,
    effect: a.effect,
    retry: a.retry,
    ...(a.approval ? { approval: a.approval } : {}),
    ...(a.plan ? { plan: a.plan } : {}),
    input: a.input ?? {},
    output: a.output ?? {},
    sample: a.sample,
    overridden: false,
    slo: a.slo ?? null,
  }))
  return {
    name: 'vsphere',
    title: 'VMware vSphere',
    type: 'openapi',
    owner: 'team-compute',
    version: '1.3.0',
    environments: Object.keys(doc.spec.endpoints),
    endpoints: doc.spec.endpoints,
    auth: doc.spec.auth,
    actionCount: actions.length,
    health: { status: 'degraded', successRate: 97.2, p50Ms: 220, p95Ms: 1350, calls: 1240, errorTrend: [2, 3, 9], reasons: ['p95 1.35 s is above the SLO of 800 ms'] },
    slo: { successRate: 99.5, p95Ms: 800, channel: '#compute-alerts' },
    alerts: { channel: '#compute-alerts' },
    yaml: VSPHERE_YAML,
    actions,
    daily: [],
    perAction: [{ action: 'vm.get', calls: 812, successRate: 96.1, p50Ms: 210, p95Ms: 1500, topErrors: [], slo: { successRate: 99.5, p95Ms: 800 }, band: 'failing' }],
    changeRequests: [
      { id: 'cr_41', kind: 'provider', name: 'vsphere', version: '1.3.1', branch: 'provider/vsphere-1.3.1', title: 'vsphere 1.3.1: the SLO of VMware vSphere', author: 'alice', status: 'open', checks: [], strategy: 'canary', createdAt: '2026-10-18T08:00:00Z' },
      { id: 'cr_30', kind: 'provider', name: 'vsphere', version: '1.3.0', branch: 'provider/vsphere-1.3.0', title: 'vsphere 1.3.0', author: 'alice', status: 'merged', checks: [], strategy: 'canary', createdAt: '2026-10-01T08:00:00Z' },
    ],
  }
}

const RELEASE = {
  id: 'rel_p7',
  provider: 'vsphere',
  from: '1.2.0',
  to: '1.3.0',
  strategy: 'canary',
  status: 'canary',
  stageIndex: 1,
  stages: [10, 50, 100],
  stagePercent: 50,
  startedAt: '2026-10-18T06:00:00Z',
  stageStartedAt: '2026-10-18T08:00:00Z',
  gates: [
    { id: 'success', label: 'Success rate at least 99.5%', passed: true, detail: '99.7% over 412 calls' },
    { id: 'p95', label: 'p95 at most 800 ms', passed: null, detail: 'judged at the end of the stage' },
  ],
  jobs: { current: 0, candidate: 0 },
  history: [],
}

const ANSWER = { id: 'vm-1042', name: 'web-1042', power_state: 'POWERED_ON', cpu: 4 }

interface ProviderCalls {
  tries: Obj[]
  overrides: Obj[]
  slos: Obj[]
}

function json(route: Route, body: unknown, status = 200): Promise<void> {
  return route.fulfill({ status, contentType: 'application/json', body: JSON.stringify(body) })
}

/** The provider endpoints the node detail view calls, in front of the designer's mocks. */
async function mockProviders(page: Page): Promise<ProviderCalls> {
  const calls: ProviderCalls = { tries: [], overrides: [], slos: [] }
  const detail = vsphereDetail()
  await page.route('**/api/v1/**', async (route) => {
    const req = route.request()
    const url = new URL(req.url())
    const path = url.pathname.replace(/^\/api\/v1/, '')
    const method = req.method()
    if (method === 'GET' && path === '/providers')
      return json(route, [
        { ...detail, yaml: undefined, actions: undefined },
        { name: 'backup', title: 'Backup', type: 'openapi', owner: 'team-platform', version: '2.0.0', environments: ['dev'], actionCount: 3, health: { status: 'healthy', successRate: 99.9, p50Ms: 90, p95Ms: 300, calls: 400, errorTrend: [], reasons: [] } },
      ])
    if (method === 'GET' && path === '/providers/vsphere') return json(route, detail)
    if (method === 'GET' && path === '/providers/vsphere/alerts')
      return json(route, [
        { id: 'al_2', at: '2026-10-18T07:00:00Z', provider: 'vsphere', action: 'vm.create', message: 'Three timeouts in ten minutes', severity: 'critical', jobId: 'job_51c2aa90' },
        { id: 'al_1', at: '2026-10-18T06:00:00Z', provider: 'vsphere', action: 'vm.get', message: 'vm.get p95 1.5 s is above its SLO of 800 ms', severity: 'warning' },
      ])
    if (method === 'GET' && path === '/releases' && url.searchParams.get('provider') === 'vsphere') return json(route, [RELEASE])
    if (method === 'PUT' && path === '/providers/vsphere/slo') {
      calls.slos.push(req.postDataJSON() as Obj)
      return json(route, { id: 'cr_43', status: 'open' }, 202)
    }
    if (method === 'POST' && path === '/providers:try') {
      const body = req.postDataJSON() as Obj
      calls.tries.push(body)
      return json(route, {
        ok: true,
        status: 200,
        durationMs: 412,
        response: ANSWER,
        mismatches: [
          { field: 'powerState', expected: 'string' },
          { field: 'power_state', actual: 'POWERED_ON' },
        ],
        environment: String(body.environment),
        at: '2026-10-18T10:00:00Z',
        // the file with the answer as the sample, as the backend writes it
        yaml: `${String(body.yaml)}\n# vm.get's sample recorded in ${String(body.environment)}\n`,
      })
    }
    if (method === 'POST' && path === '/providers/vsphere/overrides') {
      calls.overrides.push(req.postDataJSON() as Obj)
      return json(route, { id: 'cr_42', status: 'open' }, 202)
    }
    return route.fallback()
  })
  return calls
}

function node(page: Page, id: string): Locator {
  return page.locator(`.react-flow__node[data-id="${id}"]`)
}

async function openVmRestart(page: Page): Promise<ProviderCalls> {
  await mockDesignerApi(page)
  const calls = await mockProviders(page)
  await page.goto('/#/workflows/vm-restart')
  await expect(node(page, 'step:checkVmExists')).toBeVisible()
  return calls
}

/** The workflow's provider drafts as this browser keeps them. */
async function storedDrafts(page: Page): Promise<Obj[]> {
  return (await page.evaluate(`JSON.parse(localStorage.getItem(${JSON.stringify(DRAFTS_KEY)}) ?? '[]')`)) as Obj[]
}

async function pick(scope: Locator, page: Page, select: string, option: RegExp): Promise<void> {
  await scope.getByRole('button', { name: select }).click()
  await page.getByRole('listbox', { name: select }).getByRole('option', { name: option }).click()
}

test.describe('a provider step in the node detail view', () => {
  test('shows its provider’s health on the canvas and in the Provider tab, and edits the SLO through a change request', async ({ page }) => {
    const calls = await openVmRestart(page)
    // the server's band, as a dot on each provider step
    await expect(node(page, 'step:checkVmExists').locator('[data-health="degraded"]')).toHaveAttribute('aria-label', 'vsphere is degraded')
    await expect(node(page, 'step:noBackupRunning').locator('[data-health="healthy"]')).toBeAttached()
    await expect(node(page, 'step:approval').locator('[data-health]')).toHaveCount(0)

    await node(page, 'step:checkVmExists').dblclick()
    const ndv = page.getByTestId('ndv')
    await expect(ndv.getByTestId('ndv-try-for-real')).toBeVisible()
    await ndv.getByRole('tab', { name: 'Provider' }).click()
    const tab = ndv.getByTestId('ndv-provider-tab')
    await expect(tab).toContainText('VMware vSphere · 1.3.0')
    await expect(tab).toContainText('Degraded')
    await expect(tab).toContainText('p95 1.35 s is above the SLO of 800 ms')
    await expect(tab).toContainText('97.2%')
    await expect(tab).toContainText('SLO 99.5% · missed')
    // the step's action's own band, and its alert first
    await expect(tab).toContainText('This step’s action · vm.get')
    await expect(tab).toContainText('Failing')
    const alerts = tab.getByRole('list', { name: 'Alerts' }).getByRole('listitem')
    await expect(alerts.first()).toContainText('vm.get p95 1.5 s is above its SLO')
    await expect(alerts.first()).toContainText('this step’s action')
    await expect(alerts.nth(1)).toContainText('Three timeouts in ten minutes')
    await expect(tab.getByTestId('provider-release')).toContainText('Release in flight')
    await expect(tab.getByTestId('provider-release')).toContainText('1.3.0')
    await expect(tab.getByTestId('ndv-provider-changes').getByRole('link', { name: 'cr_41' })).toHaveAttribute('href', '#/change-requests/cr_41')
    await expect(tab.getByTestId('ndv-provider-changes')).not.toContainText('cr_30')
    await expect(tab.getByRole('link', { name: 'Provider page' })).toHaveAttribute('href', '#/providers/vsphere')
    await expect(tab.getByRole('link', { name: 'Provider audit' })).toHaveAttribute('href', '#/audit?tab=platform&aggregate=provider')
    await expect(tab.getByRole('button', { name: 'Refresh spec' })).toBeVisible()

    await tab.getByRole('button', { name: 'Edit SLO' }).click()
    const slo = page.getByRole('dialog', { name: 'Edit SLO' })
    await slo.getByLabel('p95 latency (ms)').fill('1200')
    await slo.getByRole('button', { name: 'Save SLO' }).click()
    await expect(page.getByText('Opened change request cr_43')).toBeVisible()
    expect(calls.slos).toEqual([{ successRate: 99.5, p95Ms: 1200, channel: '#compute-alerts' }])
    // the SLO is the provider's, not the workflow's: the canvas is not touched
    await expect(page.getByTestId('ndv')).toBeVisible()
  })

  test('edits the shared action into a draft, tries it for real and keeps the answer in the draft', async ({ page }) => {
    const calls = await openVmRestart(page)
    await node(page, 'step:checkVmExists').dblclick()
    const ndv = page.getByTestId('ndv')
    await ndv.getByRole('tab', { name: 'Settings' }).click()
    const note = ndv.getByTestId('shared-action-note')
    await expect(note).toContainText('vm.get of VMware vSphere')
    await expect(note).toContainText('for every workflow that calls it')
    await expect(note).toContainText('published 1.3.0')
    // the step's own retry stays where it was
    await expect(ndv.getByText('Pause on failure')).toBeVisible()

    // the first edit starts a draft of the next patch, written into its file by lines
    await pick(ndv, page, 'Retry policy of the action', /^unsafe/)
    await expect(page.getByText('Started a draft of vsphere 1.3.1')).toBeVisible()
    await expect(note).toContainText('draft 1.3.1')
    let drafts = await storedDrafts(page)
    expect(drafts).toHaveLength(1)
    expect(drafts[0]).toMatchObject({ name: 'vsphere', origin: 'edit', publishedVersion: '1.3.0', version: '1.3.1' })
    const file = String(drafts[0].draftYaml)
    expect(file).toContain('  version: 1.3.1')
    expect(file).toContain('    - id: vm.get\n      title: Get VM\n      operation: getVm\n      effect: read\n      retry: unsafe\n')
    // the published file's error classes are kept as written
    await expect(ndv.getByTestId('shared-error-classes')).toContainText('keeps its error classes as written')

    // Try for real: the draft's file, in dev, the input resolved from the step's
    await ndv.getByTestId('ndv-try-for-real').click()
    const dialog = page.getByRole('dialog', { name: 'Try vm.get for real' })
    await expect(dialog).toContainText('draft 1.3.1')
    await expect(dialog.getByRole('button', { name: 'Environment' })).toHaveText('dev')
    await dialog.getByLabel(/^id/).fill('vm-1042')
    await dialog.getByRole('button', { name: 'Try it' }).click()
    await expect(dialog).toBeHidden()
    expect(calls.tries).toHaveLength(1)
    expect(calls.tries[0]).toMatchObject({ action: 'vm.get', environment: 'dev', input: { id: 'vm-1042' } })
    expect(String(calls.tries[0].yaml)).toContain('retry: unsafe')

    const out = ndv.getByTestId('ndv-tried')
    await expect(out).toContainText('A real call of vm.get in dev, not a dry run')
    await expect(out.getByTestId('try-outcome')).toContainText('HTTP 200')
    await expect(out.getByTestId('try-outcome')).toContainText('2 fields differ')
    await expect(out.getByTestId('try-outcome')).toContainText('POWERED_ON')

    // Save as sample: the draft takes the file the call came back with
    await out.getByRole('button', { name: 'Save as sample' }).click()
    await expect(out.getByRole('button', { name: 'Sample saved' })).toBeDisabled()
    drafts = await storedDrafts(page)
    expect(String(drafts[0].draftYaml)).toContain("# vm.get's sample recorded in dev")
    expect((drafts[0].samples as Obj)['vm.get']).toEqual(ANSWER)

    // Fix schema: the answer's shape into the draft's overrides
    await out.getByRole('button', { name: 'Fix schema' }).click()
    await expect(out.getByRole('button', { name: 'Schema fixed' })).toBeDisabled()
    drafts = await storedDrafts(page)
    const fixed = parse(String(drafts[0].draftYaml)) as { spec: { overrides: Obj; actions: Obj[] } }
    expect(fixed.spec.overrides['vm.get']).toEqual({ response: { type: 'object', properties: { id: { type: 'string' }, name: { type: 'string' }, power_state: { type: 'string' }, cpu: { type: 'integer' } } } })
    expect(fixed.spec.actions.find((a) => a.id === 'vm.get')?.retry).toBe('unsafe')

    // Use the real response: the published provider's override, through a change request
    await out.getByRole('button', { name: 'Use the real response' }).click()
    await expect(page.getByText('Opened change request cr_42')).toBeVisible()
    expect(calls.overrides).toEqual([{ action: 'vm.get', response: ANSWER }])

    // back to the test output; pinned data was never touched
    await out.getByRole('button', { name: 'Back to the test output' }).click()
    await expect(ndv.getByTestId('ndv-tried')).toBeHidden()
    await expect(ndv.getByRole('button', { name: 'Pin output' })).toBeDisabled()
  })

  test('asks before a real call that changes something', async ({ page }) => {
    const calls = await openVmRestart(page)
    await node(page, 'step:checkVmExists').dblclick()
    const ndv = page.getByTestId('ndv')
    await ndv.getByRole('tab', { name: 'Settings' }).click()
    // a delete asks for an approval and says what it removes, as the wizard's Actions step
    await pick(ndv, page, 'Effect of the action', /^delete$/)
    await expect(ndv.getByRole('switch', { name: 'Needs an approval' })).toHaveAttribute('aria-checked', 'true')
    await ndv.getByLabel('What it removes').fill('The VM and its disks')
    const file = String((await storedDrafts(page))[0].draftYaml)
    expect(file).toContain('      effect: delete\n      retry: safe\n      approval: required\n      effectDetail: The VM and its disks\n')

    await ndv.getByTestId('ndv-try-for-real').click()
    const dialog = page.getByRole('dialog', { name: 'Try vm.get for real' })
    await expect(dialog).toContainText('changes something in dev')
    await dialog.getByLabel(/^id/).fill('vm-test-7')
    await dialog.getByRole('button', { name: 'Try it' }).click()
    const confirm = page.getByRole('dialog', { name: 'Run vm.get in dev?' })
    await expect(confirm).toContainText('This is a real delete call, not a dry run.')
    expect(calls.tries).toHaveLength(0)
    await confirm.getByRole('button', { name: 'Run it' }).click()
    await expect(ndv.getByTestId('ndv-tried')).toBeVisible()
    expect(calls.tries).toHaveLength(1)
    expect(calls.tries[0]).toMatchObject({ action: 'vm.get', environment: 'dev', input: { id: 'vm-test-7' } })
  })
})
