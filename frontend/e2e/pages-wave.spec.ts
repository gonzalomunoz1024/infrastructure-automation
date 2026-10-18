import { expect, test } from '@playwright/test'
import {
  BASE_IMAGE,
  BATCH_JOB,
  BATCH_RETRIED,
  BUILD_JOB,
  CATALOG,
  CREATE_PREVIEW,
  CREATE_VM_DEFINITION,
  CREATE_VM_VERSIONS,
  CREATE_VM_YAML,
  IMAGE,
  JOBS,
  PROVIDER_CR,
  REPLAY,
  RESOURCE_TYPES,
  WORKFLOW_CR,
  WORKFLOW_CR_DEFINITION,
  WORKFLOWS,
  mockApi,
  reply,
  sse,
} from './fixtures'

/*
 * The pages against a mocked /api/v1: an input built first on the catalog run page and in the
 * preview, the job it became with its child jobs and a replay compared with another run, the
 * publish checks of a change request, and a version's step list.
 */

test.describe('catalog: an input built first', () => {
  const routes = {
    'GET /catalog': CATALOG,
    'GET /resource-types': RESOURCE_TYPES,
    'GET /images': [IMAGE, BASE_IMAGE],
    'POST /vms:preview': CREATE_PREVIEW,
  }

  test('the form offers an existing image or building one, and the preview says what the dry run did', async ({ page }) => {
    const api = await mockApi(page, routes)
    await page.goto('/#/catalog/create-vm')
    await expect(page.getByRole('heading', { level: 1, name: 'Create a VM' })).toBeVisible()
    await page.getByLabel('Name').fill('web-1044')

    // the two forms of CatalogEntry.input: the id of an existing image, or { create: <producer's input> }
    const existing = page.getByRole('radio', { name: 'Use an existing image' })
    await expect(existing).toHaveAttribute('aria-checked', 'true')
    await expect(page.getByRole('button', { name: 'Image id' })).toBeVisible()
    await page.getByRole('radio', { name: 'Build one first' }).click()
    await expect(page.getByText('Build a new image with build-vm-image first; the job continues with its id')).toBeVisible()
    await expect(page.getByLabel('Hardening')).toHaveValue('cis1')
    await page.getByRole('button', { name: 'Operating system' }).click()
    await page.getByRole('option', { name: 'rhel-9' }).click()

    await page.getByRole('button', { name: 'Preview', exact: true }).click()
    const [preview] = api.callsTo('POST', '/vms:preview')
    expect(preview.body).toEqual({ name: 'web-1044', image: { create: { os: 'rhel-9', hardening: 'cis1' } }, cpu: 2, memoryGb: 8 })

    // a step the dry run did not take is muted and says so; details and the cost's source are shown; each resource says how it is reached
    const skipped = page.locator('[data-skipped="true"]')
    await expect(skipped).toContainText('Tag for the cost centre')
    await expect(skipped).toContainText('not taken in the dry run')
    await expect(skipped).toContainText('only when a cost centre is given')
    await expect(page.getByText('build-vm-image ^1 with os rhel-9')).toBeVisible()
    await expect(page.getByText('Priced from: list-prices: 2 vCPU × 8 GB in eu-west, on demand')).toBeVisible()
    await expect(page.getByRole('link', { name: /rhel-9 cis1/ })).toContainText('built from image.create')
    await expect(page.getByRole('link', { name: /web-1044/ })).toContainText('via step register')
  })

  test('a 400 on a nested field highlights it, and the run sends the same body as the preview', async ({ page }) => {
    let attempts = 0
    const api = await mockApi(page, {
      ...routes,
      'POST /vms': () =>
        ++attempts === 1
          ? reply(400, { error: 'Invalid input', problems: [{ level: 'error', code: 'invalid', field: 'image.create.os', message: 'rhel-9 is not offered by build-vm-image' }] })
          : reply(202, { jobId: BUILD_JOB.id }),
      'GET /jobs/{id}': BUILD_JOB,
      'GET /jobs/{id}/events': sse([]),
      'GET /jobs': JOBS,
      'GET /workflows': WORKFLOWS,
    })
    await page.goto('/#/catalog/create-vm')
    await page.getByLabel('Name').fill('web-1044')
    await page.getByRole('radio', { name: 'Build one first' }).click()
    await page.getByRole('button', { name: 'Operating system' }).click()
    await page.getByRole('option', { name: 'rhel-9' }).click()
    await page.getByRole('button', { name: 'Preview', exact: true }).click()
    await expect(page.locator('[data-skipped="true"]')).toBeVisible()

    await page.getByRole('button', { name: 'Run', exact: true }).click()
    // the problem path image.create.os lands under the nested field, inside the build box
    const forms = page.getByTestId('run-create-vm-image-forms')
    await expect(forms).toContainText('rhel-9 is not offered by build-vm-image')
    await expect(page.getByText('rhel-9 is not offered by build-vm-image')).toHaveCount(1)

    await page.getByRole('button', { name: 'Operating system' }).click()
    await page.getByRole('option', { name: 'ubuntu-2404' }).click()
    await page.getByRole('button', { name: 'Run', exact: true }).click()
    await expect(page).toHaveURL(/#\/jobs\/job-9b2e4f10$/)

    const previewed = api.callsTo('POST', '/vms:preview')[0].body
    const runs = api.callsTo('POST', '/vms')
    expect(runs).toHaveLength(2)
    expect(runs[0].body).toEqual(previewed)
    expect(runs[1].body).toEqual({ name: 'web-1044', image: { create: { os: 'ubuntu-2404', hardening: 'cis1' } }, cpu: 2, memoryGb: 8 })
  })
})

test.describe('job: built inputs, continued failures and the replay', () => {
  const gone = { ...JOBS[2], id: 'job-gone', title: 'Create web-1040' }
  const summary = { ...BUILD_JOB, input: undefined, steps: undefined, approvals: undefined, children: undefined, traceId: undefined }
  const routes = {
    'GET /jobs/{id}': BUILD_JOB,
    'GET /jobs/{id}/events': sse([]),
    'GET /jobs': (req: { query: URLSearchParams }) => (req.query.get('workflow') === 'create-vm' ? [summary, JOBS[2], gone] : JOBS),
    'GET /jobs/{id}/replay': (req: { query: URLSearchParams }) => (req.query.get('compareTo') === 'job-gone' ? reply(404, { error: 'No such job: job-gone' }) : REPLAY),
    'GET /workflows': WORKFLOWS,
  }

  test('a build step names the input it built and links the child job; a continued run step says it failed', async ({ page }) => {
    await mockApi(page, routes)
    await page.goto('/#/jobs/job-9b2e4f10')
    const steps = page.getByRole('list', { name: 'Steps' })
    await expect(steps).toContainText('Built image for image')
    await expect(steps).not.toContainText('Build image')
    await expect(steps).toContainText('The image was built by job')
    await expect(steps.getByRole('link', { name: /Build ubuntu-2404 cis1|job-child-77/ }).first()).toHaveAttribute('href', '#/jobs/job-child-77')
    await expect(steps).toContainText('failed, the workflow continued')
    await expect(steps).toContainText('origin answered 503')
    await expect(steps.getByRole('link', { name: /Warm the cache of web-1044|job-child-78/ }).first()).toHaveAttribute('href', '#/jobs/job-child-78')

    // the designer opens on this very run
    await page.getByRole('button', { name: 'Open in designer' }).click()
    await expect(page).toHaveURL(/#\/workflows\/create-vm\?job=job-9b2e4f10$/)
  })

  test('the replay compares with the run you pick, and says when that job does not exist', async ({ page }) => {
    const api = await mockApi(page, routes)
    await page.goto('/#/jobs/job-9b2e4f10')
    await page.getByRole('tab', { name: /Time travel/ }).click()
    const compare = page.getByRole('button', { name: 'Compare with' })
    await expect(compare).toContainText('Last successful run')

    await compare.click()
    await page.getByRole('option', { name: /job-gone|gone/ }).click()
    await page.getByRole('button', { name: 'Replay', exact: true }).click()
    await expect(page.getByRole('alert').filter({ hasText: 'There is no job job-gone to compare with.' })).toBeVisible()
    await expect(page.getByTestId('replay-comparison')).toHaveCount(0)

    await compare.click()
    await page.getByRole('option', { name: /2c44a810/ }).click()
    await page.getByRole('button', { name: 'Replay', exact: true }).click()
    const comparison = page.getByTestId('replay-comparison')
    await expect(comparison).toBeVisible()
    await expect(comparison.getByRole('link', { name: /2c44a810/ })).toHaveAttribute('href', '#/jobs/job-2c44a810')
    await expect(comparison).toContainText('2.0.0')
    await expect(comparison).toContainText('succeeded')
    await expect(comparison).toContainText('1 input field and 1 step differ.')
    const input = page.getByRole('table', { name: 'Differences in the input' })
    await expect(input.getByRole('row').nth(1)).toContainText('input.name')
    await expect(input.getByRole('row').nth(1)).toContainText('web-1043')
    await expect(input.getByRole('row').nth(1)).toContainText('web-1044')
    const clone = page.getByRole('table', { name: 'Differences at clone' })
    await expect(clone.getByRole('columnheader').nth(1)).toHaveText('Compared run')
    await expect(clone.getByRole('columnheader').nth(2)).toHaveText('This run')
    await expect(clone).toContainText('esx-02')
    await expect(clone).toContainText('esx-07')
    expect(api.calls.some((c) => c.path === '/jobs/job-9b2e4f10/replay' && c.query === '?compareTo=job-2c44a810')).toBe(true)
    // the existing actions stay
    await expect(page.getByRole('button', { name: /Re-run from/ })).toBeVisible()
  })

  test('re-running from a step sends the step and the fields you change, then opens the new job', async ({ page }) => {
    const api = await mockApi(page, {
      ...routes,
      'POST /jobs/{id}:rerun': { id: 'job-rerun-1', status: 'queued' },
    })
    await page.goto('/#/jobs/job-9b2e4f10')
    await page.getByRole('tab', { name: /Time travel/ }).click()
    await expect(page.getByRole('button', { name: 'Re-run from image.create' })).toBeEnabled()
    await page.getByRole('button', { name: 'From step' }).click()
    await page.getByRole('option', { name: /^clone/ }).click()

    // a list is not an input: the button waits for an object
    const box = page.locator('#rerun-input')
    await box.fill('[{ "name": "web-1045" }]')
    await expect(page.getByText('Expected a JSON object')).toBeVisible()
    await expect(page.getByRole('button', { name: 'Re-run from clone' })).toBeDisabled()
    await box.fill('{ "name": "web-1045" }')
    await page.getByRole('button', { name: 'Re-run from clone' }).click()
    const dialog = page.getByRole('dialog', { name: 'Re-run from clone?' })
    await expect(dialog).toContainText("on this job's input with your changes")
    await dialog.getByRole('button', { name: 'Start the re-run' }).click()
    await expect(page).toHaveURL(/#\/jobs\/job-rerun-1$/)
    expect(api.callsTo('POST', '/jobs/job-9b2e4f10:rerun').map((c) => c.body)).toEqual([{ fromStep: 'clone', input: { name: 'web-1045' } }])
  })

  test('a job still running says it can be re-run once it ends, instead of a form the backend refuses', async ({ page }) => {
    await mockApi(page, { ...routes, 'GET /jobs/{id}': { ...BUILD_JOB, status: 'running', endedAt: undefined, durationMs: undefined } })
    await page.goto('/#/jobs/job-9b2e4f10')
    await page.getByRole('tab', { name: /Time travel/ }).click()
    await expect(page.getByTestId('rerun-unavailable')).toHaveText('A job can be re-run once it has ended.')
    await expect(page.getByRole('button', { name: /Re-run from/ })).toHaveCount(0)
  })
})

test.describe('job: a stopped batch', () => {
  test('retrying runs the failed and skipped rows again in a new wave, and the panel follows', async ({ page }) => {
    let retried = false
    // the job's stream ends after its last event, and asks a browser that reconnects to do so at once
    const finished = { seq: 1, at: BATCH_JOB.endedAt, jobId: BATCH_JOB.id, type: 'job', data: { jobId: BATCH_JOB.id, event: 'job.finished', status: 'failed' } }
    const api = await mockApi(page, {
      'GET /jobs/{id}': () => (retried ? BATCH_RETRIED : BATCH_JOB),
      'GET /jobs/{id}/events': sse([{ id: 1, event: 'job', data: finished }], 100),
      'POST /jobs/{id}:retry-failed-rows': () => {
        retried = true
        return reply(202, { id: BATCH_JOB.id, status: 'queued' })
      },
      'GET /workflows': WORKFLOWS,
    })
    await page.goto('/#/jobs/job-b4d2e9a1')
    // a batch opens on its rows
    await expect(page.getByRole('tab', { name: /Batch/ })).toHaveAttribute('aria-selected', 'true')
    // the stream ended with the job: nothing reconnects to replay it
    const streams = () => api.callsTo('GET', '/jobs/job-b4d2e9a1/events').length
    await expect.poll(streams).toBeGreaterThan(0)
    await page.waitForTimeout(300)
    const read = streams()
    await page.waitForTimeout(1000)
    expect(streams()).toBe(read)
    await expect(page.getByRole('alert').filter({ hasText: 'Stopped: stopped after wave 1: 2 of 3 rows failed (more than 20%)' })).toBeVisible()
    const rows = page.getByRole('table', { name: 'Batch rows' })
    await expect(rows).toContainText('package_conflict at apply')
    await expect(rows.getByRole('link', { name: 'job-b4d2-r1' })).toHaveAttribute('href', '#/jobs/job-b4d2-r1')

    // the backend retries the failed, cancelled and skipped rows: two failed and three skipped here
    await expect(page.getByText('2 failed, 3 skipped: they run again in a new wave')).toBeVisible()
    await page.getByRole('button', { name: 'Retry 5 rows' }).click()
    await expect(page.getByText('Retrying the rows')).toBeVisible()
    expect(api.callsTo('POST', '/jobs/job-b4d2e9a1:retry-failed-rows')).toHaveLength(1)

    // the job reads again: queued, a fourth wave pending, nothing left to retry
    await expect(page.getByText('Wave 4 · 100%')).toBeVisible()
    await expect(page.getByText('5 rows · pending')).toBeVisible()
    await expect(page.getByRole('button', { name: /^Retry/ })).toHaveCount(0)
    await expect(page.getByRole('alert').filter({ hasText: 'Stopped:' })).toHaveCount(0)
    await expect(rows).not.toContainText('package_conflict at apply')
    // running again, the job is listened to again
    await expect.poll(streams).toBeGreaterThan(read)
  })

  test('a batch still running offers no retry, even with a failed row', async ({ page }) => {
    const running = { ...BATCH_JOB, status: 'running' as const, endedAt: undefined, durationMs: undefined, batch: { ...BATCH_JOB.batch!, waves: [BATCH_JOB.batch!.waves[0], { ...BATCH_JOB.batch!.waves[1], status: 'running', endedAt: undefined }, { ...BATCH_JOB.batch!.waves[2], status: 'pending', endedAt: undefined }], stoppedReason: undefined } }
    await mockApi(page, { 'GET /jobs/{id}': running, 'GET /jobs/{id}/events': sse([]), 'GET /workflows': WORKFLOWS })
    await page.goto('/#/jobs/job-b4d2e9a1')
    await expect(page.getByRole('table', { name: 'Batch rows' })).toContainText('package_conflict at apply')
    await expect(page.getByRole('button', { name: /^Retry/ })).toHaveCount(0)
  })
})

test.describe('change request: the publish checks', () => {
  test('a failed contracts check lists every consumer it reaches, and the playbooks check its pin', async ({ page }) => {
    await mockApi(page, { 'GET /change-requests/{id}': WORKFLOW_CR, 'GET /workflows': WORKFLOWS })
    await page.goto('/#/change-requests/cr-202')
    const checks = page.getByRole('table', { name: 'Checks' })
    const contracts = checks.getByRole('row', { name: /Contracts/ })
    await expect(contracts).toContainText('failed')
    await expect(contracts).toContainText('2.0.0 breaks the contract of 1.2.0 without a new major version: input.reason became required.')
    await expect(contracts).not.toContainText('Affected consumers:')
    await expect(contracts.getByRole('list', { name: 'A breaking change reaches' }).getByRole('listitem')).toHaveText(['web-environment', 'create-vm'])
    const playbooks = checks.getByRole('row', { name: /Playbooks/ })
    await expect(playbooks).toContainText('1 playbook(s) pinned to a commit, 1 linted')
    await expect(playbooks.getByRole('list', { name: 'Pinned at publish' }).getByRole('listitem')).toHaveText(['restart to 9fceb02d0ae5'])
    const replay = checks.getByRole('row', { name: /Replay of recent jobs/ })
    await expect(replay).toContainText('replayed: 42')
    await expect(replay).not.toContainText('{"replayed"')
    await expect(page.getByRole('button', { name: 'Merge' })).toBeDisabled()
  })
})

test.describe('change request: the candidate on the canvas', () => {
  test("a workflow's change request opens on the canvas, the candidate as its author designed it", async ({ page }) => {
    const api = await mockApi(page, {
      'GET /change-requests/{id}': WORKFLOW_CR,
      'GET /workflows': WORKFLOWS,
      'POST /workflows:parse': { definition: WORKFLOW_CR_DEFINITION, problems: [] },
      'GET /registry/providers': [],
    })
    await page.goto('/#/change-requests/cr-202')
    const tabs = page.getByRole('tablist', { name: 'Content' })
    await expect(tabs.getByRole('tab')).toHaveText(['Canvas', 'Diff', 'YAML'])
    await expect(tabs.getByRole('tab', { name: 'Canvas' })).toHaveAttribute('aria-selected', 'true')

    const canvas = page.getByTestId('candidate-canvas')
    await expect(canvas.getByRole('region', { name: 'vm-restart 2.0.0 on the canvas' })).toBeVisible()
    await expect(canvas.locator('.react-flow__node[data-id="step:lookup"]')).toContainText('Look up the VM')
    await expect(canvas.locator('.react-flow__node[data-id="step:restart"]')).toContainText('Restart the VM')
    expect(api.callsTo('POST', '/workflows:parse')[0].body).toEqual({ yaml: WORKFLOW_CR.yaml })
    // read only: nothing to tidy, nothing to drag
    await expect(canvas.getByRole('button', { name: /Tidy up/ })).toHaveCount(0)

    // the flowchart GitHub draws in the pull request, as its source behind a disclosure
    const source = page.getByTestId('diagram-source')
    await expect(source.locator('pre')).toBeHidden()
    await source.getByText('Diagram source').click()
    await expect(source.locator('pre')).toContainText('flowchart LR')
    await expect(source.locator('pre')).toContainText('s2["Restart the VM<br/>provider: vsphere vm.restart"]')
    await expect(source.getByRole('button', { name: 'Copy' })).toBeVisible()
    await expect(source.getByRole('link', { name: 'the pull request' })).toHaveAttribute('href', WORKFLOW_CR.url!)

    // a step opens as written
    await canvas.locator('.react-flow__node[data-id="step:restart"]').dblclick()
    const step = page.getByRole('dialog', { name: 'Step restart' })
    await expect(step).toContainText('Restart the VM')
    await expect(step).toContainText('vm.restart')
    await page.keyboard.press('Escape')
    await expect(step).toBeHidden()

    // the diff and the file are a tab away
    await tabs.getByRole('tab', { name: 'Diff' }).click()
    await expect(page.getByText('version: 2.0.0').first()).toBeVisible()
    await tabs.getByRole('tab', { name: 'YAML' }).click()
    await expect(page.locator('pre').first()).toContainText('name: vm-restart')
  })

  test('a candidate whose YAML does not parse says so, and a provider has no canvas', async ({ page }) => {
    await mockApi(page, {
      'GET /change-requests/{id}': (req) => (req.params.id === 'cr-101' ? PROVIDER_CR : WORKFLOW_CR),
      'GET /workflows': WORKFLOWS,
      'POST /workflows:parse': { definition: null, problems: [{ level: 'error', code: 'yaml.invalid', message: 'mapping values are not allowed here (line 4)' }] },
    })
    await page.goto('/#/change-requests/cr-202')
    await expect(page.getByTestId('candidate-unparsed')).toContainText('The YAML does not parse, so there is no canvas to draw')
    await expect(page.getByTestId('candidate-unparsed')).toContainText('mapping values are not allowed here (line 4)')

    await page.goto('/#/change-requests/cr-101')
    const tabs = page.getByRole('tablist', { name: 'Content' })
    await expect(tabs.getByRole('tab')).toHaveText(['Diff', 'YAML'])
    await expect(tabs.getByRole('tab', { name: 'Diff' })).toHaveAttribute('aria-selected', 'true')
    // a provider's change request has no flowchart
    await expect(page.getByTestId('diagram-source')).toHaveCount(0)
  })
})

test.describe('workflow versions: the steps of a version', () => {
  test('run steps show the workflow they run and its version range', async ({ page }) => {
    await mockApi(page, {
      'GET /workflows/{name}': { ...WORKFLOWS[1], yaml: CREATE_VM_YAML, definition: CREATE_VM_DEFINITION, versions: CREATE_VM_VERSIONS, problems: [], changeRequests: [] },
      'GET /workflows/{name}/versions': CREATE_VM_VERSIONS,
      'GET /workflows/{name}/versions/{version}': (req: { params: Record<string, string> }) => ({ version: req.params.version, yaml: CREATE_VM_YAML, definition: CREATE_VM_DEFINITION }),
    })
    await page.goto('/#/workflows/create-vm/versions')
    // the version a release rollback took out says so
    const table = page.getByRole('table', { name: 'Versions of Create a VM' })
    await expect(table.getByRole('row', { name: /2\.1\.0/ })).toContainText('rolled back')
    await expect(table.getByRole('row', { name: /2\.0\.0/ })).not.toContainText('rolled back')
    await table.getByRole('row', { name: /2\.0\.0/ }).click()
    const drawer = page.getByRole('dialog', { name: 'Version 2.0.0' })
    await expect(drawer).toBeVisible()
    await drawer.getByRole('radio', { name: 'Steps' }).click()
    const steps = drawer.getByRole('list', { name: 'Steps' })
    await expect(steps.getByRole('listitem')).toHaveCount(5)
    const build = steps.getByRole('listitem').filter({ hasText: 'Build the image' })
    await expect(build).toContainText('runs')
    await expect(build.getByRole('link', { name: 'build-vm-image' })).toHaveAttribute('href', '#/workflows/build-vm-image')
    await expect(build).toContainText('^1')
    await expect(steps.getByRole('listitem').filter({ hasText: 'Wire the environment' })).toContainText('any version')
    await expect(steps.getByRole('listitem').filter({ hasText: 'Attach the disk' })).toContainText('~1.4')
    await expect(steps.getByRole('listitem').filter({ hasText: 'Clone from the image' })).not.toContainText('runs')
  })
})
