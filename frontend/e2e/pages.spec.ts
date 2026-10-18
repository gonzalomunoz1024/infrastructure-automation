import { expect, test } from '@playwright/test'
import {
  APPROVALS,
  BASE_IMAGE,
  CATALOG,
  CONNECTION,
  CREDENTIALS,
  DNS_RECORD,
  EVIDENCE,
  IMAGE,
  IMAGE_LINEAGE,
  JOB,
  JOBS,
  PREVIEW,
  PROVIDER,
  PROVIDER_ALERTS,
  PROVIDER_CR,
  PROVIDERS,
  RELEASES,
  RESOURCE_TYPES,
  SPEC,
  TRY_RESULT,
  VALIDATION,
  VERIFIED,
  VMS,
  WORKFLOWS,
  mockApi,
  reply,
  sse,
} from './fixtures'

/*
 * The pages against a mocked /api/v1 (see fixtures.ts): each test walks one path a person takes
 * and checks what they see and what the page sends.
 */

test.describe('workflows', () => {
  test('the list shows every workflow and a row opens it in the designer', async ({ page }) => {
    await mockApi(page, { 'GET /workflows': WORKFLOWS })
    await page.goto('/#/workflows')
    const table = page.getByRole('table', { name: 'Workflows' })
    for (const w of WORKFLOWS) await expect(table).toContainText(w.title)
    // a workflow never published shows "not yet", and its draft version under Draft
    const draftOnly = table.getByRole('row', { name: /Patch a CVE/ })
    await expect(draftOnly).toContainText('not yet')
    await expect(draftOnly.getByTitle('A draft newer than the published version')).toHaveText('0.3.0')
    await table.getByRole('row', { name: /VM restart/ }).click()
    await expect(page).toHaveURL(/#\/workflows\/vm-restart$/)
  })
})

test.describe('catalog', () => {
  const routes = {
    'GET /catalog': CATALOG,
    'GET /resource-types': RESOURCE_TYPES,
    'GET /vms': VMS,
    'POST /vms/{vmId}:restart:preview': PREVIEW,
  }

  test('the run form renders the input fields and the preview card', async ({ page }) => {
    const api = await mockApi(page, routes)
    await page.goto('/#/catalog/vm-restart')
    await expect(page.getByRole('heading', { level: 1, name: 'VM restart' })).toBeVisible()
    // one control per input property: a registry picker, a text field, a choice with its default
    const vm = page.getByRole('button', { name: 'VM id' })
    await expect(vm).toBeVisible()
    await expect(page.getByLabel('Reason')).toBeVisible()
    await expect(page.getByRole('button', { name: 'Environment' })).toHaveText(/dev/)

    await vm.click()
    await page.getByRole('option', { name: /web-1042/ }).click()
    await page.getByLabel('Reason').fill('kernel patch')
    await page.getByRole('button', { name: 'Preview', exact: true }).click()

    await expect(page.getByText('The run will pause until someone approves it.')).toBeVisible()
    for (const s of PREVIEW.steps) await expect(page.getByText(s.name, { exact: true })).toBeVisible()
    await expect(page.getByText('Production changes need compute-approvers')).toBeVisible()
    const [call] = api.callsTo('POST', '/vms/vm-1042:restart:preview')
    expect(call.body).toEqual({ vmId: 'vm-1042', reason: 'kernel patch', environment: 'dev' })
  })

  test('many rows go to the batch path the catalog names, with per-row problems first', async ({ page }) => {
    let attempts = 0
    const api = await mockApi(page, {
      ...routes,
      'POST /vms:restart:batch': () =>
        ++attempts === 1
          ? reply(400, { error: 'Some rows are invalid', rows: [{ index: 1, problems: [{ level: 'error', code: 'not_found', field: 'vmId', message: 'vm-9999 is not a registered vm' }] }] })
          : { jobId: 'job-5b10e7d2', rows: [] },
      'GET /jobs/{id}': { ...JOB, id: 'job-5b10e7d2', attention: undefined, steps: [] },
      'GET /jobs/{id}/events': sse([]),
      'GET /jobs': JOBS,
    })
    await page.goto('/#/catalog/vm-restart')
    await page.getByRole('radio', { name: 'Many' }).click()
    await page.getByRole('textbox', { name: 'Rows as CSV or JSON' }).fill('vmId,reason\nvm-1042,kernel patch\nvm-9999,kernel patch')
    await page.getByRole('button', { name: 'Load rows' }).click()
    await expect(page.getByRole('table', { name: 'Batch rows' }).getByRole('row')).toHaveCount(3)

    await page.getByRole('button', { name: 'Start batch of 2' }).click()
    await expect(page.getByText('Row 2: vm-9999 is not a registered vm')).toBeVisible()

    await page.getByRole('textbox', { name: 'Row 2 vmId' }).fill('vm-1043')
    await page.getByRole('button', { name: 'Start batch of 2' }).click()
    await expect(page).toHaveURL(/#\/jobs\/job-5b10e7d2$/)

    const sent = api.callsTo('POST', '/vms:restart:batch')
    expect(sent).toHaveLength(2)
    expect((sent[1].body as { items: unknown[] }).items).toEqual([
      { vmId: 'vm-1042', reason: 'kernel patch' },
      { vmId: 'vm-1043', reason: 'kernel patch' },
    ])
    expect(api.calls.some((c) => c.path.endsWith(':restart:batch') && c.path !== '/vms:restart:batch')).toBe(false)
  })
})

test.describe('jobs', () => {
  test('a row opens the job: its step timeline and the attention panel', async ({ page }) => {
    const api = await mockApi(page, {
      'GET /jobs': JOBS,
      'GET /workflows': WORKFLOWS,
      'GET /jobs/{id}': JOB,
      'GET /jobs/{id}/events': sse([{ id: 1, event: 'attention', data: { seq: 1, at: JOB.attention!.since, jobId: JOB.id, type: 'attention', data: { step: 'restart', message: 'vCenter did not answer in 30s' } } }]),
      'POST /jobs/{id}:retry': { id: JOB.id, status: 'running' },
      'POST /jobs/{id}:abort': { id: JOB.id, status: 'rolled-back' },
    })
    await page.goto('/#/jobs')
    const table = page.getByRole('table', { name: 'Jobs' })
    await expect(table.getByRole('row', { name: /Patch CVE-2026-1234/ })).toContainText('6/20')
    // the row opens the job (its workflow cell is a link of its own, so click the title)
    await table.getByRole('row', { name: /Restart web-1042/ }).getByText('Restart web-1042', { exact: true }).click()
    await expect(page).toHaveURL(/#\/jobs\/job-7f3a2c91$/)

    const steps = page.getByRole('list', { name: 'Steps' })
    for (const s of JOB.steps) await expect(steps).toContainText(s.name)
    await expect(steps).toContainText('vCenter did not answer in 30s')

    const attention = page.getByRole('region', { name: 'Needs attention' })
    for (const name of ['Retry', 'Edit input', 'Skip', 'Abort']) await expect(attention.getByRole('button', { name, exact: true })).toBeVisible()

    await attention.getByRole('button', { name: 'Retry', exact: true }).click()
    await expect.poll(() => api.callsTo('POST', '/jobs/job-7f3a2c91:retry').length).toBe(1)
    expect(api.callsTo('POST', '/jobs/job-7f3a2c91:retry')[0].body).toEqual({ step: 'restart' })

    // destructive choices ask first
    await attention.getByRole('button', { name: 'Abort', exact: true }).click()
    const confirm = page.getByRole('dialog', { name: 'Abort and roll back?' })
    await expect(confirm).toBeVisible()
    await confirm.getByRole('button', { name: 'Cancel' }).click()
    await expect(confirm).toBeHidden()
    expect(api.callsTo('POST', '/jobs/job-7f3a2c91:abort')).toHaveLength(0)
  })

  test('cancelling says nothing is rolled back, points a paused run at Abort, then posts the cancel', async ({ page }) => {
    const api = await mockApi(page, {
      'GET /jobs/{id}': JOB,
      'GET /jobs/{id}/events': sse([]),
      'GET /workflows': WORKFLOWS,
      'POST /jobs/{id}:cancel': reply(202, { id: JOB.id, status: 'cancelled' }),
    })
    await page.goto('/#/jobs/job-7f3a2c91')
    await page.getByRole('button', { name: 'Cancel', exact: true }).click()
    const dialog = page.getByRole('dialog', { name: 'Cancel this job?' })
    await expect(dialog).toContainText('Restart web-1042 stops: no further step starts')
    await expect(dialog).toContainText('Nothing is rolled back: what the finished steps changed stays changed.')
    await expect(dialog).toContainText('To roll them back instead, choose Abort for restart under Needs attention.')
    await expect(dialog).not.toContainText('compensated')
    await dialog.getByRole('button', { name: 'Keep running' }).click()
    await expect(dialog).toBeHidden()
    expect(api.callsTo('POST', '/jobs/job-7f3a2c91:cancel')).toHaveLength(0)

    await page.getByRole('button', { name: 'Cancel', exact: true }).click()
    await page.getByRole('dialog', { name: 'Cancel this job?' }).getByRole('button', { name: 'Cancel job' }).click()
    await expect.poll(() => api.callsTo('POST', '/jobs/job-7f3a2c91:cancel').length).toBe(1)
  })
})

test.describe('approvals', () => {
  test('approving posts the decision with the comment and empties the queue, the preview in view', async ({ page }) => {
    let pending = [...APPROVALS]
    const api = await mockApi(page, {
      'GET /approvals': (req) => (req.query.get('status') === 'pending' ? pending : []),
      'POST /approvals/{id}:approve': (req) => {
        pending = []
        return { id: req.params.id, status: 'approved' }
      },
    })
    await page.goto('/#/approvals')
    await expect(page.getByText('Restart web-1042 in prod for a kernel patch.')).toBeVisible()
    // what the approver decides on: the run's steps, what it touches and what policy said
    const preview = page.getByTestId('preview-card')
    await expect(preview).toBeVisible()
    await expect(preview).toContainText('Needs approval')
    await expect(preview).toContainText('Restart the VM')
    await expect(preview.getByRole('link', { name: /web-1042/ })).toHaveAttribute('href', '#/resources/vm-1042')
    await expect(preview).toContainText('Production changes need compute-approvers')
    await page.getByRole('textbox', { name: 'Comment' }).fill('Window agreed')
    await page.getByRole('button', { name: 'Approve', exact: true }).click()
    await expect(page.getByText('Nothing waiting for you')).toBeVisible()
    expect(api.callsTo('POST', '/approvals/ap-1:approve')[0].body).toEqual({ comment: 'Window agreed' })
  })
})

test.describe('providers', () => {
  test('the list shows each health band, and a provider its charts and actions', async ({ page }) => {
    await mockApi(page, { 'GET /providers': PROVIDERS, 'GET /providers/{name}': PROVIDER, 'GET /providers/{name}/alerts': PROVIDER_ALERTS })
    await page.goto('/#/providers')
    const table = page.getByRole('table', { name: 'Providers' })
    await expect(table.getByRole('row', { name: /VMware vSphere/ })).toContainText('Healthy')
    await expect(table.getByRole('row', { name: /Backup service/ })).toContainText('Degraded')
    await table.getByRole('row', { name: /VMware vSphere/ }).click()
    await expect(page).toHaveURL(/#\/providers\/vsphere$/)

    for (const name of ['Calls per day', 'Success rate per day', 'Latency per day', 'Calls by action']) await expect(page.getByRole('img', { name })).toBeVisible()
    await expect(page.getByText('#compute-alerts')).toBeVisible()

    await page.getByRole('tab', { name: /Actions/ }).click()
    const actions = page.getByRole('table', { name: 'Actions' })
    await expect(actions.getByRole('row', { name: /vm\.restart/ })).toContainText('timeout ×31')
    await page.getByRole('tab', { name: /Alerts/ }).click()
    await expect(page.getByText('3 timeouts in 10 minutes')).toBeVisible()
  })

  test('the Add provider wizard walks its five steps to a change request', async ({ page }) => {
    const api = await mockApi(page, {
      'GET /credentials': CREDENTIALS,
      'POST /providers:import-spec': SPEC,
      'POST /providers:test-connection': CONNECTION,
      'POST /providers:try': TRY_RESULT,
      'POST /providers:validate': VALIDATION,
      'POST /providers:publish': { id: 'cr-101', status: 'open' },
      'GET /change-requests/{id}': PROVIDER_CR,
    })
    await page.goto('/#/providers/new')
    const current = page.getByRole('list', { name: 'Steps' }).locator('[aria-current="step"]')

    // 1 · Type
    await expect(current).toContainText('Type')
    await expect(page.getByRole('radio', { name: 'OpenAPI' })).toHaveAttribute('aria-checked', 'true')
    await page.getByRole('textbox', { name: 'Title', exact: true }).fill('Backup service')
    await expect(page.getByRole('textbox', { name: 'Name', exact: true })).toHaveValue('backup-service')
    await expect(page.getByRole('textbox', { name: 'Owner', exact: true })).toHaveValue('team-platform')
    await page.getByRole('button', { name: 'Next' }).click()

    // 2 · Connect
    await expect(current).toContainText('Connect')
    await page.getByRole('textbox', { name: 'Spec URL' }).fill('https://backup.example.com/openapi.json')
    await page.getByRole('button', { name: 'Import spec' }).click()
    await expect(page.getByText('2 operations from Backup service 1.0.0.')).toBeVisible()
    await expect(page.getByRole('textbox', { name: 'Environment URL' }).first()).toHaveValue('https://backup-dev.example.com/api')
    await page.getByRole('button', { name: 'Credential', exact: true }).click()
    await page.getByRole('option', { name: /backup\/api-key/ }).click()
    await page.getByRole('button', { name: 'Test connection' }).click()
    await expect(page.getByRole('list', { name: 'Connection results' })).toContainText('200 from GET /jobs')
    expect(api.callsTo('POST', '/providers:test-connection')[0].body).toEqual({
      endpoints: { dev: 'https://backup-dev.example.com/api', prod: 'https://backup.example.com/api' },
      auth: { type: 'header', credential: 'vault:backup/api-key', header: 'X-Api-Key' },
    })
    await page.getByRole('button', { name: 'Next' }).click()

    // 3 · Actions
    await expect(current).toContainText('Actions')
    const ops = page.getByRole('table', { name: 'Operations' })
    await expect(ops).toContainText('listJobs')
    await expect(ops).toContainText('createJob')
    await page.getByRole('button', { name: 'Next' }).click()

    // 4 · Try it
    await expect(current).toContainText('Try it')
    await page.getByRole('button', { name: 'Try it', exact: true }).click()
    await expect(page.getByText('HTTP 200 in 182ms.')).toBeVisible()
    await page.getByRole('button', { name: 'Record as sample' }).click()
    await expect(page.getByRole('button', { name: 'Sample recorded' })).toBeDisabled()
    await page.getByRole('button', { name: 'Next' }).click()

    // 5 · Review
    await expect(current).toContainText('Review')
    const yaml = page.getByText(/kind: Provider/).first()
    await expect(yaml).toBeVisible()
    for (const line of ['name: backup-service', 'credential: vault:backup/api-key', 'operation: listJobs', 'bk-9001']) await expect(page.locator('pre').first()).toContainText(line)
    await page.getByRole('button', { name: 'Run checks' }).click()
    await expect(page.getByText('Ready to publish.')).toBeVisible()
    await page.getByRole('button', { name: 'Publish as change request' }).click()

    await expect(page).toHaveURL(/#\/change-requests\/cr-101$/)
    await expect(page.getByRole('heading', { level: 1, name: 'Add provider backup-service' })).toBeVisible()
    const published = api.callsTo('POST', '/providers:publish')[0].body as { yaml: string; strategy: string }
    expect(published.strategy).toBe('canary')
    expect(published.yaml).toContain('kind: Provider')
    expect(published.yaml).toContain('header: X-Api-Key')
  })
})

test.describe('releases', () => {
  test('a release shows its stages, gates and traffic; promote asks first', async ({ page }) => {
    const api = await mockApi(page, {
      'GET /releases': RELEASES,
      'GET /workflows': WORKFLOWS,
      'GET /releases/{id}': (req) => RELEASES.find((r) => r.id === req.params.id) ?? reply(404, { error: 'No such release' }),
      'POST /releases/{id}:promote': { id: 'rel-1', status: 'promoted' },
    })
    await page.goto('/#/releases')
    await page.getByRole('table', { name: 'Releases' }).getByRole('row', { name: /vm-restart/ }).click()
    await expect(page).toHaveURL(/#\/releases\/rel-1$/)

    await expect(page.getByRole('list', { name: 'Steps' }).locator('[aria-current="step"]')).toContainText('25%')
    const gates = page.getByRole('table', { name: 'Gates' })
    await expect(gates.getByRole('row')).toHaveCount(5)
    // figures in the gate's own unit: percentages, durations, a list of error types, jobs
    const rate = gates.getByRole('row', { name: /Success rate/ })
    await expect(rate).toContainText('passed')
    await expect(rate).toContainText('99.2%')
    await expect(rate).toContainText('at least 99.0%')
    const p95 = gates.getByRole('row', { name: /p95 duration/ })
    await expect(p95).toContainText('2.2 s')
    await expect(p95).toContainText('at most 36 min 40 s')
    await expect(p95).not.toContainText('2200')
    const errors = gates.getByRole('row', { name: /Error types/ })
    await expect(errors).toContainText('2 new')
    await expect(errors).toContainText('none new')
    const newTypes = errors.getByRole('list', { name: 'Error types new on the candidate' })
    await expect(newTypes.getByRole('listitem')).toHaveText(['timeout', 'upstream'])
    const sample = gates.getByRole('row', { name: /Sample size/ })
    await expect(sample).toContainText('pending')
    await expect(sample).toContainText('12 jobs')
    await expect(sample).toContainText('at least 20 jobs')
    await expect(page.getByText('1.2.0 (candidate) · 60 jobs')).toBeVisible()

    await page.getByRole('button', { name: 'Promote', exact: true }).click()
    const dialog = page.getByRole('dialog', { name: 'Promote 1.2.0?' })
    await dialog.getByRole('button', { name: 'Promote' }).click()
    await expect.poll(() => api.callsTo('POST', '/releases/rel-1:promote').length).toBe(1)
  })
})

test.describe('audit', () => {
  test('an evidence record shows its timeline and a verified chain', async ({ page }) => {
    await mockApi(page, { 'GET /audit/{jobId}': EVIDENCE, 'GET /audit/{jobId}:verify': VERIFIED })
    await page.goto('/#/audit/job-7f3a2c91')
    const timeline = page.getByRole('list', { name: 'Evidence entries' })
    await expect(timeline).toContainText('Run requested by gonzalo via catalog for vm-restart 1.2.0')
    await expect(timeline).toContainText('Policy decided: needs-approval (opa)')
    await expect(timeline).toContainText('Approval approved by maria')
    await expect(page.getByText('Chain intact')).toBeVisible()
    await expect(page.getByText('All 5 entries verify against their hashes.')).toBeVisible()
    await expect(page.getByTitle(/^Signed with /)).toHaveText('Sealed')
    await expect(page.getByRole('link', { name: 'CHG-4521' })).toHaveAttribute('href', 'https://itsm.example.com/CHG-4521')
  })

  test('a broken chain says where it broke', async ({ page }) => {
    await mockApi(page, { 'GET /audit/{jobId}': EVIDENCE, 'GET /audit/{jobId}:verify': { valid: false, entries: 5, brokenAt: 4 } })
    await page.goto('/#/audit/job-7f3a2c91')
    await expect(page.getByText('Chain broken at entry 4')).toBeVisible()
    await expect(page.getByRole('list', { name: 'Evidence entries' }).getByText('chain broken')).toHaveCount(2)
  })
})

test.describe('resources', () => {
  test('a resource draws its lineage, and retiring one in use lists its dependents', async ({ page }) => {
    const all = [IMAGE, BASE_IMAGE, ...VMS, DNS_RECORD]
    await mockApi(page, {
      'GET /resources/{id}': (req) => all.find((r) => r.id === req.params.id) ?? reply(404, { error: 'No such resource' }),
      'GET /resources/{id}/lineage': (req) => (req.params.id === IMAGE.id ? IMAGE_LINEAGE : { resource: all.find((r) => r.id === req.params.id), builtFrom: [IMAGE], dependents: [] }),
      'POST /resources/{id}:retire': reply(409, { error: 'resource has dependents', detail: VMS }),
    })
    await page.goto('/#/resources/img-77')
    const graph = page.locator('svg').filter({ hasText: 'ubuntu-2404-cis1' })
    for (const name of ['ubuntu-2404', 'web-1042', 'web-1043']) await expect(graph).toContainText(name)
    // the backend answers the whole graph: the DNS record of web-1042 is two hops away, and says how it came
    await expect(graph).toHaveAttribute('aria-label', 'Lineage of ubuntu-2404-cis1: built from 1, 2 dependents; 1 upstream and 3 downstream in all')
    await expect(graph.getByRole('link', { name: /web-1042\.prod/ })).toHaveAttribute('href', '#/resources/dns-1042')
    await expect(graph.locator('path[data-edge="vm-1042>dns-1042"] title')).toHaveText('web-1042.prod was built from web-1042 by register-dns')

    await page.getByRole('button', { name: 'Retire' }).click()
    await page.getByRole('dialog', { name: /Retire ubuntu-2404-cis1/ }).getByRole('button', { name: 'Retire' }).click()
    const blocked = page.getByRole('dialog', { name: 'Cannot retire' })
    await expect(blocked).toContainText('web-1042')
    await expect(blocked).toContainText('web-1043')
    await blocked.getByRole('button', { name: 'OK' }).click()

    await graph.getByRole('link', { name: /web-1043/ }).click()
    await expect(page).toHaveURL(/#\/resources\/vm-1043$/)
    await expect(page.getByRole('heading', { level: 1, name: 'web-1043' })).toBeVisible()
  })
})
