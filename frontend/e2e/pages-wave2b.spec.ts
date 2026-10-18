import { expect, test } from '@playwright/test'
import { ANSIBLE_IMPORT, ASSISTANT_DRAFT_DEFINITION, ASSISTANT_DRAFT_YAML, ASSISTANT_PROBLEMS, ASSISTANT_SESSION_EVENTS, CATALOG, CONNECTION, CREDENTIALS, DEPRECATED_CATALOG, JOB, ME, NOTIFICATIONS, PLATFORM_STREAM, PROVIDER, PROVIDERS, PROVIDER_ALERTS, PROVIDER_CR, PROVIDER_RELEASE, RELEASES, RESOURCE_TYPES, SERVICE_ACCOUNTS, SPEC, SPEC_FROM_GIT, TERRAFORM_IMPORT, TRY_RESULT, TRY_RESULT_RECORDED, VALIDATION, VMS, VM_RESTART_VERSIONS, WORKFLOWS, inboxRoutes, mockApi, reply, sse } from './fixtures'
import type { ServiceAccount } from '../src/lib/types'

/*
 * The pages against a mocked /api/v1: the inbox in the header, the platform audit stream, a
 * deprecated catalog entry, a run pinned to a version with an idempotency key and a 429, the
 * Swagger links, provider releases and the provider change requests, the provider imports, the
 * assistant's validation rounds and the service-account scopes.
 */

test.describe('notifications', () => {
  test('the bell counts the unread, the panel lists the inbox and marks one, then all, read', async ({ page }) => {
    const api = await mockApi(page, inboxRoutes())
    await page.goto('/#/jobs')
    const bell = page.getByTestId('notifications-bell')
    await expect(bell).toHaveAccessibleName('Notifications: 2 unread')
    await bell.click()
    const panel = page.getByRole('dialog', { name: 'Notifications' })
    await expect(panel).toBeVisible()
    const items = panel.getByRole('list', { name: 'Notifications' }).getByRole('listitem')
    await expect(items).toHaveCount(3)
    // newest first, as the backend orders them; where each one leads
    await expect(items.nth(0)).toContainText('Approve the restart of web-1042')
    await expect(items.nth(0).getByRole('link', { name: 'Open job' })).toHaveAttribute('href', '#/jobs/job-7f3a2c91')
    await expect(items.nth(1).getByRole('link', { name: 'Open provider' })).toHaveAttribute('href', '#/providers/vsphere')
    await expect(items.nth(2)).toHaveAttribute('data-read', 'true')

    await items.nth(1).getByRole('button', { name: 'Mark read: vsphere is failing' }).click()
    await expect.poll(() => api.callsTo('POST', '/notifications/ntf-2:read').length).toBe(1)
    await expect(bell).toHaveAccessibleName('Notifications: 1 unread')
    await expect(items.nth(1)).toHaveAttribute('data-read', 'true')

    await panel.getByRole('button', { name: 'Mark all read' }).click()
    await expect.poll(() => api.callsTo('POST', '/notifications/ntf-1:read').length).toBe(1)
    await expect(bell).toHaveAccessibleName('Notifications')
    await expect(panel.getByRole('button', { name: 'Mark all read' })).toBeDisabled()

    // Escape closes the panel; following a link does too
    await page.keyboard.press('Escape')
    await expect(panel).toBeHidden()
    await bell.click()
    await page.getByRole('dialog', { name: 'Notifications' }).getByRole('link', { name: 'Open provider' }).click()
    await expect(page).toHaveURL(/#\/providers\/vsphere$/)
    await expect(page.getByRole('dialog', { name: 'Notifications' })).toBeHidden()
  })

  test('an empty inbox says so and the bell carries no count', async ({ page }) => {
    await mockApi(page, inboxRoutes(NOTIFICATIONS.map((n) => ({ ...n, read: true }))))
    await page.goto('/#/jobs')
    const bell = page.getByTestId('notifications-bell')
    await expect(bell).toHaveAccessibleName('Notifications')
    await bell.click()
    await expect(page.getByRole('dialog', { name: 'Notifications' }).getByRole('listitem')).toHaveCount(3)
    await page.keyboard.press('Escape')
    await mockApi(page, inboxRoutes([]))
    await page.reload()
    await page.getByTestId('notifications-bell').click()
    await expect(page.getByText('Nothing for you')).toBeVisible()
  })
})

test.describe('audit: the platform stream', () => {
  test('a second tab lists platform events with their subjects, filters by type and actor, and opens the entry', async ({ page }) => {
    const api = await mockApi(page, {
      'GET /audit': [],
      'GET /workflows': WORKFLOWS,
      'GET /audit/platform': (req) => {
        const aggregate = req.query.get('aggregate')
        const actor = req.query.get('actor')
        const entries = PLATFORM_STREAM.entries.filter((e) => (!aggregate || e.data.aggregate === aggregate) && (!actor || e.actor === actor))
        return { ...PLATFORM_STREAM, entries, total: PLATFORM_STREAM.total }
      },
    })
    await page.goto('/#/audit')
    await page.getByRole('tab', { name: 'Platform events' }).click()
    await expect(page).toHaveURL(/tab=platform/)
    await expect(page.getByText('Chain intact')).toBeVisible()
    await expect(page.getByText('6 of 6 entries verify against their hashes')).toBeVisible()
    const table = page.getByRole('table', { name: 'Platform events' })
    const rows = table.locator('tbody tr')
    await expect(rows).toHaveCount(6)
    // newest first; each row says what happened and links to its subject
    await expect(rows.nth(0)).toContainText('service account ci-deployer created')
    await expect(rows.nth(1).getByRole('link', { name: 'rel-1' })).toHaveAttribute('href', '#/releases/rel-1')
    await expect(rows.nth(2).getByRole('link', { name: 'vsphere' })).toHaveAttribute('href', '#/providers/vsphere')
    await expect(rows.nth(5).getByRole('link', { name: 'cr-41' })).toHaveAttribute('href', '#/change-requests/cr-41')

    await page.getByRole('button', { name: 'Type' }).click()
    await page.getByRole('option', { name: 'Providers' }).click()
    await expect.poll(() => api.callsTo('GET', '/audit/platform').some((c) => c.query.includes('aggregate=provider'))).toBe(true)
    await expect(rows).toHaveCount(1)
    await expect(rows.nth(0)).toContainText('vsphere 2.1.1 change requested')

    await page.getByLabel('Actor').fill('maria')
    await expect.poll(() => api.callsTo('GET', '/audit/platform').some((c) => c.query.includes('actor=maria') && c.query.includes('aggregate=provider'))).toBe(true)
    await page.getByRole('button', { name: 'Clear' }).click()
    await expect(rows).toHaveCount(6)

    await rows.nth(3).getByText('vm-restart 1.2.0 published').click()
    const drawer = page.getByRole('dialog', { name: 'Platform event 3' })
    await expect(drawer).toBeVisible()
    await expect(drawer).toContainText('vm-restart 1.2.0 published')
    await expect(drawer).toContainText('sha256:1')
    await page.keyboard.press('Escape')
    await expect(drawer).toBeHidden()

    // the job evidence tab is still there, untouched
    await page.getByRole('tab', { name: 'Job evidence' }).click()
    await expect(page.getByRole('table', { name: 'Evidence records' })).toBeVisible()
  })

  test('a broken chain says where it broke', async ({ page }) => {
    await mockApi(page, { 'GET /audit': [], 'GET /workflows': WORKFLOWS, 'GET /audit/platform': { ...PLATFORM_STREAM, verification: { valid: false, entries: 6, brokenAt: 4 } } })
    await page.goto('/#/audit?tab=platform')
    await expect(page.getByRole('tab', { name: 'Platform events' })).toHaveAttribute('aria-selected', 'true')
    await expect(page.getByText('Chain broken at entry 4')).toBeVisible()
  })
})

test.describe('catalog: a deprecated operation', () => {
  const routes = {
    'GET /catalog': DEPRECATED_CATALOG,
    'GET /resource-types': RESOURCE_TYPES,
    'GET /vms': VMS,
  }

  test('the catalog marks the operation and the run page says since when, until when and what to use instead', async ({ page }) => {
    await mockApi(page, routes)
    await page.goto('/#/catalog')
    const marked = page.getByRole('table', { name: 'Catalog' }).getByRole('row').filter({ hasText: 'deprecated' })
    await expect(marked).toHaveCount(1)
    await expect(marked).toContainText('VM restart')
    await expect(marked.getByTitle(/^Deprecated since 2026-10-01; sunset 2027-01-31/)).toBeVisible()
    // the cards carry the same capsule
    await page.getByRole('radio', { name: 'Cards' }).click()
    await expect(page.getByRole('link', { name: /^VM restart 1\.2\.0/ }).getByText('deprecated', { exact: true })).toBeVisible()

    await page.goto('/#/catalog/vm-restart')
    await expect(page.getByRole('heading', { level: 1, name: 'VM restart' })).toBeVisible()
    const notice = page.getByRole('note', { name: 'Deprecated operation' })
    await expect(notice).toContainText('Deprecated')
    await expect(notice).toContainText('2026-10-01')
    await expect(notice).toContainText('2027-01-31')
    await expect(notice).toContainText('Use v2, which restarts through the maintenance window.')
    // the replacement path is answered by a published workflow: the link goes to its run page
    const successor = notice.getByRole('link', { name: /VM restart \(v2\)/ })
    await expect(successor).toHaveAttribute('href', '#/catalog/vm-restart-v2')
    await successor.click()
    await expect(page.getByRole('heading', { level: 1, name: 'VM restart (v2)' })).toBeVisible()
    await expect(page.getByRole('note', { name: 'Deprecated operation' })).toHaveCount(0)
  })
})

test.describe('catalog: a run pinned to a version', () => {
  test('Advanced pins the version; the submit keeps one idempotency key through a 429 and waits for Retry-After', async ({ page }) => {
    let attempts = 0
    const api = await mockApi(page, {
      'GET /catalog': CATALOG,
      'GET /resource-types': RESOURCE_TYPES,
      'GET /vms': VMS,
      'GET /workflows/{name}/versions': VM_RESTART_VERSIONS,
      'POST /vms/{vmId}:restart': () => (++attempts === 1 ? reply(429, { error: 'team-compute is over its quota of 60 jobs a minute', retryAfter: 2 }, { 'Retry-After': '2' }) : reply(202, { jobId: 'job-new', status: 'queued' })),
      'GET /jobs/{id}': JOB,
      'GET /jobs/{id}/events': sse([]),
    })
    await page.goto('/#/catalog/vm-restart')
    await page.getByRole('button', { name: 'VM id' }).click()
    await page.getByRole('option', { name: /web-1042/ }).click()

    // the versions load once the disclosure opens; drafts are not offered
    const advanced = page.getByTestId('run-advanced')
    await advanced.locator('summary').click()
    await expect.poll(() => api.callsTo('GET', '/workflows/vm-restart/versions').length).toBe(1)
    await page.getByRole('button', { name: 'Run exactly version' }).click()
    await expect(page.getByRole('option', { name: /1\.3\.0/ })).toHaveCount(0)
    await page.getByRole('option', { name: /1\.1\.0/ }).click()
    await expect(advanced).toContainText('bypassing the canary split')
    await expect(advanced).toContainText('exactly 1.1.0')

    const run = page.getByRole('button', { name: 'Run', exact: true })
    await run.click()
    const notice = page.getByTestId('quota-notice')
    await expect(notice).toContainText('team-compute is over its quota of 60 jobs a minute')
    await expect(notice).toContainText(/try again in [12] s/)
    await expect(run).toBeDisabled()
    await expect(run).toBeEnabled({ timeout: 5000 })
    await expect(notice).toContainText('you can try again now')
    await run.click()
    await expect(page).toHaveURL(/#\/jobs\/job-new/)

    const calls = api.callsTo('POST', '/vms/vm-1042:restart')
    expect(calls).toHaveLength(2)
    expect(calls[0].headers['x-workflow-version']).toBe('1.1.0')
    expect(calls[0].headers['idempotency-key']).toMatch(/^[0-9a-f-]{36}$/)
    expect(calls[1].headers['idempotency-key']).toBe(calls[0].headers['idempotency-key'])
    expect(calls[1].headers['x-workflow-version']).toBe('1.1.0')
  })

  test('a batch sends its key and pinned version too', async ({ page }) => {
    const api = await mockApi(page, {
      'GET /catalog': CATALOG,
      'GET /resource-types': RESOURCE_TYPES,
      'GET /vms': VMS,
      'GET /workflows/{name}/versions': VM_RESTART_VERSIONS,
      'POST /vms:restart:batch': reply(202, { jobId: 'job-batch', rows: 2, status: 'queued', links: { job: '/api/v1/jobs/job-batch' } }),
      'GET /jobs/{id}': JOB,
      'GET /jobs/{id}/events': sse([]),
    })
    await page.goto('/#/catalog/vm-restart')
    await page.getByRole('radio', { name: 'Many' }).click()
    await page.getByLabel('Rows as CSV or JSON').fill('vmId,reason\nvm-1,patch\nvm-2,patch')
    await page.getByRole('button', { name: 'Load rows' }).click()
    const advanced = page.getByTestId('run-advanced')
    await advanced.locator('summary').click()
    await page.getByRole('button', { name: 'Run exactly version' }).click()
    await page.getByRole('option').filter({ hasText: 'published' }).click()
    await expect(advanced).toContainText('exactly 1.2.0')
    await page.getByRole('button', { name: 'Start batch of 2' }).click()
    await expect(page).toHaveURL(/#\/jobs\/job-batch/)
    const [call] = api.callsTo('POST', '/vms:restart:batch')
    expect(call.headers['x-workflow-version']).toBe('1.2.0')
    expect(call.headers['idempotency-key']).toMatch(/^[0-9a-f-]{36}$/)
  })
})

test.describe('job: the engine link', () => {
  test('opens the job in Temporal', async ({ page }) => {
    await mockApi(page, {
      'GET /jobs/{id}': { ...JOB, links: { temporal: 'https://temporal.example.com/namespaces/default/workflows/job-7f3a2c91' } },
      'GET /jobs/{id}/events': sse([]),
    })
    await page.goto(`/#/jobs/${JOB.id}`)
    const link = page.getByRole('link', { name: 'Open in Temporal' })
    await expect(link).toHaveAttribute('href', 'https://temporal.example.com/namespaces/default/workflows/job-7f3a2c91')
    await expect(link).toHaveAttribute('target', '_blank')
  })

  test('a job off Temporal has no engine link, and a retry over the quota waits for Retry-After', async ({ page }) => {
    const api = await mockApi(page, {
      'GET /jobs/{id}': JOB,
      'GET /jobs/{id}/events': sse([]),
      'POST /jobs/{id}:retry': reply(429, { error: 'team-compute is over its quota of 60 jobs a minute', retryAfter: 1 }, { 'Retry-After': '1' }),
    })
    await page.goto(`/#/jobs/${JOB.id}`)
    await expect(page.getByRole('heading', { level: 1 })).toContainText(JOB.title)
    await expect(page.getByRole('link', { name: /Open in/ })).toHaveCount(0)

    const panel = page.getByRole('region', { name: 'Needs attention' })
    const retry = panel.getByRole('button', { name: 'Retry' })
    await retry.click()
    await expect(panel.getByTestId('quota-notice')).toContainText('team-compute is over its quota of 60 jobs a minute — try again in 1 s')
    await expect(retry).toBeDisabled()
    await expect(retry).toBeEnabled({ timeout: 4000 })
    const [call] = api.callsTo('POST', `/jobs/${JOB.id}:retry`)
    expect(call.headers['idempotency-key']).toMatch(/^[0-9a-f-]{36}$/)
  })
})

test.describe('the API documents itself', () => {
  test('the catalog, a run page and the settings link Swagger UI and the OpenAPI document', async ({ page }) => {
    await mockApi(page, { 'GET /catalog': CATALOG, 'GET /resource-types': RESOURCE_TYPES, 'GET /vms': VMS, 'GET /service-accounts': [] })
    await page.goto('/#/catalog')
    const links = page.getByTestId('api-links')
    await expect(links.getByRole('link', { name: 'Swagger UI' })).toHaveAttribute('href', '/swagger-ui')
    await expect(links.getByRole('link', { name: 'openapi.json' })).toHaveAttribute('href', '/api/v1/openapi.json')

    await page.goto('/#/catalog/vm-restart')
    await page.getByText('Call it from the API').click()
    const panel = page.getByTestId('api-links')
    await expect(panel.getByRole('link', { name: 'Swagger UI' })).toHaveAttribute('target', '_blank')

    await page.goto('/#/settings')
    const about = page.getByTestId('api-links')
    await expect(about.getByRole('link', { name: 'Swagger UI' })).toHaveAttribute('href', '/swagger-ui')
    await expect(about.getByRole('link', { name: 'openapi.json' })).toHaveAttribute('href', '/api/v1/openapi.json')
  })
})

test.describe('provider releases', () => {
  const routes = {
    'GET /workflows': WORKFLOWS,
    'GET /providers': PROVIDERS,
    'GET /releases': (req: { query: URLSearchParams }) => (req.query.get('provider') || req.query.get('kind') === 'provider' ? [PROVIDER_RELEASE] : req.query.get('kind') === 'all' ? [...RELEASES, PROVIDER_RELEASE] : RELEASES),
    'GET /releases/{id}': (req: { params: Record<string, string> }) => [...RELEASES, PROVIDER_RELEASE].find((r) => r.id === req.params.id) ?? reply(404, { error: 'No such release' }),
  }

  test('the releases page lists provider releases by kind and the release page judges calls', async ({ page }) => {
    const api = await mockApi(page, routes)
    await page.goto('/#/releases')
    const table = page.getByRole('table', { name: 'Releases' })
    await expect(table.getByRole('row', { name: /vm-restart/ })).toBeVisible()
    await expect(table.getByText('provider', { exact: true })).toHaveCount(0)

    await page.getByRole('radio', { name: 'Providers' }).click()
    await expect.poll(() => api.callsTo('GET', '/releases').some((c) => c.query === '?kind=provider')).toBe(true)
    const row = table.getByRole('row', { name: /vsphere/ })
    await expect(row).toContainText('provider')
    await expect(row.getByRole('link', { name: 'vsphere' })).toHaveAttribute('href', '#/providers/vsphere')
    await expect(row).toContainText('Stage 2 of 3')

    await page.getByRole('radio', { name: 'Both' }).click()
    await expect(table.getByRole('row').filter({ hasText: /→/ })).toHaveCount(3)

    await table.getByRole('row', { name: /vsphere/ }).click()
    await expect(page).toHaveURL(/#\/releases\/rel-p1$/)
    await expect(page.getByRole('heading', { level: 1 })).toContainText('vsphere')
    await expect(page.getByText('Provider release')).toBeVisible()
    await expect(page.getByText('340 calls since the release started, dev left out')).toBeVisible()
    const gates = page.getByRole('table', { name: 'Gates' })
    await expect(gates.getByRole('row', { name: /Sample size/ })).toContainText('12 calls')
    await expect(gates.getByRole('row', { name: /Sample size/ })).toContainText('at least 20 calls')
    await expect(page.getByRole('link', { name: 'vsphere' })).toHaveAttribute('href', '#/providers/vsphere')
    await expect(page.getByText('the calls of every environment but dev')).toBeVisible()
    // the same controls as a workflow release
    await expect(page.getByRole('button', { name: 'Promote' })).toBeVisible()
    await expect(page.getByRole('button', { name: 'Pause' })).toBeVisible()
  })

  test('the provider page shows the release in flight with its stage and gates', async ({ page }) => {
    await mockApi(page, { ...routes, 'GET /providers/{name}': PROVIDER, 'GET /providers/{name}/alerts': PROVIDER_ALERTS })
    await page.goto('/#/providers/vsphere')
    const card = page.getByTestId('provider-release')
    await expect(card).toContainText('Release in flight')
    await expect(card).toContainText('Stage 2 of 3')
    await expect(card.getByRole('list', { name: 'Gates' }).getByRole('listitem')).toHaveCount(4)
    await expect(card).toContainText('2/4 gates')
    await expect(card.getByRole('link', { name: 'Follow the release' })).toHaveAttribute('href', '#/releases/rel-p1')
    await expect(page.getByText('canary 2.2.0 at 50%')).toBeVisible()
  })
})

test.describe('provider: every edit is a change request', () => {
  test('the SLO, the real response and a spec refresh open change requests the page names and links', async ({ page }) => {
    const refreshes: unknown[] = []
    const api = await mockApi(page, {
      'GET /providers/{name}': PROVIDER,
      'GET /providers/{name}/alerts': PROVIDER_ALERTS,
      'GET /releases': [],
      'PUT /providers/{name}/slo': reply(202, { id: 'cr-90', status: 'open' }),
      'POST /providers:try': { ...TRY_RESULT, mismatches: [{ field: 'items[0].state', expected: 'number', actual: 'running' }] },
      'POST /providers/{name}/overrides': reply(202, { id: 'cr-91', status: 'open' }),
      'POST /providers/{name}:refresh-spec': (req: { body: unknown }) => {
        refreshes.push(req.body)
        return refreshes.length === 1 ? reply(202, { id: 'vsphere', status: 'unchanged' }) : reply(202, { id: 'cr-92', status: 'open' })
      },
      'GET /change-requests/{id}': (req: { params: Record<string, string> }) => ({ ...PROVIDER_CR, id: req.params.id, name: 'vsphere', title: `vsphere: ${req.params.id}` }),
    })
    await page.goto('/#/providers/vsphere')

    // what is already waiting for review
    const pending = page.getByTestId('pending-change-requests')
    await expect(pending).toContainText('vsphere: add vm.snapshot')
    await expect(pending.getByRole('link', { name: 'cr-88' })).toHaveAttribute('href', '#/change-requests/cr-88')

    // the SLO
    await page.getByRole('button', { name: 'SLO' }).click()
    await page.getByLabel('Success rate (%)').fill('99.7')
    await page.getByRole('button', { name: 'Save SLO' }).click()
    const sloToast = page.getByRole('status').filter({ hasText: 'Opened change request cr-90' })
    await expect(sloToast).toBeVisible()
    expect(api.callsTo('PUT', '/providers/vsphere/slo')[0].body).toEqual({ successRate: 99.7, p95Ms: 800, channel: '#compute-alerts' })

    // the real response of a tried action
    await page.getByRole('button', { name: 'Try action' }).click()
    await page.getByRole('button', { name: 'Try it', exact: true }).click()
    await page.getByRole('button', { name: 'Use the real response' }).click()
    await expect(page.getByRole('status').filter({ hasText: 'Opened change request cr-91' })).toBeVisible()
    expect(api.callsTo('POST', '/providers/vsphere/overrides')[0].body).toEqual({ action: 'vm.get', response: TRY_RESULT.response })
    await page.keyboard.press('Escape')

    // the spec, read again: first unchanged, then at a new ref
    const dialog = page.getByRole('dialog', { name: 'Refresh spec' })
    await page.getByRole('button', { name: 'Refresh spec' }).first().click()
    await dialog.getByRole('button', { name: 'Refresh spec' }).click()
    await expect(page.getByRole('status').filter({ hasText: 'Nothing to change' })).toBeVisible()
    await expect(dialog).toBeHidden()
    await page.getByRole('button', { name: 'Refresh spec' }).first().click()
    await dialog.getByLabel('Ref').fill('v8.0.3')
    await dialog.getByRole('button', { name: 'Refresh spec' }).click()
    const refreshToast = page.getByRole('status').filter({ hasText: 'Opened change request cr-92' })
    await expect(refreshToast).toBeVisible()
    expect(refreshes).toEqual([{}, { ref: 'v8.0.3' }])

    // the toast's button opens the change request
    await refreshToast.getByRole('button', { name: 'Open change request' }).click()
    await expect(page).toHaveURL(/#\/change-requests\/cr-92$/)
  })
})

test.describe('provider imports', () => {
  test('an Ansible repository becomes a draft the review step shows and publishes as it came', async ({ page }) => {
    const api = await mockApi(page, {
      'GET /providers': PROVIDERS,
      'GET /credentials': CREDENTIALS,
      'POST /providers:import-ansible': ANSIBLE_IMPORT,
      'POST /providers:validate': VALIDATION,
      'POST /providers:publish': { id: 'cr-101', status: 'open' },
      'GET /change-requests/{id}': PROVIDER_CR,
    })
    await page.goto('/#/providers')
    await page.getByRole('button', { name: 'Import an Ansible repository' }).click()
    await expect(page).toHaveURL(/#\/providers\/new\?import=ansible$/)
    await expect(page.getByRole('heading', { level: 1, name: 'Import an Ansible repository' })).toBeVisible()

    const importButton = page.getByRole('button', { name: 'Import playbooks' })
    await expect(importButton).toBeDisabled()
    await page.getByRole('textbox', { name: 'Repository' }).fill('https://github.com/acme/ops-playbooks')
    await page.getByRole('textbox', { name: 'Ref' }).fill('v3.1.0')
    await page.getByRole('textbox', { name: 'Path' }).fill('playbooks/')
    await page.getByRole('textbox', { name: 'Playbooks' }).fill('vm/restart.yml, vm/snapshot.yml')
    // the owner is the signed-in user's team until typed over
    await expect(page.getByRole('textbox', { name: 'Owner' })).toHaveValue('team-platform')
    await page.getByRole('button', { name: 'Machine credential' }).click()
    await page.getByRole('option', { name: /vsphere\/token/ }).click()
    await page.getByRole('textbox', { name: 'Execution environment' }).fill('ee-platform:2.17')
    await importButton.click()

    // the wizard opens on the review, with the import's draft as the document
    const current = page.getByRole('list', { name: 'Steps' }).locator('[aria-current="step"]')
    await expect(current).toContainText('Review')
    expect(api.callsTo('POST', '/providers:import-ansible')[0].body).toEqual({
      git: { repo: 'https://github.com/acme/ops-playbooks', ref: 'v3.1.0', path: 'playbooks/' },
      playbooks: ['vm/restart.yml', 'vm/snapshot.yml'],
      owner: 'team-platform',
      credential: 'vault:vsphere/token',
      executionEnvironment: 'ee-platform:2.17',
    })
    const summary = page.getByTestId('import-summary')
    await expect(summary).toContainText('https://github.com/acme/ops-playbooks at v3.1.0, pinned to a7c3e9f1b2d4')
    await expect(summary.getByRole('list', { name: 'Import notes' })).toContainText('2 playbooks become actions; 1 skipped.')
    await expect(summary).toContainText('Skipped: site.yml: imports every play, not a single action')
    const playbooks = summary.getByRole('table', { name: 'Imported playbooks' })
    await expect(playbooks.getByRole('row')).toHaveCount(3)
    await expect(playbooks.getByRole('row', { name: /vm\.snapshot/ })).toContainText('vcenter_password')
    await expect(page.getByText("The import's draft replaces the wizard's document.")).toBeVisible()
    const pre = page.locator('pre').first()
    for (const line of ['type: ansible', 'commit: a7c3e9f1b2d4068c5e7a9b1d3f5c7e9a1b3d5f7c', 'playbook: vm/snapshot.yml']) await expect(pre).toContainText(line)

    await page.getByRole('button', { name: 'Run checks' }).click()
    await expect(page.getByText('Ready to publish.')).toBeVisible()
    // the review's evidence, with the SLO it suggests
    await page.getByTestId('review-evidence').getByRole('button', { name: 'Use suggested SLO' }).click()
    await expect(page.getByRole('spinbutton', { name: 'SLO success (%)' })).toHaveValue('99.5')
    await expect(page.getByRole('spinbutton', { name: 'SLO p95 (ms)' })).toHaveValue('800')
    await page.getByRole('button', { name: 'Publish as change request' }).click()
    await expect(page).toHaveURL(/#\/change-requests\/cr-101$/)
    const published = api.callsTo('POST', '/providers:publish')[0].body as { yaml: string; strategy: string }
    expect(published.yaml).toBe(ANSIBLE_IMPORT.yaml)
    expect(published.strategy).toBe('canary')
  })

  test('a Terraform module from the registry becomes a draft, its variables and outputs listed', async ({ page }) => {
    const api = await mockApi(page, {
      'GET /credentials': CREDENTIALS,
      'POST /providers:import-terraform': TERRAFORM_IMPORT,
    })
    await page.goto('/#/providers/new?import=terraform')
    await expect(page.getByRole('heading', { level: 1, name: 'Import a Terraform module' })).toBeVisible()
    await page.getByRole('radio', { name: 'Registry' }).click()
    await page.getByRole('textbox', { name: 'Module', exact: true }).fill('acme/lb/aws')
    await page.getByRole('textbox', { name: 'Version' }).fill('2.3.0')
    await page.getByRole('textbox', { name: 'Name', exact: true }).fill('lb')
    await page.getByRole('button', { name: 'Runner credential' }).click()
    await page.getByRole('option', { name: /backup\/api-key/ }).click()
    await page.getByRole('button', { name: 'Import module' }).click()

    const summary = page.getByTestId('import-summary')
    await expect(summary).toContainText('acme/lb/aws 2.3.0')
    expect(api.callsTo('POST', '/providers:import-terraform')[0].body).toEqual({ registry: { module: 'acme/lb/aws', version: '2.3.0' }, name: 'lb', owner: 'team-platform', credential: 'vault:backup/api-key' })
    const variables = summary.getByRole('table', { name: 'Module variables' })
    await expect(variables.getByRole('row', { name: /db_password/ })).toContainText('Vault')
    await expect(variables.getByRole('row', { name: /listeners/ })).toContainText('the input')
    await expect(summary.getByRole('table', { name: 'Module outputs' })).toContainText('dns_name')
    await expect(page.locator('pre').first()).toContainText('source: acme/lb/aws')
  })

  test('the spec comes from Git at a ref and the file pins the commit it read', async ({ page }) => {
    const api = await mockApi(page, {
      'GET /credentials': CREDENTIALS,
      'POST /providers:import-spec': SPEC_FROM_GIT,
    })
    await page.goto('/#/providers/new')
    await page.getByRole('textbox', { name: 'Title', exact: true }).fill('Backup service')
    await page.getByRole('button', { name: 'Next' }).click()
    await page.getByRole('radio', { name: 'Git' }).click()
    const importButton = page.getByRole('button', { name: 'Import spec' })
    await expect(importButton).toBeDisabled()
    await page.getByRole('textbox', { name: 'Repository' }).fill('https://github.com/acme/backup-api')
    await page.getByRole('textbox', { name: 'Ref' }).fill('v1.0.0')
    await page.getByRole('textbox', { name: 'Path' }).fill('openapi.yaml')
    await page.getByRole('button', { name: 'Read credential' }).click()
    await page.getByRole('option', { name: /backup\/api-key/ }).click()
    await importButton.click()
    await expect(page.getByText('2 operations from Backup service 1.0.0.')).toBeVisible()
    await expect(page.getByText('commit 4f9e2a7c1d0b…')).toBeVisible()
    expect(api.callsTo('POST', '/providers:import-spec')[0].body).toEqual({ type: 'openapi', git: { repo: 'https://github.com/acme/backup-api', ref: 'v1.0.0', path: 'openapi.yaml', credential: 'vault:backup/api-key' } })

    await page.getByRole('button', { name: 'Credential', exact: true }).click()
    await page.getByRole('option', { name: /backup\/api-key/ }).click()
    await page.getByRole('button', { name: 'Next' }).click()
    await page.getByRole('button', { name: 'Next' }).click()
    await page.getByRole('button', { name: 'Skip to review' }).click()
    const pre = page.locator('pre').first()
    for (const line of ['repo: https://github.com/acme/backup-api', 'ref: v1.0.0', 'path: openapi.yaml', 'credential: vault:backup/api-key', 'commit: 4f9e2a7c1d0b5e8a9c3f6b2d7e1a4c8f0b9d2e5a']) await expect(pre).toContainText(line)
    await expect(pre).not.toContainText('url:')
  })

  test('an uploaded spec file is sent as text', async ({ page }) => {
    const api = await mockApi(page, { 'GET /credentials': CREDENTIALS, 'POST /providers:import-spec': SPEC })
    await page.goto('/#/providers/new')
    await page.getByRole('textbox', { name: 'Title', exact: true }).fill('Backup service')
    await page.getByRole('button', { name: 'Next' }).click()
    await page.getByRole('radio', { name: 'Upload' }).click()
    const importButton = page.getByRole('button', { name: 'Import spec' })
    await expect(importButton).toBeDisabled()
    const content = 'openapi: 3.0.0\ninfo:\n  title: Backup service\n'
    await page.locator('input[type=file]').setInputFiles({ name: 'openapi.yaml', mimeType: 'application/yaml', buffer: Buffer.from(content) })
    await expect(page.getByText('openapi.yaml', { exact: true })).toBeVisible()
    await importButton.click()
    await expect(page.getByText('2 operations from Backup service 1.0.0.')).toBeVisible()
    expect(api.callsTo('POST', '/providers:import-spec')[0].body).toEqual({ type: 'openapi', content })
  })
})

test.describe('editing a provider', () => {
  test('the wizard tests and tries the provider again, keeps the evidence and publishes the next patch of its file', async ({ page }) => {
    const api = await mockApi(page, {
      'GET /providers/{name}': PROVIDER,
      'GET /providers/{name}/alerts': PROVIDER_ALERTS,
      'GET /releases': [],
      'GET /credentials': CREDENTIALS,
      'POST /providers:test-connection': CONNECTION,
      'POST /providers:try': TRY_RESULT_RECORDED,
      'POST /providers:validate': VALIDATION,
      'POST /providers:publish': { id: 'cr-101', status: 'open' },
      'GET /change-requests/{id}': PROVIDER_CR,
    })
    await page.goto('/#/providers/vsphere')
    await page.getByRole('button', { name: 'Edit provider' }).click()
    await expect(page).toHaveURL(/#\/providers\/vsphere\/edit$/)
    await expect(page.getByRole('heading', { level: 1, name: 'Edit VMware vSphere' })).toBeVisible()
    const steps = page.getByRole('list', { name: 'Steps' })
    await expect(steps.getByRole('listitem')).toHaveCount(3)
    const current = steps.locator('[aria-current="step"]')
    await expect(current).toContainText('Connect')

    // the connect step carries the file's endpoints and auth, asks nothing about the spec, and the test names the provider
    await expect(page.getByRole('textbox', { name: 'Environment URL' }).first()).toHaveValue('https://vsphere-dev.example.com/rest')
    await expect(page.getByRole('textbox', { name: 'Spec URL' })).toHaveCount(0)
    await page.getByRole('button', { name: 'Test connection' }).click()
    await expect(page.getByRole('list', { name: 'Connection results' })).toContainText('200 from GET /jobs')
    expect(api.callsTo('POST', '/providers:test-connection')[0].body).toEqual({
      endpoints: { dev: 'https://vsphere-dev.example.com/rest', test: 'https://vsphere-test.example.com/rest', prod: 'https://vsphere.example.com/rest' },
      auth: { type: 'header', credential: 'vault:vsphere/token', header: 'X-Api-Key' },
      provider: 'vsphere',
    })
    await page.getByRole('button', { name: 'Next' }).click()

    // try it: the file at the next patch goes out; the backend hands it back with the answer as the sample
    await expect(current).toContainText('Try it')
    await page.getByRole('button', { name: 'Try it', exact: true }).click()
    await expect(page.getByText('HTTP 200 in 182ms.')).toBeVisible()
    const tried = api.callsTo('POST', '/providers:try')[0].body as { yaml: string; action: string; environment: string }
    expect(tried.action).toBe('vm.get')
    expect(tried.environment).toBe('dev')
    expect(tried.yaml).toContain('version: 1.3.1')
    expect(tried.yaml).not.toContain('version: 1.3.0')
    await expect(page.getByTestId('try-evidence')).toContainText('Kept as review evidence · tried in dev')
    await expect(page.getByRole('button', { name: 'Record as sample' })).toHaveCount(0)
    await page.getByTestId('try-yaml').locator('summary').click()
    await expect(page.getByTestId('try-yaml')).toContainText('powerState: poweredOn')
    await page.getByRole('button', { name: 'Use this' }).click()
    await expect(page.getByRole('button', { name: 'Using this file' })).toBeDisabled()
    await page.getByRole('button', { name: 'Next' }).click()

    // review: the file as it will be published, its checks, and the evidence as the reviewer sees it
    await expect(current).toContainText('Review')
    await expect(page.getByText("The file as published, at version 1.3.1; a try's answer goes in with Use this.")).toBeVisible()
    await expect(page.getByRole('button', { name: "Back to the wizard's YAML" })).toHaveCount(0)
    const pre = page.locator('pre').first()
    await expect(pre).toContainText('version: 1.3.1')
    await expect(pre).toContainText('powerState: poweredOn')
    await page.getByRole('button', { name: 'Run checks' }).click()
    const review = page.getByTestId('review-evidence')
    await expect(review).toContainText('Ready for review')
    const checks = review.getByRole('list', { name: 'Review checks' })
    await expect(checks.getByRole('listitem')).toHaveCount(4)
    await expect(checks).toContainText('3 of 3 actions tried in dev')
    await expect(checks).toContainText('No error classes: a tried error matches nothing')
    await expect(review.getByTestId('slo-suggestion')).toContainText('Suggested SLO: 99.5% success, p95 800ms')
    // the SLO changes from the provider page while editing
    await expect(review.getByRole('button', { name: 'Use suggested SLO' })).toHaveCount(0)
    await expect(page.getByRole('spinbutton', { name: 'SLO success (%)' })).toHaveCount(0)
    await page.getByRole('button', { name: 'Publish as change request' }).click()
    await expect(page).toHaveURL(/#\/change-requests\/cr-101$/)
    const published = api.callsTo('POST', '/providers:publish')[0].body as { yaml: string }
    expect(published.yaml).toBe(TRY_RESULT_RECORDED.yaml)
  })

  test('a review that is not ready keeps the publish button off and says why', async ({ page }) => {
    await mockApi(page, {
      'GET /providers/{name}': PROVIDER,
      'GET /credentials': CREDENTIALS,
      'POST /providers:validate': {
        ...VALIDATION,
        review: { ready: false, checks: [{ id: 'tried', label: 'Every action answered when tried', status: 'failed', detail: 'vm.delete was never tried in dev' }], problems: [{ level: 'error', code: 'review.tried', message: 'Try vm.delete in dev before publishing' }] },
      },
    })
    await page.goto('/#/providers/vsphere/edit')
    await page.getByRole('button', { name: 'Next' }).click()
    await page.getByRole('button', { name: 'Skip to review' }).click()
    await page.getByRole('button', { name: 'Run checks' }).click()
    const review = page.getByTestId('review-evidence')
    await expect(review).toContainText('Not ready yet')
    await expect(review).toContainText('Try vm.delete in dev before publishing')
    await expect(page.getByText('Ready to publish.')).toHaveCount(0)
    await expect(page.getByRole('button', { name: 'Publish as change request' })).toBeDisabled()
  })
})

test.describe('assistant', () => {
  test('the validation lists the problems on their steps, the timeline shows the session, and the draft becomes a change request', async ({ page }) => {
    const api = await mockApi(page, {
      'POST /assistant/sessions': { sessionId: 'as-77' },
      // as the backend streams it: the type as the SSE event name, the payload alone as the data
      'POST /assistant/sessions/{id}/messages': sse([
        { id: 1, event: 'status', data: { status: 'Drafting' } },
        { id: 2, event: 'message', data: { role: 'assistant', text: 'Here is a first draft: it checks the backups, then restarts.' } },
        { id: 3, event: 'draft', data: { status: 'ready', summary: 'Restarts a VM after a backup check.', workflowYaml: ASSISTANT_DRAFT_YAML, validation: { valid: false, rounds: 2, errors: ['step.unknownProvider @restart: No provider called vcenter'] } } },
        { id: 4, event: 'validation', data: { valid: false, problems: ASSISTANT_PROBLEMS } },
        { id: 5, event: 'done', data: { status: 'done' } },
      ]),
      'POST /workflows:parse': { definition: ASSISTANT_DRAFT_DEFINITION, problems: [] },
      'GET /registry/providers': [],
      'GET /audit/platform': { ...PLATFORM_STREAM, entries: [...PLATFORM_STREAM.entries, ...ASSISTANT_SESSION_EVENTS], total: 10 },
      'POST /workflows': { id: 'vm-restart-safe', status: 'created' },
      'POST /workflows/{name}:publish': { id: 'cr-120', status: 'open' },
      'GET /change-requests/{id}': { ...PROVIDER_CR, id: 'cr-120', kind: 'workflow', name: 'vm-restart-safe', title: 'Add vm-restart-safe' },
    })
    await page.goto('/#/assistant')
    const box = page.getByRole('textbox', { name: 'Message' })
    await box.fill('Restart a VM after checking no backup is running.')
    await page.getByRole('button', { name: 'Send' }).click()
    await expect(page.getByText('Here is a first draft: it checks the backups, then restarts.')).toBeVisible()

    // the validation event, grouped on the steps as the designer's Issues drawer does, with the names the parse gives them
    const issues = page.getByTestId('issues-list')
    const restart = issues.getByRole('region', { name: 'Restart the VM' })
    await expect(restart).toContainText('No provider called vcenter; vsphere is the closest')
    await expect(restart.locator('[data-code="step.unknownProvider"]')).toBeVisible()
    await expect(issues).toContainText('A change step runs without an approval')
    expect(api.callsTo('POST', '/workflows:parse')[0].body).toEqual({ yaml: ASSISTANT_DRAFT_YAML })

    // the session's own events, from the platform stream
    const timeline = page.getByTestId('session-timeline')
    await expect(timeline).toContainText('Session timeline · 3 events')
    await timeline.locator('summary').click()
    const items = timeline.getByRole('listitem')
    await expect(items).toHaveCount(3)
    await expect(items.nth(0)).toContainText('Session started · create · devin')
    await expect(items.nth(1)).toContainText('Message · user: Restart a VM after checking no backup is running.')
    await expect(items.nth(2)).toContainText('Draft updated · ready · not valid · 1 error')
    expect(api.callsTo('GET', '/audit/platform')[0].query).toContain('aggregate=assistant-session')

    // ask for changes: another message in the same session
    await page.getByRole('button', { name: 'Ask for changes' }).click()
    await expect(box).toBeFocused()
    await expect(box).toHaveValue('Change the draft: ')
    await box.fill('Change the draft: use vsphere')
    await page.getByRole('button', { name: 'Send' }).click()
    await expect.poll(() => api.callsTo('POST', '/assistant/sessions/as-77/messages').length).toBe(2)
    expect(api.callsTo('POST', '/assistant/sessions')).toHaveLength(1)
    expect(api.callsTo('POST', '/assistant/sessions/as-77/messages')[1].body).toEqual({ text: 'Change the draft: use vsphere' })

    // the draft goes out as a change request: created, then published as a canary
    await page.getByRole('button', { name: 'Submit as change request' }).click()
    await expect(page.getByRole('status').filter({ hasText: 'Opened change request cr-120' })).toBeVisible()
    await expect(page).toHaveURL(/#\/change-requests\/cr-120$/)
    expect(api.callsTo('POST', '/workflows')[0].body).toEqual({ yaml: ASSISTANT_DRAFT_YAML })
    expect(api.callsTo('POST', '/workflows/vm-restart-safe:publish')[0].body).toEqual({ strategy: 'canary' })
  })
})

test.describe('service accounts', () => {
  test('the form offers the presets and the grouped scopes, the list shows each account’s scopes, and the token once', async ({ page }) => {
    let accounts = SERVICE_ACCOUNTS
    const api = await mockApi(page, {
      'GET /service-accounts': () => reply(200, accounts),
      'POST /service-accounts': (req) => {
        const body = req.body as { name: string; preset?: string; scopes?: string[]; teams?: string[] }
        const scopes = body.scopes ?? (body.preset === 'ci' ? ['registry:read', 'workflows:validate', 'workflows:dry-run', 'workflows:write', 'providers:write'] : ['registry:read', 'jobs:start', 'jobs:read'])
        // no teams given: the account acts for its creator's, and the note says so
        const teams = body.teams && body.teams.length > 0 ? body.teams : ['team-compute', 'team-platform']
        const note = body.teams && body.teams.length > 0 ? undefined : "No teams were given: the account acts for Gonzalo's teams (team-compute, team-platform)"
        accounts = [...accounts, { id: 'sa-new', name: body.name, roles: ['ops'], teams, createdAt: new Date().toISOString(), scopes: scopes as ServiceAccount['scopes'] }]
        return reply(201, { id: 'sa-new', token: 'iaf_sa_example_token_not_a_secret', scopes, teams, ...(note ? { note } : {}) })
      },
    })
    await page.goto('/#/settings')
    const table = page.getByRole('table', { name: 'Service accounts' })
    const ci = table.getByRole('row', { name: /ci-deployer/ })
    await expect(ci.getByLabel('Scopes')).toContainText('ci')
    await expect(ci.getByLabel('Scopes')).toContainText('providers:write')
    await expect(table.getByRole('row', { name: /cost-bot/ })).toContainText('not reported')

    await page.getByRole('button', { name: 'New service account' }).click()
    const dialog = page.getByRole('dialog', { name: 'New service account' })
    await dialog.getByRole('textbox', { name: 'Name', exact: true }).fill('release-bot')
    // the invoker preset is the default; CI fills its set
    await expect(dialog.getByRole('radio', { name: 'Invoker' })).toHaveAttribute('aria-checked', 'true')
    await expect(dialog.getByRole('checkbox', { name: /jobs:start/ })).toBeChecked()
    await dialog.getByRole('radio', { name: 'CI' }).click()
    await expect(dialog.getByRole('checkbox', { name: /jobs:start/ })).not.toBeChecked()
    await expect(dialog.getByRole('checkbox', { name: /workflows:write/ })).toBeChecked()
    const groups = dialog.getByTestId('scope-groups')
    await expect(groups.getByRole('group')).toHaveCount(5)
    await expect(groups.getByRole('group', { name: 'Jobs' }).getByRole('checkbox')).toHaveCount(2)
    // a scope outside the preset makes the set custom; back inside, the preset applies with the scopes it keeps
    await dialog.locator('#sa-scope-admin').check()
    await expect(dialog.getByRole('radio', { name: 'Custom' })).toHaveAttribute('aria-checked', 'true')
    await dialog.getByRole('radio', { name: 'CI' }).click()
    await expect(dialog.locator('#sa-scope-admin')).not.toBeChecked()
    await dialog.getByRole('checkbox', { name: /providers:write/ }).uncheck()
    await expect(dialog.getByRole('radio', { name: 'CI' })).toHaveAttribute('aria-checked', 'true')
    await dialog.getByRole('button', { name: 'Create' }).click()

    // the token, once, with what it may call; then the list carries the scopes
    const token = page.getByRole('dialog', { name: 'Service account token' })
    await expect(token).toContainText('iaf_sa_example_token_not_a_secret')
    await expect(token.getByLabel('Scopes')).toContainText('workflows:write')
    // the teams it acts for: its creator's, since none were given
    const actsFor = token.getByTestId('acts-for')
    await expect(actsFor.getByLabel('Teams')).toContainText('team-compute')
    await expect(actsFor.getByLabel('Teams')).toContainText('team-platform')
    await expect(actsFor).toContainText("No teams were given: the account acts for Gonzalo's teams (team-compute, team-platform)")
    expect(api.callsTo('POST', '/service-accounts')[0].body).toEqual({
      name: 'release-bot',
      roles: ['ops'],
      teams: [],
      preset: 'ci',
      scopes: ['registry:read', 'workflows:validate', 'workflows:dry-run', 'workflows:write'],
    })
    await token.getByRole('button', { name: 'Done' }).click()
    await expect(table.getByRole('row', { name: /release-bot/ }).getByLabel('Scopes')).toContainText('workflows:write')
  })
})

test.describe('settings', () => {
  test('the identity card says production signs in through the auth proxy and links the README', async ({ page }) => {
    await mockApi(page, { 'GET /service-accounts': SERVICE_ACCOUNTS })
    await page.goto('/#/settings')
    await expect(page.getByText('Who the API runs your requests as.')).toBeVisible()
    await expect(page.getByText('An auth proxy in front of the app and the API signs you in and adds the bearer token the API verifies')).toBeVisible()
    const readme = page.getByRole('link', { name: 'README: Identity and OIDC' })
    await expect(readme).toHaveAttribute('href', 'https://github.com/gonzalomunoz1024/infrastructure-automation/blob/main/README.md#identity-and-oidc')
    await expect(readme).toHaveAttribute('target', '_blank')
    // the shell cannot tell the API's profile, so nothing claims one
    await expect(page.getByText('Identity mode')).toHaveCount(0)
    await expect(page.getByText(/SSO/)).toHaveCount(0)
  })

  test('someone who is not an admin is told only admins see service accounts, with no error and no create button', async ({ page }) => {
    await mockApi(page, {
      'GET /me': { ...ME, id: 'ana', name: 'Ana', email: 'ana@example.com', teams: ['team-compute'], roles: ['ops'], admin: false },
      'GET /service-accounts': () => reply(403, { error: 'Only a platform admin lists service accounts' }),
    })
    await page.goto('/#/settings')
    await expect(page.getByText('Only admins see service accounts')).toBeVisible()
    await expect(page.getByText('ask one for an account for your pipeline or agent')).toBeVisible()
    await expect(page.getByText('Could not load service accounts')).toHaveCount(0)
    await expect(page.getByText('Only a platform admin lists service accounts')).toHaveCount(0)
    await expect(page.getByRole('button', { name: 'New service account' })).toHaveCount(0)
  })
})
