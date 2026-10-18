// The onboarding journey in pictures: a junior engineer adds a VM provider from its OpenAPI spec,
// a reviewer merges it, the junior builds a Create VM workflow on it and runs it. Every screen is
// the real console on a running stack; the VM API is fake-vm-api.cjs, called over HTTP.
// Usage: node journey-vm-provider.cjs [frontend base] [api base] [fake VM API base] [out dir]
const fs = require('fs')
const path = require('path')
const { chromium, expect } = require('@playwright/test')

const BASE = process.argv[2] || 'http://localhost:3000'
const API = process.argv[3] || 'http://localhost:8080/api/v1'
const VM_API = process.argv[4] || 'http://localhost:18090'
const OUT = process.argv[5] || path.join(__dirname, '..', '..', 'docs', 'screenshots', 'journey')
const TOKEN = process.env.FAKE_VM_TOKEN || 'journey-demo-token'
const ONLY = process.env.JOURNEY_UNTIL || 'all'

const shots = []
let n = 0

let failurePage = null

async function main() {
  fs.mkdirSync(OUT, { recursive: true })
  const browser = await chromium.launch()
  const ctx = await browser.newContext({ viewport: { width: 1440, height: 900 }, colorScheme: 'light', deviceScaleFactor: 1 })
  const page = await ctx.newPage()
  failurePage = page
  page.on('pageerror', (e) => console.log('pageerror', e.message))
  // the console's dev identity (Settings, Act as) is kept in localStorage and read once per load
  const as = async (user) => {
    await page.evaluate((u) => localStorage.setItem('iaf-user', u), user)
    await page.reload()
    await page.waitForLoadState('networkidle', { timeout: 8000 }).catch(() => {})
  }
  const go = async (route) => {
    await page.goto(`${BASE}/#${route}`)
    await page.waitForLoadState('networkidle', { timeout: 8000 }).catch(() => {})
  }
  const clearToasts = async () => {
    const dismiss = page.locator('[aria-label="Notifications"] button[aria-label="Dismiss"]')
    for (let i = 0; i < 6 && (await dismiss.count()) > 0; i++) await dismiss.first().click().catch(() => {})
  }
  const shot = async (slug, caption, target) => {
    await clearToasts()
    n += 1
    const name = `${String(n).padStart(2, '0')}-${slug}`
    if (target) await target.scrollIntoViewIfNeeded().catch(() => {})
    await page.waitForTimeout(400)
    await page.screenshot({ path: path.join(OUT, `${name}.png`) })
    shots.push({ name, caption })
    console.log(`shot ${name}`)
  }
  const current = () => page.getByRole('list', { name: 'Steps' }).locator('[aria-current="step"]')

  await go('/providers')
  await as('junior')

  // ------------------------------------------------------------ 1 · the wizard: type
  await shot('providers', 'The junior engineer opens Providers: the systems workflows may call. Add provider starts the wizard.')
  await page.getByRole('link', { name: 'Add provider' }).or(page.getByRole('button', { name: 'Add provider' })).first().click()
  await expect(current()).toContainText('Type')
  await page.getByRole('radio', { name: 'OpenAPI' }).click()
  await page.getByRole('textbox', { name: 'Title', exact: true }).fill('Acme Cloud')
  await expect(page.getByRole('textbox', { name: 'Name', exact: true })).toHaveValue('acme-cloud')
  await page.getByLabel('Description').fill('Virtual machines in Acme Cloud: create, read and delete.')
  await shot('wizard-type', 'Type: the VM API publishes an OpenAPI document, so the provider is OpenAPI. The name is what workflows call it by; the owner is the junior engineer’s team.')
  await page.getByRole('button', { name: 'Next' }).click()

  // ------------------------------------------------------------ 2 · connect
  await expect(current()).toContainText('Connect')
  await page.getByRole('textbox', { name: 'Spec URL' }).fill(`${VM_API}/openapi.json`)
  await page.getByRole('button', { name: 'Import spec' }).click()
  await expect(page.getByText(/4 operations from Acme Cloud 1\.4\.0/)).toBeVisible({ timeout: 15000 })
  // the credential: stored once, referenced by name
  await page.getByRole('button', { name: 'New', exact: true }).click()
  const modal = page.getByRole('dialog', { name: 'Add credential' })
  await modal.getByLabel('Name').fill('acme-cloud/token')
  await modal.locator('#cred-value').fill(TOKEN)
  await shot('credential', 'Connect: the API token is stored once as a credential. The provider names it as vault:acme-cloud/token; nobody can read the value back.')
  await modal.getByRole('button', { name: 'Store credential' }).click()
  await expect(modal).toHaveCount(0)
  await page.getByRole('button', { name: 'Test connection' }).click()
  await expect(page.getByRole('list', { name: 'Connection results' })).toBeVisible({ timeout: 20000 })
  await page.waitForTimeout(500)
  await shot('connect', 'Connect: the spec is imported from its URL, each environment’s base URL comes from it, and Test connection probes dev and prod with the token. The result is kept as review evidence.', page.getByRole('list', { name: 'Connection results' }))
  await page.getByRole('button', { name: 'Next' }).click()

  // ------------------------------------------------------------ 3 · actions
  await expect(current()).toContainText('Actions')
  const ops = page.getByRole('table', { name: 'Operations' })
  for (const [op, id] of [['listVms', 'vm.list'], ['createVm', 'vm.create'], ['getVm', 'vm.get'], ['deleteVm', 'vm.delete']]) {
    await ops.getByRole('textbox', { name: `Id of ${op}` }).fill(id)
  }
  // a delete is previewed before a run: vm.get reads the VM it would remove
  await ops.getByRole('button', { name: 'Plan step of deleteVm', exact: true }).click()
  await page.getByRole('option', { name: 'vm.get', exact: true }).click()
  await shot('actions', 'Actions: each operation becomes an action. Effect and retry come from the HTTP method, and a delete needs an approval. The ids are renamed vm.create, vm.get and so on, and vm.get previews vm.delete.', ops)
  const errors = page.getByTestId('error-classes')
  await errors.getByRole('button', { name: 'Start from the usual ones' }).click()
  await shot('errors', 'Actions: error classes say which failures are retried. Start from the usual ones adds not_found, conflict, rate_limited and unavailable.', errors)
  await page.getByRole('button', { name: 'Next' }).click()

  // ------------------------------------------------------------ 4 · try it
  await expect(current()).toContainText('Try it')
  const pick = async (label, option) => {
    await page.getByRole('button', { name: label, exact: true }).click()
    await page.getByRole('option', { name: option }).first().click()
  }
  // one try: the answer arrives with the response of POST /providers:try
  const tryIt = async (confirmTitle, before) => {
    const tried = page.waitForResponse((r) => r.url().includes('/providers:try') && r.request().method() === 'POST', { timeout: 30000 })
    await page.getByRole('button', { name: 'Try it', exact: true }).click()
    if (confirmTitle) {
      const dialog = page.getByRole('dialog', { name: confirmTitle })
      await expect(dialog).toBeVisible()
      if (before) await before(dialog)
      await dialog.getByRole('button', { name: 'Run it' }).click()
    }
    const body = await (await tried).json()
    await page.waitForTimeout(300)
    return body
  }
  await pick('Action', /^vm\.create/)
  await page.locator('#wtry-name').fill('try-vm-01')
  await page.locator('#wtry-image').fill('ubuntu-24.04')
  const made = await tryIt('Run vm.create in dev?', (dialog) =>
    shot('try-confirm', 'Try it: one real call in dev, with a form generated from the operation. A create changes something, so the wizard asks first.', dialog),
  )
  const vmId = made.response && made.response.id
  if (!vmId) throw new Error('the create try made no VM: ' + JSON.stringify(made).slice(0, 300))
  await page.getByRole('button', { name: 'Record as sample' }).click()
  await shot('try-create', 'Try it: the API created the VM and its answer matches the schema. The answer is kept as review evidence and recorded as the sample that dry runs answer with.', page.getByRole('button', { name: 'Sample recorded' }))
  // the read and the list, so every action has been tried
  await pick('Action', /^vm\.get/)
  await page.locator('#wtry-id').fill(vmId)
  await tryIt()
  await page.getByRole('button', { name: 'Record as sample' }).click()
  await pick('Action', /^vm\.list/)
  await tryIt()
  await page.getByRole('button', { name: 'Record as sample' }).click()
  // the delete takes the test VM away again, and is checked gone afterwards
  await pick('Action', /^vm\.delete/)
  await page.locator('#wtry-id').fill(vmId)
  const gone = await tryIt('Run vm.delete in dev?')
  console.log('delete try ->', JSON.stringify(gone).slice(0, 300))
  await shot('try-delete', 'Try it: the delete removes the test VM again, so dev is left as it was.', page.getByTestId('try-evidence'))
  await page.getByRole('button', { name: 'Next' }).click()

  // ------------------------------------------------------------ 5 · review
  await expect(current()).toContainText('Review')
  await page.locator('#pub-channel').fill('#compute-alerts')
  await page.getByRole('button', { name: 'Run checks' }).click()
  await expect(page.getByRole('list', { name: 'Checks', exact: true })).toBeVisible({ timeout: 20000 })
  await shot('review-checks', 'Review: the provider file the wizard wrote, and the checks the change request will run again. Plain http is allowed only because the API is on this machine.', page.getByRole('list', { name: 'Checks', exact: true }))
  await shot('review-evidence', 'Review: the automated review of what happened in the wizard: spec pinned, connection tested, every action tried in dev, errors classified, an owner and an alert channel.', page.getByTestId('review-evidence'))
  const publishing = page.waitForResponse((r) => r.url().includes('/providers:publish'), { timeout: 30000 })
  await page.getByRole('button', { name: 'Publish as change request' }).click()
  const published = await publishing
  console.log('publish ->', published.status(), (await published.text()).slice(0, 400))
  await page.waitForURL(/#\/change-requests\//, { timeout: 30000 })
  const crRoute = new URL(page.url()).hash.slice(1)
  await page.waitForLoadState('networkidle', { timeout: 8000 }).catch(() => {})
  await page.waitForTimeout(800)
  await shot('provider-cr', 'The change request: the new provider file, the checks and the review evidence the wizard kept. The author cannot merge it.')

  // ------------------------------------------------------------ the reviewer merges it
  await as('gonzalo')
  await go(crRoute)
  await page.waitForTimeout(800)
  const merge = page.getByRole('button', { name: 'Merge', exact: true })
  await expect(merge).toBeEnabled({ timeout: 20000 })
  await shot('provider-cr-review', 'A reviewer from the owner team opens the same change request, reads the file and the evidence, and merges.', merge)
  await merge.click()
  const confirmMerge = page.getByRole('dialog').filter({ hasText: /Merge/ })
  await confirmMerge.getByRole('button', { name: /Merge/ }).last().click()
  await page.waitForTimeout(1500)
  await as('junior')
  await go('/providers/acme-cloud')
  await page.waitForTimeout(1000)
  await shot('provider-page', 'Acme Cloud is published: its actions, the SLO it is held to, and the health the platform computes from every call workflows make.')
  if (ONLY === 'provider') return finish(browser)

  // ------------------------------------------------------------ the workflow: from the provider's create operation
  await go('/workflows')
  await page.getByRole('button', { name: 'New workflow' }).first().click()
  const dialog = page.getByRole('dialog', { name: 'New workflow' })
  await dialog.getByRole('button', { name: /^Import/ }).click()
  await dialog.getByRole('radio', { name: 'OpenAPI provider' }).click()
  await dialog.getByRole('button', { name: 'Reference' }).click()
  await page.getByRole('option', { name: /Acme Cloud|acme-cloud/ }).first().click()
  await dialog.getByRole('button', { name: 'Import', exact: true }).click()
  await expect(dialog.getByText('Imported workflow')).toBeVisible({ timeout: 15000 })
  await shot('workflow-import', 'Workflows, New workflow, Import: the provider’s create operation becomes a draft workflow, with its form taken from the operation, a create step and a step that registers the VM.', dialog)
  await dialog.getByRole('button', { name: 'Open in designer' }).click()
  await page.waitForURL(/#\/workflows\/[^/?]+$/, { timeout: 20000 })
  const workflowRoute = new URL(page.url()).hash.slice(1)
  const workflowName = workflowRoute.split('/').pop()
  console.log('workflow', workflowName)
  const node = (id) => page.locator(`.react-flow__node[data-id="${id}"]`)
  await expect(node('step:create')).toBeVisible({ timeout: 20000 })
  await page.waitForTimeout(800)
  await shot('designer', 'The designer: the draft on the n8n-style canvas. The API trigger is the generated endpoint; the steps call vm.create and register what it made.')
  await node('step:create').dblclick()
  const ndv = page.getByTestId('ndv')
  await expect(ndv).toBeVisible()
  await shot('designer-step', 'A step’s parameters: the provider action and its input, mapped from the workflow’s input with {{ }} expressions. Fields can be dragged in from the left.', ndv)
  await page.keyboard.press('Escape')
  await page.getByTestId('test-workflow').click()
  const test = page.getByRole('dialog', { name: 'Test workflow' })
  await expect(test).toBeVisible()
  const inputs = test.locator('input[id]')
  console.log('test inputs', await inputs.evaluateAll((els) => els.map((e) => e.id)))
  for (const [field, value] of [['name', 'web-07'], ['image', 'ubuntu-24.04']]) {
    const box = test.locator(`[id$="-${field}"]`).first()
    if (await box.count()) await box.fill(value)
  }
  await test.getByRole('button', { name: /Test workflow/ }).click()
  await expect(page.getByText(/Test passed|Test failed/).first()).toBeVisible({ timeout: 30000 })
  await page.waitForTimeout(800)
  await shot('designer-test', 'Test workflow: a dry run with the samples recorded in the wizard. Nothing is created; every step shows what it would send and get back.')
  if (ONLY === 'designer') return finish(browser)

  // the title the catalog shows, next to the vSphere Create VM
  await page.getByRole('tab', { name: 'Settings' }).click()
  await page.getByTestId('settings-view').getByRole('region', { name: 'Workflow' }).getByRole('textbox', { name: /^Title/ }).fill('Create an Acme Cloud VM')
  await page.getByRole('tab', { name: 'Editor' }).click()
  await page.getByTestId('save-button').click()
  await expect(page.getByTestId('save-button')).toHaveText(/Saved/, { timeout: 15000 })

  // ------------------------------------------------------------ publish: a change request a teammate merges
  await page.getByRole('button', { name: 'Publish', exact: true }).click()
  const publishingWorkflow = page.waitForResponse((r) => r.url().includes(`/workflows/${workflowName}:publish`), { timeout: 30000 })
  await page.getByTestId('publish-confirm').click()
  const workflowCr = (await (await publishingWorkflow).json()).id
  console.log('workflow change request', workflowCr)
  await expect(page.getByTestId('cr-merge')).toHaveText('A teammate merges it', { timeout: 30000 })
  await shot('workflow-cr', 'Publish opens a change request with the workflow’s checks: schema, policy, a replay of recent jobs, the contracts with other workflows and the routes. The author waits for a teammate.', page.getByTestId('cr-checks'))
  await as('gonzalo')
  await go(`/change-requests/${workflowCr}`)
  const mergeWorkflow = page.getByRole('button', { name: 'Merge', exact: true })
  await expect(mergeWorkflow).toBeEnabled({ timeout: 20000 })
  await mergeWorkflow.click()
  await page.getByRole('dialog').getByRole('button', { name: 'Merge and publish' }).click()
  await expect(page.getByText('Merged', { exact: true }).first()).toBeVisible({ timeout: 20000 })
  await page.waitForTimeout(800)
  await shot('workflow-merged', 'The reviewer merges it. Version 0.1.0 is published: it has its endpoint, its MCP tool and a run form in the catalog.')

  // ------------------------------------------------------------ run it
  await as('junior')
  await go(`/catalog/${workflowName}`)
  const field = (name) => page.locator(`#run-${workflowName}-${name}`)
  await expect(field('name')).toBeVisible({ timeout: 20000 })
  await field('name').fill('web-07')
  await field('image').fill('ubuntu-24.04')
  await field('cpu').fill('4')
  await field('memoryGb').fill('16')
  await page.getByRole('button', { name: 'Preview', exact: true }).click()
  const preview = page.getByTestId('preview-card')
  await expect(preview).toBeVisible({ timeout: 20000 })
  await shot('run-preview', 'The catalog’s run form, generated from the workflow’s input. Preview shows the blast radius before anything runs: the steps, what each one changes and the policy decision.', preview)
  await page.getByRole('button', { name: 'Run', exact: true }).click()
  await page.waitForURL(/#\/jobs\//, { timeout: 30000 })
  const jobRoute = new URL(page.url()).hash.slice(1)
  const jobId = jobRoute.split('/').pop()
  let job = {}
  for (let i = 0; i < 60 && !['succeeded', 'failed', 'cancelled'].includes(job.status); i++) {
    if (i) await page.waitForTimeout(1000)
    job = await (await fetch(`${API}/jobs/${jobId}`, { headers: { 'X-User': 'junior' } })).json()
  }
  if (job.status !== 'succeeded') throw new Error(`the job ended ${job.status}`)
  await page.waitForTimeout(1500)
  await shot('job', 'The job: both steps succeeded. Create a VM called the Acme Cloud API for real, and Register put the VM it made in the registry. Each step opens to show its input and output.')
  console.log('job', jobRoute)
  const vms = await (await fetch(`${VM_API}/dev/vms`, { headers: { Authorization: `Bearer ${TOKEN}` } })).json()
  console.log('the VM API now has', JSON.stringify(vms))

  // ------------------------------------------------------------ the VM in the registry
  await go('/resources')
  await page.waitForTimeout(800)
  const row = page.getByRole('row', { name: /web-07/ }).first()
  await expect(row).toBeVisible({ timeout: 20000 })
  await shot('resources', 'Resources: the new VM is in the registry, owned by the junior engineer’s team, where other workflows can find it.', row)
  await row.click()
  await page.waitForTimeout(1200)
  await shot('resource', 'The VM’s page: its attributes, the workflow and job that created it, its audit record and its lineage. A delete or another workflow picks it from here.')
  return finish(browser)
}

async function finish(browser) {
  await browser.close()
  fs.writeFileSync(path.join(OUT, 'captions.json'), JSON.stringify(shots, null, 2) + '\n')
  console.log(`${shots.length} screenshots in ${OUT}`)
}

main().catch(async (e) => {
  console.error(e)
  if (failurePage) await failurePage.screenshot({ path: path.join(OUT, 'failure.png') }).catch(() => {})
  process.exit(1)
})
