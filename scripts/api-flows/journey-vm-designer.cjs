// Create VM end to end in the designer, in pictures: a junior engineer builds the workflow on a
// blank canvas with two HTTP steps (no provider), stores the API token from the step's credential
// field, checks, tests and publishes it; a reviewer merges the change request; the junior runs it.
// Every screen is the real console on a running stack; the VM API is fake-vm-api.cjs over HTTP.
// Usage: node journey-vm-designer.cjs [frontend base] [api base] [fake VM API base] [out dir]
const fs = require('fs')
const path = require('path')
const { chromium, expect } = require('@playwright/test')

const BASE = process.argv[2] || 'http://localhost:3000'
const API = process.argv[3] || 'http://localhost:8080/api/v1'
const VM_API = process.argv[4] || 'http://localhost:18090'
const OUT = process.argv[5] || path.join(__dirname, '..', '..', 'docs', 'screenshots', 'designer-journey')
const TOKEN = process.env.FAKE_VM_TOKEN || 'example-token'
const ONLY = process.env.JOURNEY_UNTIL || 'all'
const NAME = process.env.JOURNEY_WORKFLOW || 'create-cloud-vm'
const NOUN = process.env.JOURNEY_NOUN || 'cloud-vm'

const shots = []
let n = 0
let failurePage = null

async function main() {
  fs.mkdirSync(OUT, { recursive: true })
  const browser = await chromium.launch()
  const ctx = await browser.newContext({ viewport: { width: 1440, height: 900 }, colorScheme: 'light', deviceScaleFactor: 1 })
  // the backend's CORS list names the usual dev ports (3000, 5173); the Vite proxy passes the
  // browser's Origin through, so a console on another port drops it to be served like curl
  await ctx.route('**/api/**', (route) => {
    const headers = { ...route.request().headers() }
    delete headers.origin
    return route.continue({ headers })
  })
  const page = await ctx.newPage()
  failurePage = page
  page.on('pageerror', (e) => console.log('pageerror', e.message))
  page.on('response', async (r) => {
    if (r.url().includes('/api/') && r.status() >= 400) console.log('http', r.status(), r.request().method(), r.url(), (await r.text().catch(() => '')).slice(0, 300))
  })
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
  // scrolling a settings section into view slides the whole designer sideways: put it back
  const unslide = () => page.evaluate(() => document.querySelectorAll('*').forEach((e) => e.scrollLeft && (e.scrollLeft = 0)))
  const node = (id) => page.locator(`.react-flow__node[data-id="${id}"]`)
  const ndv = page.getByTestId('ndv')
  const save = async () => {
    await page.getByTestId('save-button').click()
    await expect(page.getByTestId('save-button')).toHaveText(/Saved/, { timeout: 15000 })
    // the saved toast goes now: dismissing it later would count as a click outside an open panel
    await page.waitForTimeout(600)
    await clearToasts()
  }

  await go('/workflows')
  await as('junior')

  // ------------------------------------------------------------ 1 · a blank workflow
  await shot('workflows', 'The junior engineer opens Workflows: everything the platform can run. New workflow starts one.')
  await page.getByRole('button', { name: 'New workflow' }).first().click()
  const dialog = page.getByRole('dialog', { name: 'New workflow' })
  await dialog.getByRole('button', { name: /^Blank/ }).click()
  await dialog.locator('#nw-title').fill('Create VM')
  await dialog.locator('#nw-name').fill(NAME)
  await dialog.locator('#nw-noun').fill(NOUN)
  await dialog.locator('#nw-verb').fill('create')
  await dialog.locator('#nw-owner').fill('team-compute')
  await dialog.locator('#nw-folder').fill('Compute')
  await shot('new-blank', 'New workflow, Blank: a title, the name the file and the URL use, a noun and a verb that name its endpoint, and the owner team.', dialog)
  await dialog.getByRole('button', { name: 'Create and open' }).click()
  await page.waitForURL(new RegExp(`#/workflows/${NAME}$`), { timeout: 20000 })
  await expect(node('trigger:api')).toBeVisible({ timeout: 20000 })
  await page.waitForTimeout(800)
  await shot('canvas', 'The designer opens on the n8n-style canvas: the API trigger every workflow has, and a button for the first step.')
  if (ONLY === 'canvas') return finish(browser)

  // ------------------------------------------------------------ 2 · the input the person running it fills in
  await page.getByTestId('designer').getByRole('tab', { name: 'Settings' }).click()
  const settings = page.getByTestId('settings-view')
  await settings.getByRole('navigation', { name: 'Settings sections' }).getByRole('button', { name: 'Input' }).click()
  const schema = settings.getByTestId('input-schema')
  const fields = [
    ['name', 'string', 'VM name', true],
    ['image', 'string', 'Image', true],
    ['cpu', 'integer', 'vCPUs', false],
    ['memoryGb', 'integer', 'Memory (GB)', false],
  ]
  for (const [key, type, title, required] of fields) {
    await schema.getByRole('button', { name: 'Add field' }).click()
    const box = schema.getByRole('textbox', { name: /^Name of field\d+$/ }).last()
    await box.fill(key)
    await box.press('Enter')
    await expect(schema.getByRole('textbox', { name: `Name of ${key}`, exact: true })).toBeVisible()
    if (type !== 'string') {
      await schema.getByRole('button', { name: `Type of ${key}`, exact: true }).click()
      await page.getByRole('option', { name: type, exact: true }).click()
    }
    await schema.getByRole('textbox', { name: `Title of ${key}`, exact: true }).fill(title)
    if (required) {
      const row = schema.locator('div.rounded-md').filter({ has: page.getByRole('textbox', { name: `Name of ${key}`, exact: true }) })
      await row.getByText('Required', { exact: true }).click()
    }
  }
  await page.keyboard.press('Escape')
  await settings.getByRole('navigation', { name: 'Settings sections' }).getByRole('button', { name: 'Input' }).click()
  await page.waitForTimeout(800)
  await unslide()
  await shot('inputs', 'Settings, Input: the fields the person running it fills in. They become the run form, the API body and the MCP tool’s arguments.')
  await page.getByTestId('designer').getByRole('tab', { name: 'Editor' }).click()
  await unslide()
  await save()
  if (ONLY === 'inputs') return finish(browser)

  // ------------------------------------------------------------ 3 · the HTTP step that creates the VM
  await node('placeholder').getByRole('button', { name: 'Add first step' }).dispatchEvent('click')
  const creator = page.getByTestId('node-creator')
  await expect(creator.getByRole('combobox', { name: 'Search steps' })).toBeFocused()
  await page.keyboard.type('HTTP request')
  await expect(creator.getByRole('option').first()).toContainText('HTTP request')
  await shot('node-creator', 'Add first step opens the step list. HTTP request calls any API: no provider is needed.')
  await page.keyboard.press('Enter')
  await expect(ndv.getByRole('textbox', { name: 'Step name' })).toHaveValue('HTTP request')
  await ndv.getByRole('textbox', { name: 'Step name' }).fill('Create VM')
  await ndv.getByRole('textbox', { name: 'Step name' }).press('Enter')
  await ndv.getByRole('button', { name: 'Method', exact: true }).click()
  await page.getByRole('option', { name: 'POST', exact: true }).click()
  await ndv.getByRole('textbox', { name: /^URL/ }).fill(`${VM_API}/dev/vms`)
  await ndv.getByRole('textbox', { name: /^URL/ }).press('Tab')
  // the body: the input mapped field by field, each a {{ }} expression
  const body = ndv.getByRole('textbox', { name: /^Body/ })
  await body.fill('{\n  "name": "{{ $input.name }}",\n  "image": "{{ $input.image }}",\n  "cpu": "{{ $input.cpu }}",\n  "memoryGb": "{{ $input.memoryGb }}"\n}')
  await body.press('Tab')
  await body.evaluate((e) => (e.scrollTop = 0))
  await page.waitForTimeout(300)
  await shot('http-step', 'The step’s parameters: POST to the VM API, with the JSON body mapped from the input with {{ }} expressions. The input’s fields on the left can be dragged onto a parameter.', ndv)
  if (ONLY === 'http') return finish(browser)

  // ------------------------------------------------------------ 4 · the credential, without leaving the canvas
  const credential = ndv.getByRole('button', { name: 'Credential', exact: true })
  await credential.click()
  const entry = ndv.getByRole('listbox', { name: 'Credential' }).getByRole('option').last()
  await expect(entry).toHaveText('New credential…')
  await shot('credential-field', 'The step’s credential field lists the credentials already stored, by name and kind. None is for the VM API yet; the list ends with New credential.', ndv)
  await entry.click()
  const add = page.getByRole('dialog', { name: 'Add credential' })
  await expect(add.getByRole('button', { name: 'Credential type' })).toHaveText('Token')
  await add.getByRole('textbox', { name: 'Name', exact: true }).fill('acme-cloud/api-token')
  await add.getByRole('textbox', { name: 'Value', exact: true }).fill(TOKEN)
  await shot('credential-dialog', 'New credential opens over the canvas: a token for the VM API, stored once under a name. Nobody can read the value back.', add)
  await add.getByRole('button', { name: 'Store credential' }).click()
  await expect(add).toBeHidden()
  await expect(credential).toHaveText('acme-cloud/api-token')
  await shot('credential-set', 'The field now names the credential: the step keeps only the reference vault:acme-cloud/api-token, and the token goes as a bearer header on each call.', ndv)
  await ndv.getByRole('button', { name: 'Back to canvas' }).click()
  await save()
  if (ONLY === 'credential') return finish(browser)

  // ------------------------------------------------------------ 5 · read the VM back, and the outputs
  const first = page.locator('.react-flow__node').filter({ hasText: 'Create VM' }).first()
  await first.click()
  await page.keyboard.press('Tab')
  await expect(creator.getByRole('combobox', { name: 'Search steps' })).toBeFocused()
  await page.keyboard.type('HTTP request')
  await page.keyboard.press('Enter')
  await expect(ndv.getByRole('textbox', { name: 'Step name' })).toBeVisible()
  await ndv.getByRole('textbox', { name: 'Step name' }).fill('Get VM')
  await ndv.getByRole('textbox', { name: 'Step name' }).press('Enter')
  await ndv.getByRole('textbox', { name: /^URL/ }).fill(`${VM_API}/dev/vms/{{ $json.body.id }}`)
  await ndv.getByRole('textbox', { name: /^URL/ }).press('Tab')
  await ndv.getByRole('button', { name: 'Credential', exact: true }).click()
  await ndv.getByRole('listbox', { name: 'Credential' }).getByRole('option', { name: /acme-cloud\/api-token/ }).first().click()
  await page.waitForTimeout(300)
  await shot('get-step', 'A second HTTP step reads the VM back: GET with the id the create step answered with ($json is the previous step’s output), and the same credential, now picked from the list.', ndv)
  await ndv.getByRole('button', { name: 'Back to canvas' }).click()
  await expect(ndv).toBeHidden()
  await page.getByTestId('designer').getByRole('tab', { name: 'Settings' }).click()
  await settings.getByRole('navigation', { name: 'Settings sections' }).getByRole('button', { name: 'Outputs' }).click()
  const outputsSection = settings.getByRole('region', { name: 'Outputs' })
  for (const [key, value] of [
    ['vmId', "{{ $('Get VM').json.body.id }}"],
    ['address', "{{ $('Get VM').json.body.ip }}"],
    ['state', "{{ $('Get VM').json.body.state }}"],
  ]) {
    await outputsSection.getByRole('button', { name: 'Add output' }).click()
    const boxes = outputsSection.locator('input, textarea')
    const count = await boxes.count()
    await boxes.nth(count - 2).fill(key)
    await boxes.nth(count - 2).press('Tab')
    const values = outputsSection.locator('input, textarea')
    const last = values.nth((await values.count()) - 1)
    await last.fill(value)
    await last.press('Tab')
  }
  await page.waitForTimeout(300)
  await unslide()
  await shot('outputs', 'Settings, Outputs: what a finished job answers with, as expressions over the steps: the VM’s id, its address and its state, read back by Get VM.')
  await page.getByTestId('designer').getByRole('tab', { name: 'Editor' }).click()
  await unslide()
  await save()
  if (ONLY === 'outputs') return finish(browser)

  // ------------------------------------------------------------ 6 · checks, a test run, the YAML, publish
  await page.getByTestId('validate-button').click()
  await page.waitForTimeout(2500)
  await shot('validated', 'The canvas with both steps. The live checks run as you edit and Validate runs the server’s: no issues.')
  await page.getByTestId('test-workflow').click()
  const test = page.getByRole('dialog', { name: 'Test workflow' })
  await expect(test).toBeVisible()
  for (const [field, value] of [['name', 'web-07'], ['image', 'ubuntu-24.04'], ['cpu', '4'], ['memoryGb', '16']]) {
    const box = test.locator(`[id$="-${field}"]`).first()
    if (await box.count()) await box.fill(value)
  }
  await test.getByRole('button', { name: /Test workflow/ }).click()
  await expect(page.getByText(/Test passed|Test failed/).first()).toBeVisible({ timeout: 30000 })
  await page.waitForTimeout(800)
  await shot('test-run', 'Test workflow: a dry run with the values a person would type. Nothing is called; each step shows what it would send.')
  await page.keyboard.press('Escape')
  await page.getByRole('button', { name: 'YAML' }).click()
  await page.waitForTimeout(1500)
  // down to the steps, where the credential is
  await page.mouse.move(1100, 500)
  await page.mouse.wheel(0, 560)
  await page.waitForTimeout(600)
  await shot('yaml', 'The YAML drawer: the file the designer has been writing all along, the input schema and then the steps. It is what the change request will hold.')
  await page.keyboard.press('Escape')
  if (ONLY === 'test') return finish(browser)

  await page.getByRole('button', { name: 'Publish', exact: true }).click()
  const publishing = page.waitForResponse((r) => r.url().includes(`/workflows/${NAME}:publish`), { timeout: 30000 })
  await page.getByTestId('publish-confirm').click()
  const crId = (await (await publishing).json()).id
  console.log('change request', crId)
  await expect(page.getByTestId('cr-merge')).toHaveText('A teammate merges it', { timeout: 30000 })
  await shot('publish', 'Publish opens a change request with the workflow’s checks, right in the designer. The author waits for a teammate to merge it.', page.getByTestId('cr-checks'))

  // ------------------------------------------------------------ 7 · the reviewer merges
  await as('gonzalo')
  await go(`/change-requests/${crId}`)
  const merge = page.getByRole('button', { name: 'Merge', exact: true })
  await expect(merge).toBeEnabled({ timeout: 20000 })
  await page.waitForTimeout(800)
  await shot('cr-review', 'A reviewer opens the change request: the workflow file, what changed and the checks. Then Merge.')
  await merge.click()
  await page.getByRole('dialog').getByRole('button', { name: 'Merge and publish' }).click()
  await expect(page.getByText('Merged', { exact: true }).first()).toBeVisible({ timeout: 20000 })
  await page.waitForTimeout(800)
  await shot('merged', 'Merged: version 0.1.0 of Create VM is published, with its endpoint, its MCP tool and a run form in the catalog.')

  // ------------------------------------------------------------ 8 · run it
  await as('junior')
  await go(`/catalog/${NAME}`)
  const field = (name) => page.locator(`#run-${NAME}-${name}`)
  await expect(field('name')).toBeVisible({ timeout: 20000 })
  await field('name').fill('web-07')
  await field('image').fill('ubuntu-24.04')
  await field('cpu').fill('4')
  await field('memoryGb').fill('16')
  await shot('run-form', 'The run form, generated from the input defined in the designer. The junior engineer asks for web-07.')
  await page.getByRole('button', { name: 'Run', exact: true }).click()
  await page.waitForURL(/#\/jobs\//, { timeout: 30000 })
  const jobId = new URL(page.url()).hash.slice(1).split('/').pop()
  let job = {}
  for (let i = 0; i < 60 && !['succeeded', 'failed', 'cancelled'].includes(job.status); i++) {
    if (i) await page.waitForTimeout(1000)
    job = await (await fetch(`${API}/jobs/${jobId}`, { headers: { 'X-User': 'junior' } })).json()
  }
  console.log('job', jobId, job.status, JSON.stringify(job.output || job.outputs || {}).slice(0, 300))
  if (job.status !== 'succeeded') throw new Error(`the job ended ${job.status}`)
  await page.waitForTimeout(1500)
  await page.mouse.move(800, 600)
  await page.mouse.wheel(0, 320)
  await page.waitForTimeout(500)
  await shot('job', 'The job: both steps succeeded. Create VM called the VM API and Get VM read the VM back; the output is its id, address and state.')
  const vms = await (await fetch(`${VM_API}/dev/vms`, { headers: { Authorization: `Bearer ${TOKEN}` } })).json()
  console.log('the VM API now has', JSON.stringify(vms))
  await go(`/workflows/${NAME}`)
  await page.getByTestId('designer').getByRole('tab', { name: 'Executions' }).click()
  await page.waitForTimeout(1500)
  await shot('executions', 'Back in the designer, Executions shows the job on the canvas: each step green, and its input and output a click away.')
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
